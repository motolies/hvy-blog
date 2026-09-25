# advisor M0 원인 측정 SQL (2026-09-25)

- 목적: 사용자가 느낀 "어드바이스가 잘 안 맞는다" 의 원인 가설 6개(계획 `~/.claude/plans/moto-planner-agent-pasted-content-id-6a-swirling-crab.md` "안 맞는 원인 가설")를 **코드 변경 없이** 운영 DB 로 확인한다. 결과를 보고 M6(b) 국면별 가중치·진입 규약 변경·유니버스 결정을 내린다.
- 전부 읽기 전용 `SELECT` 다. psql 에서 그대로 실행하고, 각 쿼리 맨 위 `p` CTE 의 날짜만 바꾼다.
- 컬럼명은 `db/advisor-schema.sql`·`db/stock-schema.sql`·`db/stock-derived.sql`(2026-09-25, 브랜치 `feat/advisor-longterm-factors` bcecc62 기준)와 대조했다. **DB 에서 실제로 돌려 본 적은 없다** — 첫 실행에서 문법·비용을 확인한다.
- 운영 문서는 `claudedocs/stock-advisor.md`. KPI API(`GET /api/advisor/admin/scores/summary`)와 숫자가 다르면 아래 "API 와의 차이" 를 먼저 본다.

## 0. 공통 규약

| 항목 | 값 | 근거 |
|---|---|---|
| 채점 행 | `tb_advisor_candidate_score` (`horizon_days`, `status <> 'MISSING'`, `excess_ret IS NOT NULL`) | `AdvisorKpiService` 와 같은 필터 |
| 픽 | `tb_advisor_pick` ⋈ 채점, `direction = 'LONG'` | 〃 |
| 후보군 평균 | 같은 advice 의 채점 행 전체 평균 | 〃 |
| 판단 필터 | `advice_kind = 'DAILY'`, `data_quality = 'OK'` | M1 이후 kind 를 반드시 명시한다 |
| 초과수익 | 진입 D+1 수정 시가 → 청산 D+h 수정 종가, 소속 시장 지수 대비 | 운영 문서 §7 |

**se 는 날짜 클러스터로 잰다.** 같은 날 픽들은 같은 시장 충격을 받아 서로 독립이 아니다. 그래서 먼저 날짜별 평균을 만들고, 그 날짜 값들의 표본 표준편차를 쓴다.

- `se_day = sd(일별 값) / √D` — D 는 날짜 수다.
- `se_overlap = sd(일별 값) / √(D/h)` — 보유 창이 h 일이고 매일 새로 판단하면 인접한 날짜의 창이 겹친다. 그래서 유효 표본을 D/h 로 본다. **판정에는 이 값을 쓴다.**
  - h=5 이면 se_day 의 약 2.2배다.
  - H20 사전 등록(주 1회, h=20)의 n/4 도 같은 논리다.

**API 와의 차이.** `/scores/summary` 의 valueAdd 는 픽 평균과 후보군 평균을 **픽 단위로 풀링**해 뺀다. 여기서는 날짜별로 뺀 뒤 평균한다. 날짜마다 픽 수가 다르면 두 값이 조금 다르다. 판정은 날짜 단위 값을 기준으로 한다.

## M0-1. 변형별 부가가치(픽 − 후보군) ± 날짜 클러스터 se

**목적.** 가설 1(표본이 작아 생긴 노이즈)을 확인한다. LIVE 의 부가가치가 se 안에서 0 과 구별되는지 본다. 또 섀도(QUANT_TOPN·LLM_NOMEM·LLM_NONEWS·QUANT_TOPN_BROAD)와 비교해 어느 쪽이 나은지 본다.

```sql
-- M0-1a 변형별 부가가치: 날짜별 (LONG 픽 평균 − 후보군 평균) → 평균 ± se
WITH p AS (SELECT DATE '2026-09-14' AS d_from, CURRENT_DATE AS d_to, 'DAILY'::varchar AS kind, 5::smallint AS h),
adv AS (
    SELECT a.advice_id, a.base_date, a.variant
    FROM tb_advisor_advice a CROSS JOIN p
    WHERE a.advice_kind = p.kind AND a.base_date BETWEEN p.d_from AND p.d_to AND a.data_quality = 'OK'
),
pick_d AS (
    SELECT pk.advice_id, AVG(s.excess_ret) AS pick_mean, COUNT(*) AS n_pick
    FROM adv
             JOIN tb_advisor_pick pk ON pk.advice_id = adv.advice_id
             JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker
             CROSS JOIN p
    WHERE s.horizon_days = p.h AND pk.direction = 'LONG' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
    GROUP BY pk.advice_id
),
pool_d AS (
    SELECT s.advice_id, AVG(s.excess_ret) AS pool_mean, COUNT(*) AS n_pool
    FROM adv
             JOIN tb_advisor_candidate_score s ON s.advice_id = adv.advice_id
             CROSS JOIN p
    WHERE s.horizon_days = p.h AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
    GROUP BY s.advice_id
),
daily AS (
    SELECT adv.base_date, adv.variant, pd.pick_mean, pl.pool_mean, pd.pick_mean - pl.pool_mean AS va, pd.n_pick
    FROM adv
             JOIN pick_d pd ON pd.advice_id = adv.advice_id
             JOIN pool_d pl ON pl.advice_id = adv.advice_id
)
SELECT d.variant,
       COUNT(*)                                                                                 AS days,
       SUM(d.n_pick)                                                                            AS picks,
       ROUND(AVG(d.pick_mean)::numeric, 5)                                                      AS pick_mean,
       ROUND(AVG(d.pool_mean)::numeric, 5)                                                      AS pool_mean,
       ROUND(AVG(d.va)::numeric, 5)                                                             AS value_add,
       ROUND((STDDEV_SAMP(d.va) / SQRT(COUNT(*)::float8))::numeric, 5)                          AS se_day,
       ROUND((STDDEV_SAMP(d.va) / SQRT(COUNT(*)::float8 / p.h))::numeric, 5)                    AS se_overlap,
       ROUND((AVG(d.va) / NULLIF(STDDEV_SAMP(d.va) / SQRT(COUNT(*)::float8 / p.h), 0))::numeric, 2) AS t_overlap
FROM daily d CROSS JOIN p
GROUP BY d.variant, p.h
ORDER BY d.variant;
```

