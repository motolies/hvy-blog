-- =============================================
-- 주식 파생 계층 (수정주가 계수 MV · 소비자용 뷰)
-- PostgreSQL. db/stock-schema.sql 적용 후 psql 로 실행한다. 갱신은 ADJUST_FACTOR / DERIVED_REFRESH 잡이 수행한다.
-- =============================================
-- 원주가(tb_stock_daily_price)는 그대로 두고 기업행사에서 파생한 계수(tb_stock_adjust_event)를 조회 시점에 곱한다.
-- KIS 수정주가는 조회 시점마다 재계산되어 저장하면 재현할 수 없기 때문이다.
-- =============================================

-- ---------------------------------------------
-- 누적 수정계수: 이벤트 보유 종목만 펼친다 (전 종목 대비 약 1/200 크기).
-- 효력일(effective_date) 이전 거래일에 그 이후의 모든 이벤트 계수를 곱한다. EXP(SUM(LN(x))) = 곱.
-- 효력일이 KST 오늘 이후인 이벤트(예정 권리락)는 제외한다. DAILY 가 매일 REFRESH 하므로 효력일 당일 자동 반영된다.
-- ---------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_stock_adjust_factor AS
SELECT p.ticker,
       p.trade_date,
       COALESCE(EXP(SUM(LN(x.price_factor))), 1.0)::double precision  AS cum_price_factor,
       COALESCE(EXP(SUM(LN(x.volume_factor))), 1.0)::double precision AS cum_volume_factor
FROM tb_stock_daily_price p
         JOIN (SELECT DISTINCT ticker FROM tb_stock_adjust_event
               WHERE effective_date <= (now() AT TIME ZONE 'Asia/Seoul')::date) t ON t.ticker = p.ticker
         LEFT JOIN tb_stock_adjust_event x ON x.ticker = p.ticker
                                           AND x.effective_date > p.trade_date
                                           AND x.effective_date <= (now() AT TIME ZONE 'Asia/Seoul')::date
GROUP BY p.ticker, p.trade_date
WITH DATA;
-- REFRESH MATERIALIZED VIEW CONCURRENTLY 에 유니크 인덱스가 필요하다
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_stock_adjust_factor ON mv_stock_adjust_factor (ticker, trade_date);

-- ---------------------------------------------
-- 수정주가 뷰: 가격 소비자가 쓰는 유일한 진입점. 계수가 없는 종목·일자는 원주가 그대로다.
-- ---------------------------------------------
CREATE OR REPLACE VIEW vw_stock_daily_price_adj AS
SELECT p.ticker,
       p.trade_date,
       (p.open_price * COALESCE(f.cum_price_factor, 1))::double precision   AS adj_open,
       (p.high_price * COALESCE(f.cum_price_factor, 1))::double precision   AS adj_high,
       (p.low_price * COALESCE(f.cum_price_factor, 1))::double precision    AS adj_low,
       (p.close_price * COALESCE(f.cum_price_factor, 1))::double precision  AS adj_close,
       (p.volume * COALESCE(f.cum_volume_factor, 1))::double precision      AS adj_volume,
       p.trading_value,
       p.close_price                                                        AS raw_close,
       p.volume                                                             AS raw_volume,
       p.change_rate
FROM tb_stock_daily_price p
         LEFT JOIN mv_stock_adjust_factor f ON f.ticker = p.ticker AND f.trade_date = p.trade_date;

-- ---------------------------------------------
-- 영업일 캘린더 정본: KOSPI 종합(0001) 지수 일봉이 존재하는 날짜 집합. 미래 판정은 tb_stock_market_holiday 를 쓴다.
-- ---------------------------------------------
CREATE OR REPLACE VIEW vw_stock_market_calendar AS
SELECT trade_date
FROM tb_stock_index_daily
WHERE index_code = '0001';

-- =============================================
-- 지표 계층 (선순환의 출발점). 모두 vw_stock_daily_price_adj(수정주가) 위에서 계산하며 DOUBLE PRECISION 이다. 종목 일별 지표는 테이블(위 참조).
-- 갱신 순서: mv_stock_adjust_factor → tb_stock_daily_metric(증분 재계산) → mv_stock_index_metric → mv_stock_sector_daily
-- (DerivedMetricRefreshService.REFRESH_ORDER). 갱신이 3분을 넘으면 증분 테이블로 전환하되 뷰 이름은 유지한다.
-- =============================================

-- ---------------------------------------------
-- 종목 일별 지표는 2026-09-08 부터 테이블 tb_stock_daily_metric (db/stock-schema.sql) 이다. 전체 재계산이 25분이라 MV 로는
-- DAILY 락(40분) 안에 못 끝나, DerivedViewRefresher.recomputeDailyMetric 이 최근 N일만 창 함수로 다시 계산해 upsert 한다.
-- 옛 mv_stock_daily_metric 은 stock-derived-rebuild.sql 이 지운다.
-- ---------------------------------------------

