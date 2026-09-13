package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.CallSubject;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.InvalidationType;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStatus;
import kr.hvy.blog.modules.advisor.domain.code.TrendHorizon;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CallScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.domain.model.TrendOutlook;
import kr.hvy.blog.modules.advisor.repository.jdbc.ScoreWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 사후 채점. 규약(2026-09-13 설계 검토):
 * <ul>
 *   <li>종목: 진입 = 기준일 다음 영업일 수정 시가, 청산 = h번째 영업일 수정 종가(뷰 재조회), 벤치마크 = 소속 시장 지수 같은 규약(시가→종가), β=1</li>
 *   <li>배당락(DIVIDEND, 계수 없음)은 현금배당/진입일 원주가를 가산. 비용(왕복 bp)은 보고 전용 컬럼</li>
 *   <li>청산일 행이 없으면 구간 마지막 거래일 종가로 청산: 상폐면 DELISTED, 아니면 SUSPENDED — 학습에 포함(빼면 낙관 편향)</li>
 *   <li>국면: close-to-close, 밴드 = band-sigma × σ_1d × √h(h=5 면 σ_5d), NEUTRAL 은 밴드 안이 적중, Brier 는 부호 기준</li>
 *   <li>섹터: 업종 지수(tb_stock_index_daily 에 섹터 코드가 있으면) 아니면 MV 동일가중 등락 합, 시장 지수 대비 초과 > 0 이 적중</li>
 *   <li>추세 전망(advice-v2, h=20 패스만): TREND 는 지속 버킷 일치, TREND_INV 는 무효화 신호와 실제 전환의 일치 — {@link #trendScores}</li>
 *   <li>잠정(PROVISIONAL) → 확정(CONFIRMED) 은 같은 SQL 을 다시 돌려 덮어쓴다 (유상증자 계수 지연 반영)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdviceScoringService {

  private static final String CANDIDATE_SQL = """
      WITH cal AS (SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar),
      base AS (SELECT rn FROM cal WHERE trade_date = :baseDate),
      win AS (
          SELECT c1.trade_date AS entry_date, ch.trade_date AS exit_date
          FROM base b JOIN cal c1 ON c1.rn = b.rn + 1 JOIN cal ch ON ch.rn = b.rn + :h
      )
      SELECT c.ticker, c.bench_index_code, w.entry_date, w.exit_date,
             e.adj_open AS entry_price, x.adj_close AS exit_price,
             lastp.adj_close AS last_close, lastp.trade_date AS last_date,
             ie.open_price AS bench_entry, ix.close_price AS bench_exit,
             raw_e.close_price AS raw_entry_close,
             COALESCE(div.cash, 0) AS dividend_cash,
             m.is_active, m.delisting_date
      FROM tb_advisor_candidate c
               CROSS JOIN win w
               JOIN tb_stock_master m ON m.ticker = c.ticker
               LEFT JOIN vw_stock_daily_price_adj e ON e.ticker = c.ticker AND e.trade_date = w.entry_date
               LEFT JOIN vw_stock_daily_price_adj x ON x.ticker = c.ticker AND x.trade_date = w.exit_date
               LEFT JOIN LATERAL (SELECT p.adj_close, p.trade_date FROM vw_stock_daily_price_adj p
                                  WHERE p.ticker = c.ticker AND p.trade_date >= w.entry_date AND p.trade_date <= w.exit_date
                                  ORDER BY p.trade_date DESC LIMIT 1) lastp ON TRUE
               LEFT JOIN tb_stock_index_daily ie ON ie.index_code = c.bench_index_code AND ie.trade_date = w.entry_date
               LEFT JOIN tb_stock_index_daily ix ON ix.index_code = c.bench_index_code AND ix.trade_date = w.exit_date
               LEFT JOIN tb_stock_daily_price raw_e ON raw_e.ticker = c.ticker AND raw_e.trade_date = w.entry_date
               LEFT JOIN LATERAL (SELECT SUM(ca.cash_amount) AS cash FROM tb_stock_corporate_action ca
                                  WHERE ca.ticker = c.ticker AND ca.action_type = 'DIVIDEND'
                                    AND ca.effective_date > w.entry_date AND ca.effective_date <= w.exit_date) div ON TRUE
      WHERE c.advice_id = :adviceId
      ORDER BY c.ticker
      """;

  // σ 는 호라이즌에 맞춰 σ_1d × √h — 2026-09-13 이전엔 √5 고정이라 진단 h=1 은 거의 NEUTRAL, h=20 은 거의 UP/DOWN 으로 찍혔다
  private static final String INDEX_SQL = """
      WITH cal AS (SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar),
      base AS (SELECT rn FROM cal WHERE trade_date = :baseDate),
      win AS (SELECT ch.trade_date AS exit_date FROM base b JOIN cal ch ON ch.rn = b.rn + :h)
      SELECT i0.index_code, i0.close_price AS base_close, ix.close_price AS exit_close,
             (SELECT STDDEV_SAMP(ret_1d) * SQRT(:h) FROM (SELECT ret_1d FROM mv_stock_index_metric im
                 WHERE im.index_code = i0.index_code AND im.trade_date <= :baseDate ORDER BY trade_date DESC LIMIT :sigmaN) s) AS sigma_h
      FROM tb_stock_index_daily i0
               CROSS JOIN win w
               LEFT JOIN tb_stock_index_daily ix ON ix.index_code = i0.index_code AND ix.trade_date = w.exit_date
      WHERE i0.trade_date = :baseDate AND i0.index_code IN (:codes)
      """;

  /**
   * 추세 전망 채점 창: 기준일 다음 영업일부터 h번째 영업일까지의 확정 라벨(TrendSql, 판단 때와 같은 정의)과 MA 이벤트 최초 발생 오프셋.
   * 지수 1개씩 호출한다(:codes 에 하나, :baseLabel 은 판단 시점에 동결된 라벨).
   */
  private static final String TREND_SQL = """
      WITH cal AS (SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar),
      base AS (SELECT rn FROM cal WHERE trade_date = :baseDate),
      win AS (SELECT c.trade_date, c.rn - b.rn AS off FROM base b JOIN cal c ON c.rn > b.rn AND c.rn <= b.rn + :h),
      """ + TrendSql.labelCtes() + """

      SELECT (SELECT l0.close_value FROM lbl l0 WHERE l0.trade_date = :baseDate)      AS base_close,
             COUNT(*)                                                                  AS n_days,
             MAX(CASE WHEN w.off = :h THEN l.close_value END)                           AS exit_close,
             MIN(CASE WHEN l.trend_code <> :baseLabel THEN w.off END)                   AS flip_off,
             MIN(CASE WHEN l.trend_code <> :baseLabel THEN w.trade_date END)            AS flip_date,
             MIN(CASE WHEN l.close_value < l.ma_20 THEN w.off END)                      AS below_ma20_off,
             MIN(CASE WHEN l.close_value < l.ma_20 THEN w.trade_date END)               AS below_ma20_date,
             MIN(CASE WHEN l.close_value < l.ma_60 THEN w.off END)                      AS below_ma60_off,
             MIN(CASE WHEN l.close_value < l.ma_60 THEN w.trade_date END)               AS below_ma60_date,
             MIN(CASE WHEN l.close_value > l.ma_20 THEN w.off END)                      AS above_ma20_off,
             MIN(CASE WHEN l.close_value > l.ma_20 THEN w.trade_date END)               AS above_ma20_date,
             MIN(CASE WHEN l.close_value > l.ma_60 THEN w.off END)                      AS above_ma60_off,
             MIN(CASE WHEN l.close_value > l.ma_60 THEN w.trade_date END)               AS above_ma60_date
      FROM win w JOIN lbl l ON l.trade_date = w.trade_date
      """;

  private static final String SECTOR_SQL = """
      WITH cal AS (SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar),
      base AS (SELECT rn FROM cal WHERE trade_date = :baseDate),
      win AS (SELECT ch.trade_date AS exit_date FROM base b JOIN cal ch ON ch.rn = b.rn + :h),
      mkt AS (SELECT m0.close_price AS base_close, mx.close_price AS exit_close
              FROM tb_stock_index_daily m0 CROSS JOIN win w
                       LEFT JOIN tb_stock_index_daily mx ON mx.index_code = '0001' AND mx.trade_date = w.exit_date
              WHERE m0.index_code = '0001' AND m0.trade_date = :baseDate)
      SELECT s.code,
             idx0.close_price AS idx_base, idxx.close_price AS idx_exit,
             (SELECT SUM(d.avg_change_rate) / 100.0 FROM mv_stock_sector_daily d, win w
              WHERE d.sector_code = s.code AND d.trade_date > :baseDate AND d.trade_date <= w.exit_date) AS mv_ret,
             mkt.base_close AS mkt_base, mkt.exit_close AS mkt_exit
      FROM (SELECT unnest(ARRAY[:sectors]::text[]) AS code) s
               CROSS JOIN win w
               CROSS JOIN mkt
               LEFT JOIN tb_stock_index_daily idx0 ON idx0.index_code = s.code AND idx0.trade_date = :baseDate
               LEFT JOIN tb_stock_index_daily idxx ON idxx.index_code = s.code AND idxx.trade_date = w.exit_date
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final ScoreWriter scoreWriter;
  private final AdvisorProperties properties;
  private final kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter morningChecks;

  /** 채점 결과 요약 */
  public record Outcome(long adviceId, int horizonDays, int candidates, int scored, int missing, int calls) {
  }

  /**
   * 판단 1건을 지정 호라이즌으로 채점해 저장한다. 청산일이 아직 캘린더에 없으면 empty (미도래).
   */
  public Optional<Outcome> score(AdviceHeader advice, int horizonDays, ScoreStage stage) {
    List<CandidateScoreRow> rows = candidateScores(advice, horizonDays, stage);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    scoreWriter.upsertCandidateScores(rows);
    List<CallScoreRow> calls = new ArrayList<>(indexScores(advice, horizonDays, stage));
    calls.addAll(sectorScores(advice, horizonDays, stage));
    calls.addAll(trendScores(advice, horizonDays, stage));
    calls.addAll(morningScores(advice, horizonDays, stage));
    if (!calls.isEmpty()) {
      scoreWriter.upsertCallScores(calls);
    }
    int missing = (int) rows.stream().filter(r -> r.status() == ScoreStatus.MISSING).count();
    log.info("채점: advice={} base={} h={} stage={} candidates={} missing={} calls={}", advice.adviceId(), advice.baseDate(), horizonDays, stage,
        rows.size(), missing, calls.size());
    return Optional.of(new Outcome(advice.adviceId(), horizonDays, rows.size(), rows.size() - missing, missing, calls.size()));
  }

  /**
   * 후보 전부의 채점 행. 캘린더에 청산일이 없으면(미도래) 빈 목록.
   */
  List<CandidateScoreRow> candidateScores(AdviceHeader advice, int h, ScoreStage stage) {
    double cost = properties.getScoring().getCostBps() / 10_000.0;
    return jdbc.query(CANDIDATE_SQL, Map.of("baseDate", advice.baseDate(), "h", h, "adviceId", advice.adviceId()), (rs, i) -> {
      LocalDate entryDate = rs.getObject("entry_date", LocalDate.class);
      LocalDate exitDate = rs.getObject("exit_date", LocalDate.class);
      Double entry = nullable(rs.getObject("entry_price"));
      Double exit = nullable(rs.getObject("exit_price"));
      Double lastClose = nullable(rs.getObject("last_close"));
      LocalDate lastDate = rs.getObject("last_date", LocalDate.class);
      Double benchEntry = nullable(rs.getObject("bench_entry"));
      Double benchExit = nullable(rs.getObject("bench_exit"));
      Double rawEntry = nullable(rs.getObject("raw_entry_close"));
      double dividendCash = rs.getDouble("dividend_cash");
      boolean active = rs.getBoolean("is_active");
      LocalDate delisting = rs.getObject("delisting_date", LocalDate.class);

      ScoreStatus status = ScoreStatus.SCORED;
      LocalDate effectiveExit = exitDate;
      if (entry == null || entry <= 0) {
        status = ScoreStatus.MISSING;
      } else if (exit == null) {
        if (lastClose != null) {
          exit = lastClose;
          effectiveExit = lastDate;
          status = (!active && delisting != null && !delisting.isAfter(exitDate)) ? ScoreStatus.DELISTED : ScoreStatus.SUSPENDED;
        } else {
          status = ScoreStatus.MISSING;
        }
      }
      Double dividendRet = rawEntry != null && rawEntry > 0 ? dividendCash / rawEntry : 0.0;
      Double ret = status == ScoreStatus.MISSING ? null : exit / entry - 1 + dividendRet;
      Double bench = benchEntry != null && benchEntry > 0 && benchExit != null ? benchExit / benchEntry - 1 : null;
      Double excess = ret != null && bench != null ? ret - bench : null;
      return CandidateScoreRow.builder()
          .adviceId(advice.adviceId()).ticker(rs.getString("ticker")).horizonDays(h).stage(stage).status(status)
          .entryDate(entryDate).entryPrice(entry).exitDate(effectiveExit).exitPrice(status == ScoreStatus.MISSING ? null : exit)
          .ret(ret).dividendRet(dividendRet).benchRet(bench).excessRet(excess).costAdjExcess(excess == null ? null : excess - cost)
          .build();
    });
  }

  /**
   * 국면(지수 방향) 채점: 0001·1001. 예측이 없으면(섀도) 빈 목록.
   */
  List<CallScoreRow> indexScores(AdviceHeader advice, int h, ScoreStage stage) {
    if (advice.kospiDir() == null && advice.kosdaqDir() == null) {
      return List.of();
    }
    double bandSigma = properties.getScoring().getBandSigma();
    return jdbc.query(INDEX_SQL, Map.of("baseDate", advice.baseDate(), "h", h, "codes", List.of("0001", "1001"),
        "sigmaN", properties.getScoring().getSigmaLookbackDays()), (rs, i) -> {
      String code = rs.getString("index_code");
      DirectionCall predicted = "0001".equals(code) ? advice.kospiDir() : advice.kosdaqDir();
      if (predicted == null) {
        return null;
      }
      Double baseClose = nullable(rs.getObject("base_close"));
      Double exitClose = nullable(rs.getObject("exit_close"));
      Double sigma = nullable(rs.getObject("sigma_h"));
      Double band = sigma == null ? null : bandSigma * sigma;
      if (baseClose == null || exitClose == null || baseClose <= 0) {
        return CallScoreRow.builder().adviceId(advice.adviceId()).subjectType(CallSubject.INDEX).subjectCode(code).horizonDays(h).stage(stage)
            .status(ScoreStatus.MISSING).predicted(predicted.getCode()).pUp(advice.pUp()).baseValue(baseClose).band(band).build();
      }
      double ret = exitClose / baseClose - 1;
      double effectiveBand = band == null ? 0.005 : band;
      // 밴드 안(경계 포함)은 NEUTRAL — 변동성 0 인 합성 데이터에서 0 수익률이 DOWN 으로 찍히지 않게 경계를 포함한다
      String actualDir = Math.abs(ret) <= effectiveBand ? DirectionCall.NEUTRAL.getCode() : (ret > 0 ? DirectionCall.UP.getCode() : DirectionCall.DOWN.getCode());
      boolean hit = predicted.getCode().equals(actualDir);
      double pUp = advice.pUp() == null ? 0.5 : advice.pUp();
      double probUp = switch (predicted) {
        case UP -> pUp;
        case DOWN -> 1 - pUp;
        case NEUTRAL -> 0.5;
      };
      double brier = Math.pow(probUp - (ret > 0 ? 1 : 0), 2);
      return CallScoreRow.builder().adviceId(advice.adviceId()).subjectType(CallSubject.INDEX).subjectCode(code).horizonDays(h).stage(stage)
          .status(ScoreStatus.SCORED).predicted(predicted.getCode()).pUp(advice.pUp()).baseValue(baseClose).exitValue(exitClose)
          .actualRet(ret).band(effectiveBand).actualDir(actualDir).hit(hit).brier(brier).build();
    }).stream().filter(r -> r != null).toList();
  }

  /**
   * 주도 섹터 채점: 업종 지수가 있으면 종가, 없으면 MV 동일가중 등락 합. 시장(0001) 대비 초과 > 0 이 적중.
   */
  List<CallScoreRow> sectorScores(AdviceHeader advice, int h, ScoreStage stage) {
    if (advice.leadingSectors() == null || advice.leadingSectors().isEmpty()) {
      return List.of();
    }
    List<String> codes = advice.leadingSectors().stream().map(SectorCall::code).filter(c -> c != null).toList();
    if (codes.isEmpty()) {
      return List.of();
    }
    return jdbc.query(SECTOR_SQL, Map.of("baseDate", advice.baseDate(), "h", h, "sectors", codes), (rs, i) -> {
      String code = rs.getString("code");
      Double idxBase = nullable(rs.getObject("idx_base"));
      Double idxExit = nullable(rs.getObject("idx_exit"));
      Double mvRet = nullable(rs.getObject("mv_ret"));
      Double mktBase = nullable(rs.getObject("mkt_base"));
      Double mktExit = nullable(rs.getObject("mkt_exit"));
      Double actual = idxBase != null && idxExit != null && idxBase > 0 ? idxExit / idxBase - 1 : mvRet;
      Double bench = mktBase != null && mktExit != null && mktBase > 0 ? mktExit / mktBase - 1 : null;
      boolean scored = actual != null && bench != null;
      return CallScoreRow.builder().adviceId(advice.adviceId()).subjectType(CallSubject.SECTOR).subjectCode(code).horizonDays(h).stage(stage)
          .status(scored ? ScoreStatus.SCORED : ScoreStatus.MISSING).predicted("LEAD").baseValue(idxBase).exitValue(idxExit)
          .actualRet(actual).benchRet(bench).hit(scored ? actual - bench > 0 : null).build();
    });
  }

  /** 추세 채점 창 조회 결과 (지수 1개) */
  record TrendWindow(int nDays, Double baseClose, Double exitClose, Integer flipOff, LocalDate flipDate, Map<InvalidationType, Event> events) {
  }

  record Event(Integer offset, LocalDate date) {
  }

  /**
   * 추세 지속 전망 채점 (h = advisor.trend.score-horizon-days 패스에서만, 그 밖의 h 는 빈 목록).
   * <ul>
   *   <li>TREND: predicted = persist 버킷, actual_dir = 실현 버킷(확정 라벨이 동결 라벨과 처음 달라진 오프셋: 1~horizon → WITHIN_5D, ~h → ABOUT_20D,
   *       없음 → BEYOND_20D), hit = 일치, brier = (confidence − 1[hit])² — INDEX 의 부호 기준 Brier 와 섞지 않는다</li>
   *   <li>TREND_INV: predicted = 무효화 타입, actual_dir = FIRED|QUIET, hit = 전환·발동이 둘 다 없거나 둘 다 있고 허용 거리 안(조기 신호로 작동),
   *       NONE 은 MISSING(채점 제외)</li>
   * </ul>
   * 정답 라벨은 판단 때와 같은 TrendSql 로 만들어지므로 LLM 선택과 무관하게 결정론으로 정해진다.
   */
  List<CallScoreRow> trendScores(AdviceHeader advice, int h, ScoreStage stage) {
    AdvisorProperties.Trend cfg = properties.getTrend();
    if (h != cfg.getScoreHorizonDays() || advice.outlooks() == null || advice.outlooks().isEmpty()) {
      return List.of();
    }
    List<CallScoreRow> rows = new ArrayList<>();
    for (TrendOutlook o : advice.outlooks()) {
      MarketTrendCode baseLabel = advice.trendOf(o.indexCode());
      if (baseLabel == null || o.persist() == null) {
        continue;
      }
      Map<String, Object> p = TrendSql.params(cfg, List.of(o.indexCode()), advice.baseDate().plusDays(h * 3L + 30));
      p.put("baseDate", advice.baseDate());
      p.put("h", h);
      p.put("baseLabel", baseLabel.getCode());
      TrendWindow w = jdbc.query(TREND_SQL, p, (rs, i) -> {
        Map<InvalidationType, Event> events = new java.util.EnumMap<>(InvalidationType.class);
        events.put(InvalidationType.BELOW_MA20, new Event(intOrNull(rs.getObject("below_ma20_off")), rs.getObject("below_ma20_date", LocalDate.class)));
        events.put(InvalidationType.BELOW_MA60, new Event(intOrNull(rs.getObject("below_ma60_off")), rs.getObject("below_ma60_date", LocalDate.class)));
        events.put(InvalidationType.ABOVE_MA20, new Event(intOrNull(rs.getObject("above_ma20_off")), rs.getObject("above_ma20_date", LocalDate.class)));
        events.put(InvalidationType.ABOVE_MA60, new Event(intOrNull(rs.getObject("above_ma60_off")), rs.getObject("above_ma60_date", LocalDate.class)));
        return new TrendWindow(rs.getInt("n_days"), nullable(rs.getObject("base_close")), nullable(rs.getObject("exit_close")),
            intOrNull(rs.getObject("flip_off")), rs.getObject("flip_date", LocalDate.class), events);
      }).stream().findFirst().orElse(null);
      boolean complete = w != null && w.nDays() >= h && w.baseClose() != null && w.exitClose() != null && w.baseClose() > 0;
      double confidence = o.confidence();

      // TREND
      CallScoreRow.CallScoreRowBuilder trend = CallScoreRow.builder().adviceId(advice.adviceId()).subjectType(CallSubject.TREND)
          .subjectCode(o.indexCode()).horizonDays(h).stage(stage).predicted(o.persist().getCode()).pUp(confidence);
      if (!complete) {
        rows.add(trend.status(ScoreStatus.MISSING).build());
      } else {
        TrendHorizon realized = TrendHorizon.realized(w.flipOff(), properties.getHorizonDays(), h);
        boolean hit = realized == o.persist();
        rows.add(trend.status(ScoreStatus.SCORED).baseValue(w.baseClose()).exitValue(w.exitClose()).actualRet(w.exitClose() / w.baseClose() - 1)
            .actualDir(realized.getCode()).hit(hit).brier(Math.pow(confidence - (hit ? 1 : 0), 2)).eventDate(w.flipDate()).build());
      }

      // TREND_INV
      InvalidationType inv = o.invalidation() == null ? InvalidationType.NONE : o.invalidation();
      CallScoreRow.CallScoreRowBuilder invRow = CallScoreRow.builder().adviceId(advice.adviceId()).subjectType(CallSubject.TREND_INV)
          .subjectCode(o.indexCode()).horizonDays(h).stage(stage).predicted(inv.getCode()).pUp(confidence);
      if (!complete || inv == InvalidationType.NONE) {
        rows.add(invRow.status(ScoreStatus.MISSING).build());
      } else {
        Event e = w.events().get(inv);
        Integer invOff = e == null ? null : e.offset();
        boolean hit = (w.flipOff() == null && invOff == null)
            || (w.flipOff() != null && invOff != null && Math.abs(w.flipOff() - invOff) <= cfg.getInvalidationToleranceDays());
        rows.add(invRow.status(ScoreStatus.SCORED).baseValue(w.baseClose()).exitValue(w.exitClose()).actualRet(w.exitClose() / w.baseClose() - 1)
            .actualDir(invOff == null ? "QUIET" : "FIRED").hit(hit).eventDate(e == null ? null : e.date()).build());
      }
    }
    return rows;
  }

  /** D+1 시가 갭: 기준일 종가 → 다음 영업일 시가 */
  private static final String GAP_SQL = """
      WITH cal AS (SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar),
      base AS (SELECT rn FROM cal WHERE trade_date = :baseDate),
      nxt AS (SELECT c1.trade_date FROM base b JOIN cal c1 ON c1.rn = b.rn + 1)
      SELECT i0.index_code, i0.close_price AS base_close, i1.open_price AS next_open
      FROM tb_stock_index_daily i0
               CROSS JOIN nxt
               LEFT JOIN tb_stock_index_daily i1 ON i1.index_code = i0.index_code AND i1.trade_date = nxt.trade_date
      WHERE i0.trade_date = :baseDate AND i0.index_code IN (:codes)
      """;

  /**
   * 아침 점검 채점 (h=1 패스만): 예상 갭(β × 밤사이 미국 수익률)의 부호·크기 판정을 D+1 시가 갭과 대조한다.
   * HOLD 는 |실제 갭| < 임계가 적중, REINFORCE·CAUTION 은 부호 일치가 적중. 예상 갭이 없던 지수(미국 휴장·β 결손)는 MISSING.
   * 점검 행이 없는 판단(섀도·점검 전)은 빈 목록.
   */
  List<CallScoreRow> morningScores(AdviceHeader advice, int h, ScoreStage stage) {
    if (h != 1 || advice.adviceId() == null) {
      return List.of();
    }
    Optional<kr.hvy.blog.modules.advisor.domain.model.MorningCheckRow> check = morningChecks.findByAdvice(advice.adviceId());
    if (check.isEmpty() || !(check.get().detailJson().get("index") instanceof Map<?, ?> indexDetail)) {
      return List.of();
    }
    Map<String, double[]> gaps = new java.util.HashMap<>();
    jdbc.query(GAP_SQL, Map.of("baseDate", advice.baseDate(), "codes", List.of("0001", "1001")), rs -> {
      Double base = nullable(rs.getObject("base_close"));
      Double open = nullable(rs.getObject("next_open"));
      if (base != null && open != null && base > 0) {
        gaps.put(rs.getString("index_code"), new double[]{base, open});
      }
    });
    List<CallScoreRow> rows = new ArrayList<>();
    for (String code : List.of("0001", "1001")) {
      if (!(indexDetail.get(code) instanceof Map<?, ?> d)) {
        continue;
      }
      Double gapEst = num(d.get("gapEst"));
      Double threshold = num(d.get("threshold"));
      String verdict = d.get("verdict") == null ? null : String.valueOf(d.get("verdict"));
      CallScoreRow.CallScoreRowBuilder row = CallScoreRow.builder().adviceId(advice.adviceId()).subjectType(CallSubject.MORNING).subjectCode(code)
          .horizonDays(h).stage(stage).predicted(verdict == null ? "HOLD" : verdict).band(threshold);
      double[] g = gaps.get(code);
      if (gapEst == null || threshold == null || g == null) {
        rows.add(row.status(ScoreStatus.MISSING).build());
        continue;
      }
      double actual = g[1] / g[0] - 1;
      String actualDir = Math.abs(actual) < threshold ? DirectionCall.NEUTRAL.getCode() : actual > 0 ? DirectionCall.UP.getCode() : DirectionCall.DOWN.getCode();
      boolean hit = "HOLD".equals(verdict) ? Math.abs(actual) < threshold : Math.signum(actual) == Math.signum(gapEst);
      rows.add(row.status(ScoreStatus.SCORED).baseValue(g[0]).exitValue(g[1]).actualRet(actual).actualDir(actualDir).hit(hit).build());
    }
    return rows;
  }

  private static Double num(Object value) {
    return value instanceof Number n ? n.doubleValue() : null;
  }

  private static Integer intOrNull(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  private static Double nullable(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }
}