```sql
-- M0-1b LIVE 대비 짝지은 차이: 같은 기준일 (LIVE 픽 평균 − 변형 픽 평균).
-- M0-1a 의 WITH 절(p … daily)을 그대로 두고 마지막 SELECT 만 아래로 바꾼다.
SELECT o.variant,
       COUNT(*)                                                                                        AS days,
       ROUND(AVG(l.pick_mean - o.pick_mean)::numeric, 5)                                               AS diff,
       ROUND((STDDEV_SAMP(l.pick_mean - o.pick_mean) / SQRT(COUNT(*)::float8 / p.h))::numeric, 5)      AS se_overlap,
       ROUND((AVG(l.pick_mean - o.pick_mean)
           / NULLIF(STDDEV_SAMP(l.pick_mean - o.pick_mean) / SQRT(COUNT(*)::float8 / p.h), 0))::numeric, 2) AS t_overlap
FROM daily l
         JOIN daily o ON o.base_date = l.base_date AND o.variant <> 'LIVE'
         CROSS JOIN p
WHERE l.variant = 'LIVE'
GROUP BY o.variant, p.h
ORDER BY o.variant;
```

**해석**

- `days` 가 40 미만이면 결론을 내지 않는다. 표만 기록한다.
  - h=5 에서 se_overlap ≈ σ/√(D/5) 이다. σ≈2%/5일, D=40 이면 약 0.7%다.
- LIVE `value_add` 가 ±2·se_overlap 안에 있으면 "구별 불가" 로 적는다. 이것만으로 LLM 이 해롭다고 보지는 않는다.
- M0-1b 에서 `QUANT_TOPN` 과의 차이를 볼 때
  - `t_overlap ≤ −2` 면 LLM 이 정량 규칙보다 나쁘다.
  - `t_overlap ≥ 2` 면 LLM 이 부가가치를 만든다.
- `QUANT_TOPN_BROAD` 는 후보 모집단이 다르다(KOSPI 전체). 그래서 `value_add` 끼리 비교하지 않고 M0-1b 의 픽 평균 차이로만 본다. 이 값이 M3 사전 등록 판정의 지표다(운영 문서 §8).

**다음 결정**

- 운영 문서 §4 "3개월 규칙" 의 입력이다. LIVE 부가가치가 se 안에서 약 0 이면 LLM 은 설명만 맡기고 픽은 QUANT_TOPN 으로 바꾼다.
- 결과가 "구별 불가" 라면 가설 1(노이즈)이 유력하다. 이때는 M0-2 이하의 구조적 원인보다 **표본 누적**이 먼저다.

## M0-2. 규칙 추세 라벨별 LIVE·QUANT_TOPN 초과수익

**국면 라벨이 저장된 곳**

| 컬럼 | 내용 | 채워진 범위 |
|---|---|---|
| `tb_advisor_advice.trend_kospi` / `trend_kosdaq` | 규칙 추세 BULL/SIDEWAYS/BEAR. `TrendSql` 이 기준일에 확정한 값을 저장 시점에 동결한다 | advice-v2(2026-09-13) 이후 DAILY LIVE |
| `tb_advisor_advice.regime_json` | `->>'trend'`(KOSPI 규칙 추세), `->>'vol'`(LOW/NORMAL/HIGH/UNKNOWN), `->'policy'` | M6 이후 DAILY·ADHOC·H20 LIVE 와 LLM 섀도. QUANT 섀도·MORNING 은 NULL |
| `tb_advisor_advice.trend_json` | 지수별 상세 `[{indexCode,code,score,components,since,days,…}]` | advice-v2 이후 |
| `tb_advisor_advice.regime_code` | LLM 의 5일 위험 선호 RISK_ON/NEUTRAL/RISK_OFF. **규칙 추세와 다른 축이다** | 전체 |

QUANT 섀도 행에는 라벨이 비어 있을 수 있다. 그래서 라벨은 **같은 기준일 DAILY LIVE 행에서** 가져온다.

**목적.** 가설 3(가중치가 국면과 무관함)을 성과 쪽에서 확인한다. 약세·보합 국면에서만 픽이 무너지는지 본다.

```sql
-- M0-2 규칙 추세 라벨(같은 기준일 DAILY LIVE 가 동결한 값) × 변형: 픽 평균 초과·부가가치 ± 날짜 se
WITH p AS (SELECT DATE '2026-09-14' AS d_from, CURRENT_DATE AS d_to, 5::smallint AS h),
lbl AS (
    SELECT a.base_date,
           COALESCE(a.trend_kospi, a.regime_json ->> 'trend', 'UNKNOWN') AS trend,
           COALESCE(a.regime_json ->> 'vol', 'N/A')                     AS vol
    FROM tb_advisor_advice a CROSS JOIN p
    WHERE a.advice_kind = 'DAILY' AND a.variant = 'LIVE' AND a.base_date BETWEEN p.d_from AND p.d_to
),
adv AS (
    SELECT a.advice_id, a.base_date, a.variant
    FROM tb_advisor_advice a CROSS JOIN p
    WHERE a.advice_kind = 'DAILY' AND a.variant IN ('LIVE', 'QUANT_TOPN') AND a.data_quality = 'OK'
      AND a.base_date BETWEEN p.d_from AND p.d_to
),
daily AS (
    SELECT adv.base_date, adv.variant,
           AVG(s.excess_ret) FILTER (WHERE pk.direction = 'LONG') AS pick_mean,
           AVG(s.excess_ret)                                      AS pool_mean
    FROM adv
             JOIN tb_advisor_candidate_score s ON s.advice_id = adv.advice_id
             LEFT JOIN tb_advisor_pick pk ON pk.advice_id = s.advice_id AND pk.ticker = s.ticker
             CROSS JOIN p
    WHERE s.horizon_days = p.h AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
    GROUP BY adv.base_date, adv.variant
)
SELECT l.trend, l.vol, d.variant,
       COUNT(*)                                                                                  AS days,
       ROUND(AVG(d.pick_mean)::numeric, 5)                                                       AS pick_mean,
       ROUND((STDDEV_SAMP(d.pick_mean) / SQRT(COUNT(*)::float8 / p.h))::numeric, 5)              AS se_overlap,
       ROUND(AVG(d.pick_mean - d.pool_mean)::numeric, 5)                                         AS value_add
FROM daily d
         JOIN lbl l ON l.base_date = d.base_date
         CROSS JOIN p
WHERE d.pick_mean IS NOT NULL
GROUP BY l.trend, l.vol, d.variant, p.h
ORDER BY l.trend, l.vol, d.variant;
```