-- ---------------------------------------------
-- 지수 지표: RS = 종목 수익률 − 지수 수익률(동일 창)
-- ---------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_stock_index_metric AS
SELECT index_code,
       trade_date,
       close_price::double precision                                                                       AS close_value,
       close_price::double precision / NULLIF(LAG(close_price, 1) OVER w, 0)::double precision - 1        AS ret_1d,
       close_price::double precision / NULLIF(LAG(close_price, 5) OVER w, 0)::double precision - 1        AS ret_5d,
       close_price::double precision / NULLIF(LAG(close_price, 20) OVER w, 0)::double precision - 1       AS ret_20d,
       close_price::double precision / NULLIF(LAG(close_price, 60) OVER w, 0)::double precision - 1       AS ret_60d,
       close_price::double precision / NULLIF(LAG(close_price, 120) OVER w, 0)::double precision - 1      AS ret_120d,
       AVG(close_price::double precision) OVER (w ROWS BETWEEN 19 PRECEDING AND CURRENT ROW)              AS ma_20,
       AVG(close_price::double precision) OVER (w ROWS BETWEEN 59 PRECEDING AND CURRENT ROW)              AS ma_60,
       AVG(close_price::double precision) OVER (w ROWS BETWEEN 119 PRECEDING AND CURRENT ROW)             AS ma_120
FROM tb_stock_index_daily
WINDOW w AS (PARTITION BY index_code ORDER BY trade_date)
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_stock_index_metric ON mv_stock_index_metric (index_code, trade_date);

-- ---------------------------------------------
-- 섹터 일별 집계 (KRX 중분류 현재 매핑 기준). 매핑 이력은 구축일부터라 과거 구성은 현재 구성으로 근사한다(문서화된 편향).
-- near_high_ratio: 52주 고점의 95% 이상에 있는 종목 비율.
-- ---------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_stock_sector_daily AS
SELECT s.sector_code,
       p.trade_date,
       COUNT(*)                                                                                   AS member_count,
       SUM(v.market_cap)                                                                          AS sum_market_cap,
       SUM(p.trading_value)                                                                       AS sum_trading_value,
       AVG(p.change_rate)::double precision                                                       AS avg_change_rate,
       (SUM(p.change_rate * v.market_cap) / NULLIF(SUM(v.market_cap), 0))::double precision       AS cap_weighted_change_rate,
       AVG(CASE WHEN p.change_rate > 0 THEN 1.0 ELSE 0.0 END)::double precision                   AS rising_ratio,
       AVG(CASE WHEN m.dist_high_52w >= -0.05 THEN 1.0 ELSE 0.0 END)::double precision            AS near_high_ratio,
       SUM(i.foreign_net_amt)                                                                     AS foreign_net_sum,
       SUM(i.institution_net_amt)                                                                 AS institution_net_sum
FROM tb_stock_daily_price p
         JOIN tb_stock_sector_map s ON s.ticker = p.ticker AND s.source = 'KRX' AND s.valid_to IS NULL
         LEFT JOIN tb_stock_valuation_daily v ON v.ticker = p.ticker AND v.trade_date = p.trade_date
         LEFT JOIN tb_stock_investor_daily i ON i.ticker = p.ticker AND i.trade_date = p.trade_date
         LEFT JOIN tb_stock_daily_metric m ON m.ticker = p.ticker AND m.trade_date = p.trade_date
GROUP BY s.sector_code, p.trade_date
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_stock_sector_daily ON mv_stock_sector_daily (sector_code, trade_date);

-- ---------------------------------------------
-- 유니버스: 활성·주권·비거래정지·비관리·비정리매매 + 시총 1,000억·거래대금 5일 평균 10억 하한 (1차 상수, 추후 테이블화).
-- 시총은 스냅샷이 있는 날만 판정한다(과거 백필 구간은 시총 이력이 없어 거래대금 하한만 적용).
-- ---------------------------------------------
CREATE OR REPLACE VIEW vw_stock_universe_daily AS
SELECT p.trade_date, p.ticker
FROM tb_stock_daily_price p
         JOIN tb_stock_master m ON m.ticker = p.ticker
         LEFT JOIN tb_stock_valuation_daily v ON v.ticker = p.ticker AND v.trade_date = p.trade_date
         LEFT JOIN tb_stock_daily_metric d ON d.ticker = p.ticker AND d.trade_date = p.trade_date
WHERE m.security_group = 'ST'
  AND m.is_active = TRUE
  AND m.is_suspended = FALSE
  AND m.is_administrative = FALSE
  AND m.is_liquidating = FALSE
  AND (v.market_cap IS NULL OR v.market_cap >= 100000000000)
  AND COALESCE(d.tv_avg_5d, p.trading_value) >= 1000000000;
