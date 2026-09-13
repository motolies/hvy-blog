package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.CallSubject;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStatus;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CallScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
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
 *   <li>국면: close-to-close, 밴드 = band-sigma × σ_5d, NEUTRAL 은 밴드 안이 적중, Brier 는 부호 기준</li>
 *   <li>섹터: 업종 지수(tb_stock_index_daily 에 섹터 코드가 있으면) 아니면 MV 동일가중 등락 합, 시장 지수 대비 초과 > 0 이 적중</li>
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

  private static final String INDEX_SQL = """
      WITH cal AS (SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar),
      base AS (SELECT rn FROM cal WHERE trade_date = :baseDate),
      win AS (SELECT ch.trade_date AS exit_date FROM base b JOIN cal ch ON ch.rn = b.rn + :h)
      SELECT i0.index_code, i0.close_price AS base_close, ix.close_price AS exit_close,
             (SELECT STDDEV_SAMP(ret_1d) * SQRT(5) FROM (SELECT ret_1d FROM mv_stock_index_metric im
                 WHERE im.index_code = i0.index_code AND im.trade_date <= :baseDate ORDER BY trade_date DESC LIMIT :sigmaN) s) AS sigma5
      FROM tb_stock_index_daily i0
               CROSS JOIN win w
               LEFT JOIN tb_stock_index_daily ix ON ix.index_code = i0.index_code AND ix.trade_date = w.exit_date
      WHERE i0.trade_date = :baseDate AND i0.index_code IN (:codes)
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
      Double sigma = nullable(rs.getObject("sigma5"));
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

  private static Double nullable(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }
}