**해석**

- 라벨당 `days` 가 20 미만인 칸은 읽지 않는다.
  - 운영 기간이 짧아 대부분 한 라벨에 몰려 있을 것이다. 라벨 분포 자체가 첫 결과다.
- 라벨 사이 차이가 크더라도, LIVE 와 QUANT_TOPN 이 **같이** 움직이면 원인은 시그널·시장 쪽이다. LLM 탓이 아니다.
- LIVE 만 특정 라벨에서 무너지면 프롬프트 규칙 13(국면별 행동)과 정책 표(regime-policy-v1)를 점검한다.

**다음 결정**

- 운영 표본은 짧으므로, 국면 효과의 판정은 M0-3(2020~ IC 이력)이 맡는다.
- 이 표는 M6 정책 표 v2 를 논의할 때 참고 자료다.
  - BEAR 칸의 LIVE `pick_mean` 이 음수이고 QUANT_TOPN 보다 나쁘면 BEAR LONG 상한을 더 강하게 하는 근거가 된다(알려진 한계: pick-max−2=8 은 약함).

## M0-3. 국면 라벨별 백필 IC 와 국면 간 차이의 t

**목적.** 가설 3의 본 검정이다. 시그널 IC 가 국면(규칙 추세·변동성)에 따라 달라지는지 2020~ `tb_advisor_signal_ic_daily` 이력으로 본다.

**전제**

- `IC_BACKFILL` 이 끝나 있어야 한다. M5 뒤에는 horizon 5·20 행이 모두 있다.
- 라벨은 `TrendSql.labelCtes()` 를 yml 기본값(ret60 0.05, breadth 0.60/0.40, bull +2, bear −2, confirm 2)으로 **그대로 옮긴 것**이다.
  - yml `advisor.trend.*` 를 바꿨다면 아래 리터럴도 같이 바꾼다.
  - 이 정의는 인과적이다(LAG 만 씀). 그래서 날짜 d 의 라벨은 d 에 알 수 있던 값이다.

```sql
-- M0-3a 규칙 추세 라벨(0001) × 시그널: 평균 IC ± 겹침 보정 se, 라벨 쌍 차이의 t
WITH bre AS (
    SELECT CASE market_type WHEN 'KOSPI' THEN '0001' WHEN 'KOSDAQ' THEN '1001' END AS index_code, trade_date, above_ma20_ratio
    FROM mv_stock_market_breadth_daily WHERE market_type IN ('KOSPI', 'KOSDAQ')
),
comp AS (
    SELECT i.index_code, i.trade_date,
           CASE WHEN i.ma_20 IS NULL THEN 0 WHEN i.close_value > i.ma_20 THEN 1 WHEN i.close_value < i.ma_20 THEN -1 ELSE 0 END AS c_ma20,
           CASE WHEN i.ma_20 IS NULL OR i.ma_60 IS NULL THEN 0 WHEN i.ma_20 > i.ma_60 THEN 1 WHEN i.ma_20 < i.ma_60 THEN -1 ELSE 0 END AS c_ma60,
           CASE WHEN i.ma_60 IS NULL OR i.ma_120 IS NULL THEN 0 WHEN i.ma_60 > i.ma_120 THEN 1 WHEN i.ma_60 < i.ma_120 THEN -1 ELSE 0 END AS c_ma120,
           CASE WHEN i.ret_60d IS NULL THEN 0 WHEN i.ret_60d >= 0.05 THEN 1 WHEN i.ret_60d <= -0.05 THEN -1 ELSE 0 END AS c_ret60,
           CASE WHEN b.above_ma20_ratio IS NULL THEN 0 WHEN b.above_ma20_ratio >= 0.60 THEN 1
                WHEN b.above_ma20_ratio <= 0.40 THEN -1 ELSE 0 END AS c_breadth
    FROM mv_stock_index_metric i
             LEFT JOIN bre b ON b.index_code = i.index_code AND b.trade_date = i.trade_date
    WHERE i.index_code = '0001'
),
raw AS (
    SELECT c.*, CASE WHEN c.c_ma20 + c.c_ma60 + c.c_ma120 + c.c_ret60 + c.c_breadth >= 2 THEN 'BULL'
                     WHEN c.c_ma20 + c.c_ma60 + c.c_ma120 + c.c_ret60 + c.c_breadth <= -2 THEN 'BEAR'
                     ELSE 'SIDEWAYS' END AS raw_code
    FROM comp c
),
lagged AS (SELECT r.*, LAG(r.raw_code) OVER (ORDER BY r.trade_date) AS prev_raw FROM raw r),
run AS (SELECT l.*, SUM(CASE WHEN l.raw_code = l.prev_raw THEN 0 ELSE 1 END) OVER (ORDER BY l.trade_date) AS run_id FROM lagged l),
confirmable AS (
    SELECT r.*, CASE WHEN ROW_NUMBER() OVER (PARTITION BY r.run_id ORDER BY r.trade_date) >= 2 THEN r.raw_code END AS c FROM run r
),
grp AS (SELECT c.*, COUNT(c.c) OVER (ORDER BY c.trade_date) AS g FROM confirmable c),
lbl AS (
    SELECT g.trade_date, COALESCE(FIRST_VALUE(g.c) OVER (PARTITION BY g.g ORDER BY g.trade_date), g.raw_code) AS trend_code
    FROM grp g
),
ic AS (
    SELECT i.signal_code, i.trade_date, i.rank_ic, l.trend_code
    FROM tb_advisor_signal_ic_daily i
             JOIN lbl l ON l.trade_date = i.trade_date
    WHERE i.horizon_days = 5                      -- h=20 으로 볼 때는 여기와 아래 5 를 20 으로
),
g AS (
    SELECT signal_code, trend_code, COUNT(*) AS n, AVG(rank_ic) AS m,
           STDDEV_SAMP(rank_ic) / SQRT(COUNT(*)::float8 / 5) AS se
    FROM ic GROUP BY signal_code, trend_code
)
SELECT a.signal_code, a.trend_code AS lbl_a, b.trend_code AS lbl_b, a.n AS n_a, b.n AS n_b,
       ROUND(a.m::numeric, 4) AS ic_a, ROUND(b.m::numeric, 4) AS ic_b,
       ROUND((a.m - b.m)::numeric, 4) AS diff,
       ROUND(((a.m - b.m) / NULLIF(SQRT(a.se ^ 2 + b.se ^ 2), 0))::numeric, 2) AS t
FROM g a
         JOIN g b ON b.signal_code = a.signal_code AND a.trend_code < b.trend_code
ORDER BY ABS((a.m - b.m) / NULLIF(SQRT(a.se ^ 2 + b.se ^ 2), 0)) DESC NULLS LAST;
```

