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
 * 시그널 rank-IC 계산과 가중치 세트 산출. 학습 신호는 픽이 아니라 유니버스 전체(advisor.markets 시장, 하루 수백 종목)다 — 스크리닝과 같은
 * 특징 CTE·시장 필터를 쓰므로 척도가 일치한다. 시장 목록을 바꾸면 저장된 IC 행은 옛 유니버스 기준이니 IC_BACKFILL 로 덮어쓴다.
 * <p>
 * IC_k(d) = corr(rank s_k(i,d), rank ex_h(i,d)), ex_h = 종목 수정주가 d→d+h 수익률 − 소속 시장 지수 d→d+h 수익률.
 * d+h 는 캘린더의 h번째 다음 영업일이며 LEAD 는 이 쿼리에만 있다(특징 SQL 에는 절대 없음).
 * <p>
 * 멀티 호라이즌(M5, 2026-09-25): IC 는 advisor.horizons 의 모든 호라이즌마다 따로 저장하고(60·180 은 모니터링 전용), 가중치는 learn=true 호라이즌만
 * 그 호라이즌의 창(icWindow)·n_eff = 창/h 로 학습한다. d+h 영업일이 캘린더에 없는 d 는 계산되지 않는다 — h 가 길수록 최신 IC 가 그만큼 늦다(룩어헤드 금지).
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
   * DAILY 결정 호라이즌으로 {@link #computeAndStore(LocalDate, LocalDate, int)}.
   */
  public int computeAndStore(LocalDate from, LocalDate to) {
    return computeAndStore(from, to, properties.getHorizonDays());
  }

  /**
   * [from, to] 기준일의 호라이즌 h IC 를 계산해 저장하고 계산 행 수를 돌려준다. d+h 가 없는 날은 결과에서 자연히 빠진다.
   * 다른 호라이즌 행은 PK 가 달라 덮어쓰지 않는다.
   */
  public int computeAndStore(LocalDate from, LocalDate to, int horizonDays) {
    List<SignalIcRow> rows = compute(from, to, horizonDays);
    int stored = icWriter.upsert(rows);
    log.info("시그널 IC 계산: h={}, {}~{}, rows={}, stored={}", horizonDays, from, to, rows.size(), stored);
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
    return jdbc.query(sql, Map.of("from", from, "to", to, "h", horizonDays, "markets", properties.getMarkets()),
        (rs, i) -> new SignalIcRow(rs.getString("signal_code"), rs.getObject("trade_date", LocalDate.class), horizonDays,
            rs.getDouble("rank_ic"), rs.getInt("n")));
  }

  /**
   * DAILY 결정 호라이즌으로 {@link #proposeWeightSet(int, LocalDate, WeightSetSource, Long)}.
   */
  public Optional<WeightSet> proposeWeightSet(LocalDate asOf, WeightSetSource source, Long runId) {
    return proposeWeightSet(properties.getHorizonDays(), asOf, source, runId);
  }

  /**
   * 호라이즌 h 의 기준일 이하 창(advisor.horizons.h.ic-window) IC 통계로 새 가중치 세트를 산출한다. n_eff = 창 안 IC 일수 / h 이고 게이트
   * (n_eff ≥ ic.min-n-eff)는 호라이즌과 무관하게 같다 — 미달이면 empty. 저장·활성화는 호출자가 결정한다.
   * <p>
   * 배수의 출발점(base_weight·enabled)은 그 호라이즌의 활성 세트, 없으면(h=20 의 첫 학습) DAILY 활성 세트다 — 사전 가중치는 호라이즌과 무관한 설계값이다.
   * learn=false 호라이즌(60·180)은 IllegalArgumentException: 모니터링 IC 로 가중치를 만들지 않는다.
   */
  public Optional<WeightSet> proposeWeightSet(int horizonDays, LocalDate asOf, WeightSetSource source, Long runId) {
    if (!properties.isLearnHorizon(horizonDays)) {
      throw new IllegalArgumentException("가중치 학습 호라이즌이 아닙니다: h=" + horizonDays + " (학습 " + properties.learnHorizons() + ")");
    }
    AdvisorProperties.Ic ic = properties.getIc();
    WeightSet current = weightSets.active(horizonDays)
        .or(() -> weightSets.active(properties.getHorizonDays()))
        .orElseThrow(() -> new IllegalStateException("활성 가중치 세트가 없습니다 (h=" + horizonDays + ", DAILY 폴백 포함)"));
    int windowDays = properties.icWindowDays(horizonDays);
    List<SignalIcRow> window = icWriter.window(horizonDays, asOf, windowDays);
    Map<String, SignalWeightMath.IcStat> stats = SignalWeightMath.aggregate(window, horizonDays);
    double minNEff = SignalWeightMath.minLearnableNEff(stats);
    if (minNEff < ic.getMinNEff()) {
      log.info("가중치 세트 미갱신: h={}, n_eff {} < {} (asOf={})", horizonDays, minNEff, ic.getMinNEff(), asOf);
      return Optional.empty();
    }
    String horizonLabel = horizonDays == properties.getHorizonDays() ? "" : " h=" + horizonDays;
    WeightSet proposed = WeightSet.builder()
        .asOf(asOf)
        .windowDays(windowDays)
        .nEff(minNEff)
        .source(source)
        .active(false)
        .reason(source + horizonLabel + " IC 창 " + windowDays + "일, n_eff " + String.format("%.1f", minNEff))
        .runId(runId)
        .weights(SignalWeightMath.apply(stats, current.weights(), ic))
        .horizonDays(horizonDays)
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
   * 증분 계산 결과 (호라이즌 1개). gapFrom 이 있으면 [gapFrom, from) 은 상한(ic.incremental-max-days) 때문에 계산하지 않은 공백이다.
   */
  public record IncrementalResult(LocalDate from, LocalDate to, LocalDate gapFrom, int rows, int chunks, int horizonDays) {

    public boolean truncated() {
      return gapFrom != null;
    }

    /**
     * 잡 메타·경고에 남긴다. DAILY 결정 호라이즌은 기존 키(icRange·icRows·icChunks·icGapFrom) 그대로, 다른 호라이즌은 "@h" 접미 키로 남긴다.
     * 공백이 있으면 IC_BACKFILL 보충 명령을 경고로 적는다(run 상태는 바꾸지 않는다).
     */
    public void record(AdvisorExecution execution) {
      boolean decision = horizonDays == execution.properties().getHorizonDays();
      String suffix = decision ? "" : "@" + horizonDays;
      execution.putMetadata("icRange" + suffix, from + "~" + to);
      execution.putMetadata("icRows" + suffix, rows);
      execution.putMetadata("icChunks" + suffix, chunks);
      if (truncated()) {
        execution.putMetadata("icGapFrom" + suffix, gapFrom.toString());
        execution.warn("IC 공백" + (decision ? "" : "(h=" + horizonDays + ")") + " " + gapFrom + "~" + from.minusDays(1) + " 미계산(증분 상한 "
            + execution.properties().getIc().getIncrementalMaxDays() + "일) — POST /api/advisor/admin/jobs/IC_BACKFILL?baseDate=" + gapFrom
            + (decision ? "" : "&horizon=" + horizonDays) + " 로 채우세요");
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

  /** 호라이즌 청크 단계 이름: DAILY 결정 호라이즌은 "IC:2026-08"(기존), 그 밖은 "IC@20:2026-08" — 같은 run 안에서 단계 이름이 겹치지 않게 */
  static String chunkLabel(LocalDate from, int horizonDays, int decisionHorizonDays) {
    return horizonDays == decisionHorizonDays ? chunkLabel(from)
        : "IC@" + horizonDays + ":" + from.getYear() + "-" + String.format("%02d", from.getMonthValue());
  }

  /**
   * DAILY 결정 호라이즌으로 {@link #computeChunked(int, LocalDate, LocalDate, ChunkRunner)}.
   */
  public int[] computeChunked(LocalDate from, LocalDate to, ChunkRunner runner) {
    return computeChunked(properties.getHorizonDays(), from, to, runner);
  }

  /**
   * 호라이즌 h 의 [from, to] 를 월 청크로 계산·저장한다. 청크마다 저장되므로 중간에 끊겨도 앞 청크는 남고, 진행이 run 메타에 보인다.
   * 청크 실패의 격리 여부는 runner(AdvisorSteps::run 이면 격리) 가 정한다.
   *
   * @return {계산 행 수, 청크 수}
   */
  public int[] computeChunked(int horizonDays, LocalDate from, LocalDate to, ChunkRunner runner) {
    int rows = 0;
    int chunks = 0;
    for (LocalDate[] chunk : monthlyChunks(from, to)) {
      final int[] chunkRows = {0};
      runner.run(chunkLabel(chunk[0], horizonDays, properties.getHorizonDays()), () -> chunkRows[0] = computeAndStore(chunk[0], chunk[1], horizonDays));
      rows += chunkRows[0];
      chunks++;
    }
    return new int[] {rows, chunks};
  }

  /**
   * IC 대상 호라이즌 전부(advisor.horizons, 오름차순 — DAILY 결정 호라이즌 5 가 먼저)를 {@link #computeIncremental(int, ChunkRunner)} 로 증분 계산한다.
   * 한 호라이즌의 실패가 나머지를 막지 않도록 호라이즌마다 격리하고, 끝난 뒤 첫 실패를 다시 던져 호출 단계(IC)가 실패로 기록되게 한다. 취소는 즉시 전파한다.
   *
   * @return 계산한 호라이즌의 결과 (계산할 날이 없던 호라이즌은 빠진다)
   */
  public List<IncrementalResult> computeIncremental(ChunkRunner runner) {
    List<IncrementalResult> results = new ArrayList<>();
    RuntimeException firstFailure = null;
    for (int h : properties.icHorizons()) {
      try {
        computeIncremental(h, runner).ifPresent(results::add);
      } catch (AdvisorCanceledException e) {
        throw e;
      } catch (RuntimeException e) {
        log.warn("IC 증분 실패(h={}, 다른 호라이즌은 계속)", h, e);
        if (firstFailure == null) {
          firstFailure = e;
        }
      }
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
    return results;
  }

  /**
   * 호라이즌 h 의 IC 가 계산된 마지막 기준일 다음 날부터 계산 가능한 마지막 기준일(캘린더 끝 − h)까지 월 청크로 증분 계산한다.
   * 공백이 ic.incremental-max-days 보다 길면(IC 행이 없는 첫 실행 포함) 최근 그 일수만 계산하고 나머지는 결과의 gapFrom 으로 돌려준다 —
   * 2020 년부터 6년치를 SQL 한 번에 돌린 2026-09-13 ADVISE 결함의 재발 방지. 공백은 IC_BACKFILL?baseDate=&horizon= 으로 따로 채운다.
   *
   * @return 계산한 범위·행·청크·공백 (계산할 날이 없으면 empty)
   */
  public Optional<IncrementalResult> computeIncremental(int horizonDays, ChunkRunner runner) {
    LocalDate start = icWriter.maxTradeDate(horizonDays).map(d -> d.plusDays(1)).orElse(LocalDate.parse(properties.getIc().getBackfillFrom()));
    Optional<LocalDate> endOpt = latestScorableDate(horizonDays);
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
      log.warn("IC 증분 공백(h={})이 상한 {}일을 넘어 {}~{} 는 계산하지 않습니다 — POST /api/advisor/admin/jobs/IC_BACKFILL?baseDate={}&horizon={} 로 채우세요",
          horizonDays, maxDays, gapFrom, capStart.minusDays(1), gapFrom, horizonDays);
    }
    int[] result = computeChunked(horizonDays, start, end, runner);
    return Optional.of(new IncrementalResult(start, end, gapFrom, result[0], result[1], horizonDays));
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
