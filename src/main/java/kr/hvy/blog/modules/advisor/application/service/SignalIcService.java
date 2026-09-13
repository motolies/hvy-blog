package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.model.SignalIcRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.SignalIcWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 시그널 rank-IC 계산과 가중치 세트 산출. 학습 신호는 픽이 아니라 전 유니버스(하루 수백 종목)다.
 * <p>
 * IC_k(d) = corr(rank s_k(i,d), rank ex_h(i,d)), ex_h = 종목 수정주가 d→d+h 수익률 − 소속 시장 지수 d→d+h 수익률.
 * d+h 는 캘린더의 h번째 다음 영업일이며 LEAD 는 이 쿼리에만 있다(특징 SQL 에는 절대 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalIcService {

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalIcWriter icWriter;
  private final WeightSetRepository weightSets;
  private final AdvisorProperties properties;

  /**
   * [from, to] 기준일의 IC 를 계산해 저장하고 저장 행 수를 돌려준다. d+h 가 없는 날은 결과에서 자연히 빠진다.
   */
  public int computeAndStore(LocalDate from, LocalDate to) {
    List<SignalIcRow> rows = compute(from, to, properties.getHorizonDays());
    int stored = icWriter.upsert(rows);
    log.info("시그널 IC 계산: {}~{}, rows={}, stored={}", from, to, rows.size(), stored);
    return rows.size();
  }

  /**
   * IC 행 계산 (저장 없음).
   */
  public List<SignalIcRow> compute(LocalDate from, LocalDate to, int horizonDays) {
    List<SignalCode> signals = SignalCode.learnable();
    String sql = FeatureSql.featureCtes()
        + ", cal_all AS (\n"
        + "    SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar\n"
        + "),\n"
        + "exits AS (\n"
        + "    SELECT c0.trade_date, ch.trade_date AS exit_date\n"
        + "    FROM cal_all c0 JOIN cal_all ch ON ch.rn = c0.rn + :h\n"
        + "    WHERE c0.trade_date BETWEEN :from AND :to\n"
        + "),\n"
        + "realized AS (\n"
        + "    SELECT f.ticker, f.trade_date,\n"
        + "           (mx.adj_close / NULLIF(f.adj_close, 0) - 1) - (ix.close_value / NULLIF(i0.close_value, 0) - 1) AS ex\n"
        + "    FROM feat f\n"
        + "             JOIN exits e ON e.trade_date = f.trade_date\n"
        + "             LEFT JOIN tb_stock_daily_metric mx ON mx.ticker = f.ticker AND mx.trade_date = e.exit_date\n"
        + "             LEFT JOIN mv_stock_index_metric i0 ON i0.index_code = f.bench_index_code AND i0.trade_date = f.trade_date\n"
        + "             LEFT JOIN mv_stock_index_metric ix ON ix.index_code = f.bench_index_code AND ix.trade_date = e.exit_date\n"
        + "),\n"
        + "unpivot AS (\n"
        + "    SELECT f.trade_date, s.signal_code, s.value, r.ex\n"
        + "    FROM feat f\n"
        + "             JOIN realized r ON r.ticker = f.ticker AND r.trade_date = f.trade_date\n"
        + "             CROSS JOIN LATERAL (VALUES\n              " + FeatureSql.icValuesList(signals) + ") AS s(signal_code, value)\n"
        + "    WHERE r.ex IS NOT NULL AND s.value IS NOT NULL\n"
        + "),\n"
        + "ranked AS (\n"
        + "    SELECT trade_date, signal_code,\n"
        + "           RANK() OVER (PARTITION BY trade_date, signal_code ORDER BY value) AS rs,\n"
        + "           RANK() OVER (PARTITION BY trade_date, signal_code ORDER BY ex) AS rx\n"
        + "    FROM unpivot\n"
        + ")\n"
        + "SELECT trade_date, signal_code, corr(rs, rx) AS rank_ic, COUNT(*) AS n\n"
        + "FROM ranked GROUP BY trade_date, signal_code HAVING COUNT(*) >= 30 AND corr(rs, rx) IS NOT NULL ORDER BY trade_date, signal_code";
    return jdbc.query(sql, Map.of("from", from, "to", to, "h", horizonDays),
        (rs, i) -> new SignalIcRow(rs.getString("signal_code"), rs.getObject("trade_date", LocalDate.class), horizonDays,
            rs.getDouble("rank_ic"), rs.getInt("n")));
  }

  /**
   * 기준일 이하 창의 IC 통계로 새 가중치 세트를 산출한다. 게이트(n_eff ≥ ic.min-n-eff) 미달이면 empty.
   * 저장·활성화는 호출자가 결정한다.
   */
  public Optional<WeightSet> proposeWeightSet(LocalDate asOf, WeightSetSource source, Long runId) {
    AdvisorProperties.Ic ic = properties.getIc();
    WeightSet current = weightSets.active().orElseThrow(() -> new IllegalStateException("활성 가중치 세트가 없습니다"));
    List<SignalIcRow> window = icWriter.window(asOf, ic.getWindowDays());
    Map<String, SignalWeightMath.IcStat> stats = SignalWeightMath.aggregate(window, properties.getHorizonDays());
    double minNEff = SignalWeightMath.minLearnableNEff(stats);
    if (minNEff < ic.getMinNEff()) {
      log.info("가중치 세트 미갱신: n_eff {} < {} (asOf={})", minNEff, ic.getMinNEff(), asOf);
      return Optional.empty();
    }
    WeightSet proposed = WeightSet.builder()
        .asOf(asOf)
        .windowDays(ic.getWindowDays())
        .nEff(minNEff)
        .source(source)
        .active(false)
        .reason(source + " IC 창 " + ic.getWindowDays() + "일, n_eff " + String.format("%.1f", minNEff))
        .runId(runId)
        .weights(SignalWeightMath.apply(stats, current.weights(), ic))
        .build();
    return Optional.of(proposed);
  }

  /**
   * 청크 1개를 "단계" 로 실행하는 계약. {@code AdvisorSteps::run} 이 그대로 맞으므로 잡 쪽 규칙(단계 기록·메타 flush·취소 감지)을 서비스가 몰라도 된다.
   * 반환값은 성공 여부(격리된 실패는 false).
   */
  @FunctionalInterface
  public interface ChunkRunner {

    boolean run(String name, Runnable body);
  }

  /**
   * 증분 계산 결과. gapFrom 이 있으면 [gapFrom, from) 은 상한(ic.incremental-max-days) 때문에 계산하지 않은 공백이다.
   */
  public record IncrementalResult(LocalDate from, LocalDate to, LocalDate gapFrom, int rows, int chunks) {

    public boolean truncated() {
      return gapFrom != null;
    }

    /**
     * 잡 메타·경고에 남긴다. 공백이 있으면 IC_BACKFILL 보충 명령을 경고로 적는다(run 상태는 바꾸지 않는다).
     */
    public void record(AdvisorExecution execution) {
      execution.putMetadata("icRange", from + "~" + to);
      execution.putMetadata("icRows", rows);
      execution.putMetadata("icChunks", chunks);
      if (truncated()) {
        execution.putMetadata("icGapFrom", gapFrom.toString());
        execution.warn("IC 공백 " + gapFrom + "~" + from.minusDays(1) + " 미계산(증분 상한 " + execution.properties().getIc().getIncrementalMaxDays()
            + "일) — POST /api/advisor/admin/jobs/IC_BACKFILL?baseDate=" + gapFrom + " 로 채우세요");
      }
    }
  }

  /**
   * [from, to] 를 월 단위 청크로 나눈다: from 부터 "다음 달 같은 날 전날" 까지가 한 청크(IcBackfillJob 의 기존 규칙, 월말은 java.time 이 절단).
   * from > to 면 빈 목록.
   */
  static List<LocalDate[]> monthlyChunks(LocalDate from, LocalDate to) {
    List<LocalDate[]> chunks = new ArrayList<>();
    LocalDate cursor = from;
    while (!cursor.isAfter(to)) {
      LocalDate chunkEnd = cursor.plusMonths(1).minusDays(1);
      if (chunkEnd.isAfter(to)) {
        chunkEnd = to;
      }
      chunks.add(new LocalDate[] {cursor, chunkEnd});
      cursor = chunkEnd.plusDays(1);
    }
    return chunks;
  }

  /** 청크 단계 이름 (run 메타 steps 에 "IC:2026-08" 로 보인다) */
  static String chunkLabel(LocalDate from) {
    return "IC:" + from.getYear() + "-" + String.format("%02d", from.getMonthValue());
  }

  /**
   * [from, to] 를 월 청크로 계산·저장한다. 청크마다 저장되므로 중간에 끊겨도 앞 청크는 남고, 진행이 run 메타에 보인다.
   * 청크 실패의 격리 여부는 runner(AdvisorSteps::run 이면 격리) 가 정한다.
   *
   * @return {계산 행 수, 청크 수}
   */
  public int[] computeChunked(LocalDate from, LocalDate to, ChunkRunner runner) {
    int rows = 0;
    int chunks = 0;
    for (LocalDate[] chunk : monthlyChunks(from, to)) {
      final int[] chunkRows = {0};
      runner.run(chunkLabel(chunk[0]), () -> chunkRows[0] = computeAndStore(chunk[0], chunk[1]));
      rows += chunkRows[0];
      chunks++;
    }
    return new int[] {rows, chunks};
  }

  /**
   * IC 가 계산된 마지막 기준일 다음 날부터 계산 가능한 마지막 기준일(캘린더 끝 − h)까지 월 청크로 증분 계산한다.
   * 공백이 ic.incremental-max-days 보다 길면(IC 행이 없는 첫 실행 포함) 최근 그 일수만 계산하고 나머지는 결과의 gapFrom 으로 돌려준다 —
   * 2020 년부터 6년치를 SQL 한 번에 돌린 2026-09-13 ADVISE 결함의 재발 방지. 공백은 IC_BACKFILL?baseDate= 로 따로 채운다.
   *
   * @return 계산한 범위·행·청크·공백 (계산할 날이 없으면 empty)
   */
  public Optional<IncrementalResult> computeIncremental(ChunkRunner runner) {
    LocalDate start = icWriter.maxTradeDate().map(d -> d.plusDays(1)).orElse(LocalDate.parse(properties.getIc().getBackfillFrom()));
    Optional<LocalDate> endOpt = latestScorableDate(properties.getHorizonDays());
    if (endOpt.isEmpty() || endOpt.get().isBefore(start)) {
      return Optional.empty();
    }
    LocalDate end = endOpt.get();
    int maxDays = properties.getIc().getIncrementalMaxDays();
    LocalDate capStart = end.minusDays(maxDays);
    LocalDate gapFrom = null;
    if (start.isBefore(capStart)) {
      gapFrom = start;
      start = capStart;
      log.warn("IC 증분 공백이 상한 {}일을 넘어 {}~{} 는 계산하지 않습니다 — POST /api/advisor/admin/jobs/IC_BACKFILL?baseDate={} 로 채우세요",
          maxDays, gapFrom, capStart.minusDays(1), gapFrom);
    }
    int[] result = computeChunked(start, end, runner);
    return Optional.of(new IncrementalResult(start, end, gapFrom, result[0], result[1]));
  }

  /**
   * d+h 가 존재하는 마지막 기준일 = 캘린더 마지막 행에서 h 행 앞.
   */
  public Optional<LocalDate> latestScorableDate(int horizonDays) {
    List<LocalDate> dates = jdbc.query("SELECT trade_date FROM vw_stock_market_calendar ORDER BY trade_date DESC OFFSET :h LIMIT 1",
        Map.of("h", horizonDays), (rs, i) -> rs.getObject("trade_date", LocalDate.class));
    return dates.stream().findFirst();
  }
}