```sql
-- M0-3b 강건성: 같은 표를 전반·후반으로 나눠 부호가 유지되는지.
-- M0-3a 의 WITH 절에서 ic CTE 까지 그대로 두고, g 와 마지막 SELECT 를 아래로 바꾼다.
SELECT signal_code, trend_code,
       CASE WHEN trade_date < DATE '2023-01-01' THEN '2020-22' ELSE '2023-' END AS half,
       COUNT(*) AS n, ROUND(AVG(rank_ic)::numeric, 4) AS ic,
       ROUND((STDDEV_SAMP(rank_ic) / SQRT(COUNT(*)::float8 / 5))::numeric, 4) AS se
FROM ic
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;
```

```sql
-- M0-3c 라벨 에피소드 수: 같은 라벨이 연속된 구간 수. 날짜 수가 많아도 에피소드가 적으면 독립 표본이 적다.
-- M0-3a 의 WITH 절에서 lbl CTE 까지 두고 마지막 SELECT 만 바꾼다. (IC 행이 있는 기간만 센다)
SELECT trend_code, COUNT(*) AS days, COUNT(*) FILTER (WHERE trend_code IS DISTINCT FROM prev) AS episodes
FROM (SELECT l.*, LAG(l.trend_code) OVER (ORDER BY l.trade_date) AS prev FROM lbl l
      WHERE l.trade_date BETWEEN (SELECT MIN(trade_date) FROM tb_advisor_signal_ic_daily)
                             AND (SELECT MAX(trade_date) FROM tb_advisor_signal_ic_daily)) x
GROUP BY trend_code ORDER BY trend_code;
```

변동성 국면으로 나눌 때는 `MarketRegimeService.VOL_SQL` 을 날짜마다 재현한 아래 CTE 를 `lbl` 대신 조인한다.

- σ20 은 창 20일이 꽉 찬 날만 쓴다.
- 백분위는 기준일 앞 5년의 σ20 분포에서 계산한다. 분포 표본이 250 미만이면 UNKNOWN 이다.
- 경계일 처리(5년 창 시작점)가 서비스와 하루 차이 날 수 있는 근사다.

```sql
-- M0-3d 변동성 국면 라벨(날짜별 재현) — M0-3a 의 lbl 대신 vr 을 쓰고 trend_code 를 vol 로 바꾼다
WITH s AS (
    SELECT trade_date, STDDEV_SAMP(ret_1d) OVER w AS sigma, COUNT(ret_1d) OVER w AS n
    FROM mv_stock_index_metric WHERE index_code = '0001'
    WINDOW w AS (ORDER BY trade_date ROWS BETWEEN 19 PRECEDING AND CURRENT ROW)
),
v AS (SELECT trade_date, sigma FROM s WHERE n = 20),
pct AS (
    SELECT c.trade_date, COUNT(h.trade_date) AS hist, COUNT(h.trade_date) FILTER (WHERE h.sigma < c.sigma) AS below
    FROM v c
             LEFT JOIN v h ON h.trade_date < c.trade_date AND h.trade_date > c.trade_date - INTERVAL '5 years'
    GROUP BY c.trade_date
),
vr AS (
    SELECT trade_date,
           CASE WHEN hist < 250 THEN 'UNKNOWN'
                WHEN below::float8 / hist >= 0.80 THEN 'HIGH'
                WHEN below::float8 / hist < 0.30 THEN 'LOW'
                ELSE 'NORMAL' END AS vol
    FROM pct
)
SELECT vol, COUNT(*) FROM vr GROUP BY vol ORDER BY vol;   -- 분포 확인용. 조인할 때는 이 SELECT 를 버린다
```

**해석**

- 시그널 13개 × 라벨 쌍 3개 = 39개 검정이다. 우연만으로도 |t|≥2 가 2개쯤 나온다. 그래서 아래 **셋을 모두** 만족할 때만 "국면 효과 있음" 으로 본다.
  1. |t| ≥ 2 (M0-3a)
  2. 전반·후반 부호가 같다 (M0-3b)
  3. 두 라벨 모두 에피소드가 5개 이상이다 (M0-3c)
- 가중치 비중이 큰 시그널(MOM_20D·FOREIGN_FLOW·NEAR_HIGH_52W·TV_SURGE, base 0.10~0.12)에서 나온 효과만 실익이 있다. VOL_20D(0.03) 같은 작은 시그널은 효과가 있어도 무시한다.
- 생존 편향(M0-7)이 IC 수준을 부풀린다. 다만 **국면 간 차이**에는 편향이 대체로 상쇄된다(같은 유니버스). 그래서 이 검정은 수준보다 차이를 본다.

**다음 결정(사전 등록)**

