package kr.hvy.blog.modules.advisor.application.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.PickUniverse;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.model.LongTermFactorRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 장기(H60·H180) 규칙 선택의 팩터 조회(M8). 기준일 하루치 유니버스(advisor.markets)의 장기 팩터 원값을 만들고 {@link LongTermScorer} 로 순위를 매긴다.
 * <p>
 * 룩어헤드 불변식
 * <ul>
 *   <li>가격 팩터: tb_stock_daily_metric 의 trade_date ≤ 기준일 행만(ROW_NUMBER 역순). 기준일 행이 없는 종목(정지 등)은 모멘텀 결측</li>
 *   <li>재무 팩터: tb_stock_financial LATERAL — available_from ≤ 기준일 <b>그리고</b> first_seen_at(KST 날짜) ≤ 기준일(bitemporal: 공시 추정일과 우리가 처음 본 날
 *       중 늦은 쪽 이후에만 보인다), 결산 구분 1종(advisor.long-term.financial-period-type)의 최신 결산기·최신 정정 회차 1행</li>
 *   <li>KOSPI200 구성: tb_stock_master_history 의 기준일 유효 행(PIT, 반열림 [valid_from, valid_to)). 이력 전 날짜는 NULL(모름) → KOSPI200 모드에서 후보 아님</li>
 * </ul>
 * 섹터는 FeatureSql 과 같이 현재 KRX 매핑(valid_to IS NULL)을 쓴다(기존 스크리닝과 같은 한계).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermScreeningService {

  /**
   * 기준일 장기 팩터 SQL. :look·:skip·:volWin·:volMin 은 거래일 수, :calDays 는 가격 이력 캘린더 창(거래일 창을 넉넉히 덮는 일수).
   * mom_12_1 = (c0 / c_look − 1) − (c0 / c_skip − 1) = ret_look − ret_skip (계획의 ret_250 − ret_20). 기준일 행(rn=1)이 기준일이 아니면 결측.
   */
  static final String FACTOR_SQL = """
      WITH base AS (
          SELECT u.ticker
          FROM vw_stock_universe_daily u
                   JOIN tb_stock_master ms ON ms.ticker = u.ticker
          WHERE u.trade_date = :d AND ms.market_type IN (:markets)
      ),
      hist AS (
          SELECT m.ticker, m.trade_date, m.adj_close, m.ret_1d,
                 ROW_NUMBER() OVER (PARTITION BY m.ticker ORDER BY m.trade_date DESC) AS rn
          FROM tb_stock_daily_metric m
                   JOIN base b ON b.ticker = m.ticker
          WHERE m.trade_date <= :d AND m.trade_date > CAST(:d AS date) - :calDays
      ),
      px AS (
          SELECT ticker,
                 MAX(CASE WHEN rn = 1 THEN trade_date END)          AS last_date,
                 MAX(CASE WHEN rn = 1 THEN adj_close END)           AS c0,
                 MAX(CASE WHEN rn = :skip + 1 THEN adj_close END)   AS c_skip,
                 MAX(CASE WHEN rn = :look + 1 THEN adj_close END)   AS c_look,
                 STDDEV_SAMP(CASE WHEN rn <= :volWin THEN ret_1d END) AS vol,
                 COUNT(CASE WHEN rn <= :volWin THEN ret_1d END)       AS vol_n
          FROM hist
          GROUP BY ticker
      )
      SELECT b.ticker, ms.stock_name, ms.market_type, sm.sector_code, sm.sector_name, mh.is_kospi200,
             CASE WHEN mh.is_kospi200 THEN ms.kospi200_sector END AS k200_sector,
             p.close_price AS raw_close, px.c0 AS adj_close,
             CASE WHEN px.last_date = :d AND px.c_look > 0 AND px.c_skip > 0
                  THEN (px.c0 / px.c_look - 1) - (px.c0 / px.c_skip - 1) END AS mom_12_1,
             CASE WHEN px.vol_n >= :volMin THEN px.vol END AS low_vol_60,
             fin.roe, fin.debt_ratio, fin.operating_profit_growth, fin.fiscal_period, fin.available_from
      FROM base b
               JOIN tb_stock_master ms ON ms.ticker = b.ticker
               LEFT JOIN px ON px.ticker = b.ticker
               LEFT JOIN tb_stock_daily_price p ON p.ticker = b.ticker AND p.trade_date = :d
               LEFT JOIN tb_stock_sector_map sm ON sm.ticker = b.ticker AND sm.source = 'KRX' AND sm.valid_to IS NULL
               LEFT JOIN tb_stock_master_history mh ON mh.ticker = b.ticker AND mh.valid_from <= :d
                                                  AND (mh.valid_to IS NULL OR mh.valid_to > :d)
               LEFT JOIN LATERAL (
                   SELECT f.roe, f.debt_ratio, f.operating_profit_growth, f.fiscal_period, f.available_from
                   FROM tb_stock_financial f
                   WHERE f.ticker = b.ticker AND f.period_type = :periodType
                     AND f.available_from <= :d
                     AND CAST(f.first_seen_at AT TIME ZONE 'Asia/Seoul' AS date) <= :d
                   ORDER BY f.fiscal_period DESC, f.revision_seq DESC
                   LIMIT 1) fin ON TRUE
      ORDER BY b.ticker
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final AdvisorProperties properties;

  /**
   * 기준일 규칙 순위(설정의 가중치·픽 유니버스·커버리지·후보/픽 수·섹터 상한).
   */
  public LongTermScorer.Ranking rank(LocalDate asOf) {
    AdvisorProperties.LongTerm lt = properties.getLongTerm();
    long started = System.currentTimeMillis();
    List<LongTermFactorRow> rows = factors(asOf);
    LongTermScorer.Ranking ranking = LongTermScorer.rank(rows, new LongTermScorer.Rule(lt.getWeights(), properties.getPickUniverse() == PickUniverse.ALL,
        lt.getMinCoverage(), lt.getCandidateLimit(), lt.getPickCount(), lt.getMaxPerSector()));
    log.info("장기 스크리닝: base={}, pickUniverse={}, universe={}, eligible={}, candidates={}, picks={}, {}ms", asOf, properties.getPickUniverse().getCode(),
        ranking.universeSize(), ranking.eligibleSize(), ranking.candidates().size(), ranking.pickTickers().size(), System.currentTimeMillis() - started);
    return ranking;
  }

  /**
   * 기준일 유니버스의 장기 팩터 원값 (티커 오름차순).
   */
  List<LongTermFactorRow> factors(LocalDate asOf) {
    AdvisorProperties.LongTerm lt = properties.getLongTerm();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("d", asOf);
    params.put("markets", properties.getMarkets());
    params.put("look", lt.getMomLookbackDays());
    params.put("skip", lt.getMomSkipDays());
    params.put("volWin", lt.getVolWindowDays());
    params.put("volMin", lt.getVolMinDays());
    params.put("calDays", calendarDays(Math.max(lt.getMomLookbackDays(), lt.getVolWindowDays()) + 1));
    params.put("periodType", lt.getFinancialPeriodType());
    return jdbc.query(FACTOR_SQL, params, (rs, i) -> read(rs));
  }

  /**
   * 거래일 n 개를 덮는 캘린더 일수: 주 5거래일 + 연휴 여유 30일.
   */
  static int calendarDays(int tradingDays) {
    return (int) Math.ceil(tradingDays * 7 / 5.0) + 30;
  }

  private static LongTermFactorRow read(ResultSet rs) throws SQLException {
    Map<SignalCode, Double> factors = new EnumMap<>(SignalCode.class);
    factors.put(SignalCode.MOM_12_1, d(rs, "mom_12_1"));
    factors.put(SignalCode.LOW_VOL_60, d(rs, "low_vol_60"));
    factors.put(SignalCode.QUALITY_ROE, d(rs, "roe"));
    factors.put(SignalCode.QUALITY_DEBT, d(rs, "debt_ratio"));
    factors.put(SignalCode.OP_GROWTH, d(rs, "operating_profit_growth"));
    return LongTermFactorRow.builder()
        .ticker(rs.getString("ticker"))
        .stockName(rs.getString("stock_name"))
        .marketType(rs.getString("market_type"))
        .sectorCode(rs.getString("sector_code"))
        .sectorName(rs.getString("sector_name"))
        .kospi200((Boolean) rs.getObject("is_kospi200"))
        .k200Sector(rs.getString("k200_sector"))
        .rawClose(rs.getBigDecimal("raw_close"))
        .adjClose(d(rs, "adj_close"))
        .factors(factors)
        .fiscalPeriod(rs.getString("fiscal_period"))
        .financialAsOf(rs.getObject("available_from", LocalDate.class))
        .build();
  }

  private static Double d(ResultSet rs, String column) throws SQLException {
    double v = rs.getDouble(column);
    return rs.wasNull() ? null : v;
  }
}