- 위 셋을 만족하는 시그널이 하나라도 있으면 **M6(b) 국면별 가중치에 착수한다**.
  - 설계 초안: `tb_advisor_weight_set` 에 국면 차원을 둔다. 또는 국면별 배수 표를 정책 표처럼 사전 고정하는 안을 먼저 검토한다(학습 창이 국면별로 쪼개지면 n_eff 가 1/3 로 준다).
- 없으면 M6(b)는 보류한다. 가설 3은 기각한다.
- 판정 규칙은 결과를 본 뒤 바꾸지 않는다.

## M0-4. KOSPI200 부분집합 IC 와 전체 IC 비교

**목적.** M3 은 추천 후보를 KOSPI200 으로 한정했고, 가중치는 KOSPI 전체 IC 로 학습한다. 두 유니버스에서 시그널 효력이 다르면 "전체에서 배운 가중치를 K200 후보에 적용" 하는 구조가 맞지 않는다.

**방법**

- `SignalIcService.compute` 와 같은 정의를 쓴다.
  - 유니버스는 `vw_stock_universe_daily` ⋈ KOSPI 이고, d+h 는 캘린더 h번째 영업일이다.
  - 초과수익 = 수정 종가 수익률 − 0001 수익률이다.
  - rank 상관이며, 날짜당 표본 30 미만은 버린다.
- 표현식이 단순한 시그널 6개만 다시 계산한다.
- 저장된 IC 테이블에는 부분집합 구분이 없어서 즉석 계산한다.

**한계(반드시 함께 보고한다)**

- `K200_NOW` 는 **현재 스냅샷**(`tb_stock_master.is_kospi200`)이다. 과거 날짜에 오늘의 구성종목을 적용하므로 룩어헤드이자 생존 편향이다.
  - 최근에 편입된 종목은 대개 앞서 올랐던 종목이다. 그래서 모멘텀 IC 가 부풀려진다.
- `K200_PIT` 는 `tb_stock_master_history` 의 그날 유효 행으로 거른 값이다. 룩어헤드가 없지만 **이력 수집 시작일 이후만** 존재한다(그 전은 NULL 이라 빠진다).
- 결론은 `K200_PIT` 를 우선하고, `K200_NOW` 는 방향 참고로만 쓴다.

```sql
-- M0-4 KOSPI 전체 vs KOSPI200(현재 스냅샷·PIT) rank-IC, h=5, 날짜 대응 차이의 t
WITH p AS (SELECT DATE '2025-01-01' AS d_from, CURRENT_DATE AS d_to, 5 AS h),
cal AS (SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar),
ex AS (
    SELECT c0.trade_date, ch.trade_date AS exit_date
    FROM cal c0 JOIN cal ch ON ch.rn = c0.rn + (SELECT h FROM p) CROSS JOIN p
    WHERE c0.trade_date BETWEEN p.d_from AND p.d_to
),
f AS (
    SELECT u.trade_date, u.ticker,
           ms.is_kospi200 AS k200_now,
           mh.is_kospi200 AS k200_pit,
           m.ret_20d, m.ret_60d, m.dist_high_52w, m.tv_ratio_5_60,
           m.ret_20d - ix.ret_20d                           AS rs_index,
           m.foreign_net_5d / NULLIF(m.tv_avg_60d * 5, 0)   AS foreign_flow,
           (mx.adj_close / NULLIF(m.adj_close, 0) - 1) - (ixe.close_value / NULLIF(ix.close_value, 0) - 1) AS ex
    FROM vw_stock_universe_daily u
             JOIN ex e ON e.trade_date = u.trade_date
             JOIN tb_stock_master ms ON ms.ticker = u.ticker AND ms.market_type = 'KOSPI'
             JOIN tb_stock_daily_metric m ON m.ticker = u.ticker AND m.trade_date = u.trade_date
             LEFT JOIN tb_stock_daily_metric mx ON mx.ticker = u.ticker AND mx.trade_date = e.exit_date
             LEFT JOIN mv_stock_index_metric ix ON ix.index_code = '0001' AND ix.trade_date = u.trade_date
             LEFT JOIN mv_stock_index_metric ixe ON ixe.index_code = '0001' AND ixe.trade_date = e.exit_date
             LEFT JOIN tb_stock_master_history mh ON mh.ticker = u.ticker AND mh.valid_from <= u.trade_date
                                                AND (mh.valid_to IS NULL OR mh.valid_to > u.trade_date)
),
un AS (
    SELECT f.trade_date, f.k200_now, f.k200_pit, s.code, s.v, f.ex
    FROM f CROSS JOIN LATERAL (VALUES ('MOM_20D', f.ret_20d), ('MOM_60D', f.ret_60d), ('NEAR_HIGH_52W', f.dist_high_52w),
                                      ('TV_SURGE', f.tv_ratio_5_60), ('RS_INDEX', f.rs_index), ('FOREIGN_FLOW', f.foreign_flow)) AS s(code, v)
    WHERE f.ex IS NOT NULL AND s.v IS NOT NULL
),
sub AS (
    SELECT 'ALL' AS uni, trade_date, code, v, ex FROM un
    UNION ALL SELECT 'K200_NOW', trade_date, code, v, ex FROM un WHERE k200_now
    UNION ALL SELECT 'K200_PIT', trade_date, code, v, ex FROM un WHERE k200_pit
),
rk AS (
    SELECT uni, trade_date, code,
           RANK() OVER (PARTITION BY uni, trade_date, code ORDER BY v)  AS rs,
           RANK() OVER (PARTITION BY uni, trade_date, code ORDER BY ex) AS rx
    FROM sub
),
ic AS (
    SELECT uni, trade_date, code, corr(rs, rx) AS ic, COUNT(*) AS n
    FROM rk GROUP BY uni, trade_date, code
    HAVING COUNT(*) >= 30 AND corr(rs, rx) IS NOT NULL
)
SELECT a.code, a.uni,
       COUNT(*)                                                                                   AS days,
       ROUND(AVG(a.n))                                                                            AS avg_n,
       ROUND(AVG(a.ic)::numeric, 4)                                                               AS mean_ic,
       ROUND(AVG(a.ic - b.ic)::numeric, 4)                                                        AS diff_vs_all,
       ROUND((AVG(a.ic - b.ic) / NULLIF(STDDEV_SAMP(a.ic - b.ic) / SQRT(COUNT(*)::float8 / 5), 0))::numeric, 2) AS t_diff
FROM ic a
         JOIN ic b ON b.code = a.code AND b.trade_date = a.trade_date AND b.uni = 'ALL'
GROUP BY a.code, a.uni
ORDER BY a.code, a.uni;
```

- `ALL` 행의 `mean_ic` 가 같은 기간 저장값(`SELECT signal_code, AVG(rank_ic) FROM tb_advisor_signal_ic_daily WHERE horizon_days = 5 AND trade_date BETWEEN … GROUP BY 1`)과 소수 셋째 자리까지 비슷해야 정의가 맞은 것이다. 크게 다르면 쿼리를 먼저 의심한다.
- 비용: 1년 × KOSPI 유니버스 약 800 × 6 시그널 ≈ 120만 행. 기간을 늘릴 때는 한 해씩 나눠 돌린다.
- K200 은 날짜당 표본이 약 190 이다. ALL 보다 IC 추정 분산이 크다.

**해석**

- `K200_PIT` 의 `t_diff` 가 |t|≥2 이고 부호가 가중치 방향과 반대인 시그널(예: 전체에서는 양, K200 에서는 0 또는 음)이 **비중 큰 시그널** 중에 있으면, 전체 IC 가중치를 K200 후보에 적용하는 가정이 깨진 것이다.
- 모두 |t|<2 면 현 구조(넓은 IC 유니버스 + 후보만 K200)를 유지한다.

**다음 결정**

- 깨졌다면 선택지는 둘이다. 판단은 M3 BROAD 판정(운영 문서 §8)과 함께 내린다.
  - (a) `advisor.pick-universe: ALL` 로 되돌린다.
  - (b) K200 PIT 이력이 충분히(n_eff ≥ 24 ≈ 120영업일) 쌓인 뒤 K200 전용 IC 세트를 별도 학습한다.
- IC 유니버스 자체를 K200 으로 좁히는 안은 **이력 시작 전 구간이 룩어헤드**라 채택하지 않는다(계획의 결정).

## M0-5. 픽의 D+1 시가 갭과 이후 초과수익 (갭 추격 가설)

**목적.** 가설 6을 확인한다. 저녁에 강했던 종목을 고르면 다음 날 시가가 갭 상승한다. 채점은 그 시가에서 진입하므로 수익의 상당 부분을 이미 놓친다. 게다가 갭 뒤에 되돌림까지 먹는지 본다.

**방법**

- 갭 = `adj_open(진입일) / adj_close(기준일) − 1` 이다(`vw_stock_daily_price_adj`, 같은 누적 계수라 비율이 맞다).
- 지수 갭 = `tb_stock_index_daily` 의 `open_price(진입일) / close_price(기준일) − 1` 이다(벤치 지수 = 후보 `bench_index_code`).
- 초과 갭 = 갭 − 지수 갭이다.

```sql
-- M0-5a 픽 vs 비픽 후보: 초과 갭과 이후(시가→청산) 초과수익, 날짜 클러스터
WITH p AS (SELECT DATE '2026-09-14' AS d_from, CURRENT_DATE AS d_to),
c AS (
    SELECT a.base_date, s.ticker,
           (pk.ticker IS NOT NULL AND pk.direction = 'LONG')                               AS is_pick,
           s.excess_ret,
           (po.adj_open / NULLIF(pb.adj_close, 0) - 1)
             - (io.open_price / NULLIF(ib.close_price, 0) - 1)::float8                     AS ex_gap
    FROM tb_advisor_advice a
             CROSS JOIN p
             JOIN tb_advisor_candidate_score s ON s.advice_id = a.advice_id AND s.horizon_days = 5
             JOIN tb_advisor_candidate cd ON cd.advice_id = s.advice_id AND cd.ticker = s.ticker
             LEFT JOIN tb_advisor_pick pk ON pk.advice_id = s.advice_id AND pk.ticker = s.ticker
             JOIN vw_stock_daily_price_adj pb ON pb.ticker = s.ticker AND pb.trade_date = a.base_date
             JOIN vw_stock_daily_price_adj po ON po.ticker = s.ticker AND po.trade_date = s.entry_date
             JOIN tb_stock_index_daily ib ON ib.index_code = cd.bench_index_code AND ib.trade_date = a.base_date
             JOIN tb_stock_index_daily io ON io.index_code = cd.bench_index_code AND io.trade_date = s.entry_date
    WHERE a.advice_kind = 'DAILY' AND a.variant = 'LIVE' AND a.data_quality = 'OK'
      AND a.base_date BETWEEN p.d_from AND p.d_to
      AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
),
daily AS (
    SELECT base_date,
           AVG(ex_gap) FILTER (WHERE is_pick)          AS pick_gap,
           AVG(ex_gap) FILTER (WHERE NOT is_pick)      AS rest_gap,
           AVG(excess_ret) FILTER (WHERE is_pick)      AS pick_after,
           AVG(excess_ret) FILTER (WHERE NOT is_pick)  AS rest_after
    FROM c GROUP BY base_date
)
SELECT COUNT(*)                                                                                     AS days,
       ROUND(AVG(pick_gap)::numeric, 5)                                                             AS pick_ex_gap,
       ROUND(AVG(rest_gap)::numeric, 5)                                                             AS rest_ex_gap,
       ROUND((AVG(pick_gap - rest_gap) / NULLIF(STDDEV_SAMP(pick_gap - rest_gap) / SQRT(COUNT(*)::float8), 0))::numeric, 2) AS t_gap_day,
       ROUND(AVG(pick_after)::numeric, 5)                                                           AS pick_after,
       ROUND(AVG(rest_after)::numeric, 5)                                                           AS rest_after,
       ROUND(AVG(pick_gap + pick_after)::numeric, 5)                                                AS pick_close_to_close_approx
FROM daily
WHERE pick_gap IS NOT NULL AND rest_gap IS NOT NULL;
```

```sql
-- M0-5b 초과 갭 5분위(기준일 안 후보끼리) × 픽 여부: 이후 초과수익.
-- M0-5a 에서 p·c CTE 만 남기고(daily CTE 와 마지막 SELECT 는 지운다) c 뒤에 아래를 이어 쓴다.
, q AS (SELECT c.*, NTILE(5) OVER (PARTITION BY base_date ORDER BY ex_gap) AS gap_q FROM c WHERE ex_gap IS NOT NULL),
qd AS (SELECT base_date, gap_q, is_pick, AVG(excess_ret) AS after_mean, AVG(ex_gap) AS gap_mean, COUNT(*) AS n
       FROM q GROUP BY base_date, gap_q, is_pick)
SELECT gap_q, is_pick, COUNT(*) AS days, SUM(n) AS rows,
       ROUND(AVG(gap_mean)::numeric, 5)                                                   AS ex_gap,
       ROUND(AVG(after_mean)::numeric, 5)                                                 AS after_excess,
       ROUND((STDDEV_SAMP(after_mean) / SQRT(COUNT(*)::float8 / 5))::numeric, 5)          AS se_overlap
FROM qd GROUP BY gap_q, is_pick ORDER BY gap_q, is_pick;
```

**해석**

- 갭 추격이 성립하려면 아래 둘이 **함께** 나와야 한다.
  - `pick_ex_gap > rest_ex_gap` 이고 `t_gap_day ≥ 2` 다. 픽이 비픽 후보보다 더 크게 갭 상승한다. 저녁 신호는 맞았지만 그 몫이 밤사이에 반영됐다는 뜻이다.
  - M0-5b 에서 위 분위(4·5)의 `after_excess` 가 아래 분위(1·2)보다 낮다. 갭 뒤 되돌림이다.
- `pick_close_to_close_approx` 가 양수이고 `pick_after` 가 0 근처면, 선택은 맞았고 **진입 규약이 수익을 버린 것**이다. 이 경우 LLM 을 탓하지 않는다.
  - 초과 기준이라 복리 항을 뺀 근사다.
- 표본이 적으면 분위×픽 칸이 비거나 days 가 작다. 칸당 days 20 미만은 읽지 않는다.

**다음 결정**

- 성립하면 다음 대안을 **새 버전으로 사전 등록**한 뒤 비교한다. 채점 규약(D+1 시가 진입)은 바꾸지 않는다. 바꾸면 과거 성과와 이어지지 않는다.
  - (a) 가드에 "초과 갭 > k·σ₁d 인 픽은 진입 보류" 를 넣는다. 다만 저녁 판단 시점에는 갭을 모른다. 그래서 적용 지점은 07:40 MORNING 재판정(예상 갭 기준)이 된다.
  - (b) M4 MORNING 의 DROP 사유 분석. MORNING − DAILY 의 트리거일 차이(운영 문서 §8, M4 판정)와 같이 본다.
- 성립하지 않으면 가설 6은 기각한다. 운영 문서 §7 의 "INDEX 를 open(D+1)→close 로 바꾸는 규약 변경은 6개월 뒤 갭 기여도를 본 뒤" 는 그대로 둔다.

## M0-6. 채팅의 "도구가 없다" 응답과 tool_calls

**목적.** M2 의 원인 가설을 사후 확인한다. chat-v1 에서 추천 생성·호라이즌 요청에 "도구가 없다" 고 답한 사례가 실제로 있었는지 본다. chat-v2 배포 뒤에는 같은 질문에 `requestAdvice`·`horizonPicks`·`compareAdvice` 가 호출되는지 본다.

**컬럼(`db/advisor-schema.sql` §`tb_advisor_chat`)**

- `question`, `answer`(mrkdwn 변환 전 모델 출력), `status`, `prompt_version`(chat-v1/chat-v2)
- `tool_calls`(INTEGER 호출 횟수), `tool_calls_json`(JSONB 도구 이름 배열, 순서대로)
- `created_at`

```sql
-- M0-6a "도구·툴·기능이 없다" 류 답변 원문
SELECT chat_id, created_at, prompt_version, status, tool_calls, tool_calls_json,
       left(question, 80) AS q, left(answer, 160) AS a
FROM tb_advisor_chat
WHERE answer ~ '(도구|툴|tool|기능).{0,12}(없|지원하지|제공하지)'
   OR answer ~ '(생성|추천|판단).{0,12}(할 수 없|수 없습니다)'
ORDER BY created_at DESC;
```

```sql
-- M0-6b 프롬프트 버전별: 전체 질문, "없다" 답변 비율, 도구 0회 답변 비율, 생성 의도 질문에서 requestAdvice 호출 비율
SELECT prompt_version,
       COUNT(*)                                                                                        AS chats,
       COUNT(*) FILTER (WHERE answer ~ '(도구|툴|tool|기능).{0,12}(없|지원하지|제공하지)')              AS no_tool_answers,
       COUNT(*) FILTER (WHERE tool_calls = 0)                                                          AS zero_tool_calls,
       COUNT(*) FILTER (WHERE question ~ '(추천|판단|종목).{0,12}(뽑|골라|새로|다시|해줘|만들)')         AS gen_intent,
       COUNT(*) FILTER (WHERE question ~ '(추천|판단|종목).{0,12}(뽑|골라|새로|다시|해줘|만들)'
                          AND tool_calls_json ? 'requestAdvice')                                       AS gen_with_request,
       COUNT(*) FILTER (WHERE tool_calls_json ?| ARRAY['horizonPicks', 'compareAdvice'])               AS horizon_or_compare
FROM tb_advisor_chat
WHERE status IN ('SUCCESS', 'FAILED')
GROUP BY prompt_version
ORDER BY prompt_version;
```

```sql
-- M0-6c 도구 분포 (버전별)
SELECT prompt_version, t AS tool, COUNT(*) AS calls
FROM tb_advisor_chat, jsonb_array_elements_text(tool_calls_json) AS t
GROUP BY prompt_version, t
ORDER BY prompt_version, calls DESC;
```

- 정규식은 한국어 표현이 다양해서 **재현율 우선**으로 느슨하게 잡았다. M0-6a 원문을 사람이 읽어 오탐을 걸러낸다.
- `?`·`?|` 는 jsonb 최상위 배열 원소(문자열) 존재 검사다.

**해석**

- chat-v1 에서 `no_tool_answers` 가 생성 의도 질문에 몰려 있으면 M2 가설(규칙 1 + 생성 도구 부재)이 확인된다.
- chat-v2 에서 `gen_with_request / gen_intent` 가 낮으면 도구 설명문이나 시스템 프롬프트 도구 선택 절을 다시 봐야 한다.
- 버전을 올리려면 `PromptResources.CHAT_VERSION` 을 올리고 운영 문서 §11.5 규약을 따른다.

**다음 결정.** chat-v2 는 이미 배포 대상이다. 이 측정은 **배포 전후 비교**다. chat-v2 뒤 2주 동안 생성 의도 질문 5건 이상이 쌓이면 다시 돌린다.

## M0-7. 생존 편향 점검: 현재 is_active 필터로 빠진 과거 종목

**목적.** 가설 4를 확인한다. `vw_stock_universe_daily` 는 `tb_stock_master` **현재 값**(`is_active = TRUE`, `is_suspended`, `is_administrative`, `is_liquidating`)으로 과거 날짜까지 거른다. 그래서 과거에 거래되다 상장폐지된 종목이 IC 백필·스크리닝 유니버스에서 통째로 빠진다. 빠진 규모를 잰다.

```sql
-- M0-7a KOSPI 주권: 가격 이력은 있는데 현재 마스터가 비활성인 종목 수·행 수 (연도별)
SELECT EXTRACT(YEAR FROM p.trade_date)::int                                            AS yr,
       COUNT(DISTINCT p.ticker)                                                        AS tickers_all,
       COUNT(DISTINCT p.ticker) FILTER (WHERE NOT m.is_active)                         AS tickers_inactive_now,
       COUNT(*) FILTER (WHERE NOT m.is_active)                                         AS rows_inactive_now,
       COUNT(DISTINCT p.ticker) FILTER (WHERE m.is_active AND (m.is_suspended OR m.is_administrative OR m.is_liquidating))
                                                                                       AS tickers_flagged_now,
       ROUND(100.0 * COUNT(*) FILTER (WHERE NOT m.is_active) / COUNT(*), 2)            AS pct_rows_dropped_inactive
FROM tb_stock_daily_price p
         JOIN tb_stock_master m ON m.ticker = p.ticker
WHERE p.trade_date >= DATE '2020-01-01' AND m.market_type = 'KOSPI' AND m.security_group = 'ST'
GROUP BY 1
ORDER BY 1;
```

```sql
-- M0-7b 가격은 있는데 마스터에 아예 없는 종목 (유니버스 뷰가 INNER JOIN 이라 전부 빠진다)
SELECT COUNT(DISTINCT p.ticker) AS tickers, COUNT(*) AS rows, MIN(p.trade_date) AS first_d, MAX(p.trade_date) AS last_d
FROM tb_stock_daily_price p
         LEFT JOIN tb_stock_master m ON m.ticker = p.ticker
WHERE p.trade_date >= DATE '2020-01-01' AND m.ticker IS NULL;
```

```sql
-- M0-7c PIT 대조: 그날 이력상 활성이었는데 지금은 비활성 (이력 수집 시작일 이후 구간만 의미가 있다)
SELECT COUNT(DISTINCT h.ticker) AS tickers, MIN(h.valid_from) AS history_from
FROM tb_stock_master_history h
         JOIN tb_stock_master m ON m.ticker = h.ticker
WHERE h.is_active AND NOT m.is_active AND h.market_type = 'KOSPI' AND h.security_group = 'ST';
```

**해석**

- **하한값이다.** 상폐 종목의 가격 이력은 애초에 수집되지 않았을 수 있다. 옛 pykrx 수집기는 2026-09-06 에 삭제됐고, 상폐 이력은 이관되지 않았다. 가격 행이 없는 상폐 종목은 이 쿼리에 나타나지 않는다.
- `pct_rows_dropped_inactive` 가 연 1% 미만이면 IC 수준에 주는 영향은 작다. 단 상폐 직전 구간은 대개 큰 음의 수익이라 **저변동·퀄리티·역모멘텀 계열 IC 를 과소**, 모멘텀 IC 를 과대 추정하는 쪽으로 작용한다.
- `tickers_flagged_now` 는 지금 정지·관리·정리매매라 과거 전 구간이 빠진 종목이다. 이것도 편향이다.

**다음 결정**

- 규모가 연 1% 이상이면 유니버스 뷰를 PIT(`tb_stock_master_history` 의 그날 행)로 바꾸는 작업을 백로그에 올린다.
  - 이력 시작 전 구간은 여전히 현재 마스터라, 효과는 이력 시작 이후에만 난다.
  - 뷰 교체는 IC 전면 재계산(`IC_BACKFILL`)과 가중치 세트 변경을 부르므로 별도 작업이다.
- 규모와 무관하게, `IcBackfillJob` 주석대로 과거 IC 는 **부호·상대 순위** 용도로만 쓴다는 원칙을 유지한다.

## 결과 기록 양식

측정할 때마다 아래를 이 문서 끝에 덧붙인다(판정 규칙은 고치지 않는다).

```
### YYYY-MM-DD 실행
- 기간: d_from ~ d_to, DAILY LIVE days = N
- M0-1: LIVE value_add = x ± se_overlap (t=), LIVE − QUANT_TOPN = … (t=)
- M0-2: 라벨 분포 …, 특이 칸 …
- M0-3: 기준 3개를 모두 만족한 시그널 = [...] → M6(b) 착수 / 보류
- M0-4: K200_PIT t_diff |t|≥2 시그널 = [...]
- M0-5: pick_ex_gap − rest_ex_gap = … (t=), 분위 단조성 …
- M0-6: chat-v1 no_tool_answers = …/…, chat-v2 gen_with_request = …/…
- M0-7: 연도별 pct_rows_dropped_inactive = …, 마스터 부재 종목 = …
```
