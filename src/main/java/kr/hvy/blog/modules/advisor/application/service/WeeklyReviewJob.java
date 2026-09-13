package kr.hvy.blog.modules.advisor.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage;
import kr.hvy.blog.modules.advisor.client.llm.AdviceResponse;
import kr.hvy.blog.modules.advisor.client.llm.LessonProposalResponse;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalWeightRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.PromptInputWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 주간 검토 (WEEKLY_REVIEW, 일요일 08:00 KST, WEEKLY 수집 종료 후).
 * <ol>
 *   <li>채점·확정 재채점·IC 증분</li>
 *   <li>가중치 세트 갱신 (n_eff 게이트) → 활성화</li>
 *   <li>교훈: 사후 성과 갱신·폐기 → 셀 집계 → 보조 모델 제안 → 규칙 검증 저장 (누적 픽 게이트 뒤)</li>
 *   <li>재현성: 직전 LIVE 입력을 동결한 채 N회 재실행 → 픽 집합 Jaccard</li>
 *   <li>주간 보고 발행, 프롬프트 스냅샷 보존 정리</li>
 * </ol>
 * 각 단계는 격리되어 하나가 죽어도 나머지는 진행하고 run 은 PARTIAL 로 남는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class WeeklyReviewJob implements AdvisorJob {

  static final int REVIEW_WINDOW_DAYS = 90;

  private final AdvisorProperties properties;
  private final ScoreJob scoreJob;
  private final SignalIcService icService;
  private final WeightSetRepository weightSets;
  private final LessonService lessonService;
  private final AdvisorKpiService kpi;
  private final AdviceWriter adviceWriter;
  private final PromptInputWriter promptInputs;
  private final PromptResources prompts;
  private final MarketJudgeClient judge;
  private final MarketJudgeClient assist;
  private final AdvisorNotifier notifier;
  private final JdbcTemplate jdbc;

  /**
   * 유일한 생성자. judge/assist 클라이언트는 AdvisorAiConfig 의 judgeClient/assistClient 빈을 받는다.
   * 생성자를 둘(Spring 용·테스트 용) 두면 @Autowired 없는 Spring 은 기본 생성자로 후퇴해 기동이 실패하므로(2026-09-13) 하나만 유지한다.
   */
  public WeeklyReviewJob(AdvisorProperties properties, ScoreJob scoreJob, SignalIcService icService, WeightSetRepository weightSets,
      LessonService lessonService, AdvisorKpiService kpi, AdviceWriter adviceWriter, PromptInputWriter promptInputs, PromptResources prompts,
      @Qualifier(MarketJudgeClient.JUDGE_BEAN) MarketJudgeClient judge, @Qualifier(MarketJudgeClient.ASSIST_BEAN) MarketJudgeClient assist,
      AdvisorNotifier notifier, JdbcTemplate jdbc) {
    this.properties = properties;
    this.scoreJob = scoreJob;
    this.icService = icService;
    this.weightSets = weightSets;
    this.lessonService = lessonService;
    this.kpi = kpi;
    this.adviceWriter = adviceWriter;
    this.promptInputs = promptInputs;
    this.prompts = prompts;
    this.judge = judge;
    this.assist = assist;
    this.notifier = notifier;
    this.jdbc = jdbc;
  }

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.WEEKLY_REVIEW;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    LocalDate today = execution.baseDate();
    LocalDate from = today.minusDays(REVIEW_WINDOW_DAYS);
    AdvisorSteps steps = new AdvisorSteps(execution);
    List<String> warnings = new ArrayList<>();

    // ① 채점·IC
    steps.run("SCORE", () -> scoreJob.scoreDue(execution));
    steps.run("IC", () -> icService.computeIncremental(steps::run).ifPresent(r -> r.record(execution)));

    // ② 가중치
    List<String> weightLines = new ArrayList<>();
    List<String> icLines = new ArrayList<>();
    steps.run("WEIGHTS", () -> {
      WeightSet before = weightSets.active().orElseThrow();
      LocalDate asOf = icService.latestScorableDate(properties.getHorizonDays()).orElse(today);
      Optional<WeightSet> proposed = icService.proposeWeightSet(asOf, WeightSetSource.WEEKLY, execution.runId());
      if (proposed.isEmpty()) {
        weightLines.add(String.format("갱신 없음 — IC 표본 부족 (n_eff < %d). 활성 세트 #%d 유지", properties.getIc().getMinNEff(), before.weightSetId()));
        return;
      }
      long id = weightSets.insert(proposed.get(), true);
      execution.putMetadata("weightSetId", id);
      Map<String, SignalWeightRow> old = before.byCode();
      for (SignalWeightRow w : proposed.get().weights()) {
        SignalWeightRow o = old.get(w.signalCode());
        if (o != null && Math.abs(o.weight() - w.weight()) >= 0.005) {
          weightLines.add(String.format("%s %.3f → %.3f (m=%.2f%s)", w.signalCode(), o.weight(), w.weight(), w.multiplier(), w.flagged() ? " ⚑" : ""));
        }
        if (w.icMean() != null) {
          icLines.add(String.format("%s ĪC %+.3f t=%+.1f n=%d%s", w.signalCode(), w.icMean(), w.tStat() == null ? 0 : w.tStat(),
              w.nDays() == null ? 0 : w.nDays(), w.flagged() ? " ⚑" : ""));
        }
      }
      if (weightLines.isEmpty()) {
        weightLines.add("세트 #" + id + " 활성화 (변경 폭 0.005 미만)");
      } else {
        weightLines.add(0, "세트 #" + id + " 활성화 (n_eff " + String.format("%.1f", proposed.get().nEff()) + ")");
      }
      long flagged = proposed.get().weights().stream().filter(SignalWeightRow::flagged).count();
      if (flagged > 0) {
        warnings.add("IC 음수 시그널 " + flagged + "개 — GET /api/advisor/admin/weights 검토");
      }
    });
    icLines.sort((a, b) -> b.compareTo(a));

    // ③ 교훈 (게이트: 누적 LIVE 픽)
    List<String> lessonLines = new ArrayList<>();
    boolean memoryOn = adviceWriter.countLivePicks() >= properties.getLesson().getMinPicks();
    if (memoryOn) {
      steps.run("LESSONS", () -> {
        Instant now = Instant.now();
        List<Long> retired = lessonService.review(now);
        List<LessonService.Cell> cells = lessonService.cells(from.minusDays(365), today);
        double degraded = degradedRatio(from, today);
        Map<String, Object> payload = lessonService.reviewPayload(cells, kpi.calibration(from, today), degraded);
        String schema = LessonSchemaFactory.schemaJson(sectorCodes());
        MarketJudgeClient.CallResult<LessonProposalResponse> r = assist.call(prompts.lessonSystem(), AdvisorJson.write(payload), schema,
            LessonProposalResponse.class);
        execution.recordLlmUsage(r.model(), PromptResources.LESSON_VERSION, r.usage(), r.reasoningTokens(), r.cachedTokens());
        LessonService.Applied applied = lessonService.apply(r.value(), execution.runId(), r.model(), now);
        lessonLines.add(String.format("셀 %d개 검토 · 제안 %d · 활성 +%d · 후보 +%d · 거부 %d · 폐기 %d", cells.size(),
            r.value().proposals() == null ? 0 : r.value().proposals().size(), applied.activated().size(), applied.candidates().size(),
            applied.rejected().size(), retired.size()));
        applied.rejected().stream().limit(3).forEach(reason -> lessonLines.add("거부: " + reason));
        if (r.value().nullResults() != null) {
          r.value().nullResults().stream().limit(2).forEach(s -> lessonLines.add("유의하지 않음: " + s));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("activated", applied.activated());
        meta.put("candidates", applied.candidates());
        meta.put("rejected", applied.rejected().size());
        meta.put("retired", retired);
        execution.putMetadata("lessons", meta);
      });
    } else {
      steps.skip("LESSONS", "누적 LIVE 픽 " + adviceWriter.countLivePicks() + " < " + properties.getLesson().getMinPicks());
      lessonLines.add("메모리 미활성 (누적 픽 " + adviceWriter.countLivePicks() + "/" + properties.getLesson().getMinPicks() + ")");
    }

    // ④ 재현성
    List<String> reproLines = new ArrayList<>();
    int runs = properties.getShadow().getReproducibilityRuns();
    if (runs > 1) {
      steps.run("REPRO", () -> reproducibility(execution, runs, reproLines, warnings));
    } else {
      steps.skip("REPRO", "reproducibility-runs=" + runs);
    }

    // ⑤ 보고
    List<String> kpiLines = new ArrayList<>();
    steps.run("KPI", () -> {
      for (AdvisorKpiService.VariantSummary v : kpi.variantSummaries(from, today)) {
        if (v.picks() == 0) {
          continue;
        }
        kpiLines.add(String.format("%s: n=%d 승률 %.1f%% · 초과 %+.2f%%p ±%.2f · 후보군 대비 %+.2f%%p · 비용차감 %+.2f%%p",
            v.variant(), v.picks(), pct(v.hitRate()), pct(v.meanExcess()), pct(v.seExcess()), pct(v.valueAdd()), pct(v.meanCostAdj())));
      }
      AdvisorKpiService.RegimeSummary regime = kpi.regimeSummary(AdviceVariant.LIVE, from, today);
      if (regime.calls() > 0) {
        kpiLines.add(String.format("국면 적중 %.0f%% (n=%d) · Brier skill %.2f", pct(regime.hitRate()), regime.calls(), nz(regime.brierSkill())));
      }
      // 추세 전망은 20영업일 뒤에야 첫 행이 생기고 4주간은 적중률을 판정하지 않는다(운영 문서 §7)
      AdvisorKpiService.TrendSummary trend = kpi.trendSummary(AdviceVariant.LIVE, from, today);
      if (trend.calls() > 0) {
        kpiLines.add(String.format("%d일 추세 지속 적중 %.0f%% (n=%d) · 무효화 신호 적중 %.0f%% (n=%d)", properties.getTrend().getScoreHorizonDays(),
            pct(trend.hitRate()), trend.calls(), pct(trend.invalidationHitRate()), trend.invalidationCalls()));
      }
      AdvisorKpiService.MorningSummary morning = kpi.morningSummary(AdviceVariant.LIVE, from, today);
      if (morning.calls() > 0) {
        kpiLines.add(String.format("아침 갭 판정 적중 %.0f%% (n=%d) · 주의 비율 %.0f%%", pct(morning.hitRate()), morning.calls(), pct(morning.cautionRate())));
      }
      if (kpiLines.isEmpty()) {
        kpiLines.add("채점된 픽 없음");
      }
    });
    List<String> calibrationLines = new ArrayList<>();
    for (AdvisorKpiService.CalibrationRow c : kpi.calibration(from, today)) {
      calibrationLines.add(String.format("확신 %.2f: n=%d 실현 승률 %.0f%%", c.conviction(), c.n(), pct(c.hitRate())));
    }
    steps.run("REPORT", () -> {
      WeeklyReviewMessage message = WeeklyReviewMessage.builder().periodLabel(from + " ~ " + today).kpiLines(kpiLines)
          .icLines(icLines.subList(0, Math.min(6, icLines.size()))).weightLines(weightLines).calibrationLines(calibrationLines)
          .lessonLines(lessonLines).reproducibilityLines(reproLines).warnings(warnings).runId(execution.runId()).build();
      if (!notifier.publish(message)) {
        execution.warn("주간 보고 Slack 발행 실패");
      }
    });

    // ⑥ 보존 정리
    steps.run("CLEANUP", () -> execution.putMetadata("promptInputsDeleted", promptInputs.deleteOlderThan(properties.getRetentionDays())));
    warnings.forEach(execution::warn);
  }

  /**
   * 직전 LIVE 입력을 동결한 채 N회 재실행해 픽 집합 Jaccard·방향 일치·확신 편차를 잰다. Jaccard < 0.7 이면 경고.
   */
  private void reproducibility(AdvisorExecution execution, int runs, List<String> lines, List<String> warnings) {
    Optional<AdviceHeader> latest = adviceWriter.findLatest(AdviceVariant.LIVE, execution.baseDate());
    if (latest.isEmpty()) {
      lines.add("측정 대상 없음");
      return;
    }
    Optional<PromptInputRow> input = promptInputs.find(latest.get().runId(), AdviceVariant.LIVE);
    if (input.isEmpty()) {
      lines.add("직전 LIVE 입력 스냅샷 없음");
      return;
    }
    Map<String, Object> payload = AdvisorJson.readMap(input.get().userPayload());
    List<String> tickers = new ArrayList<>();
    Set<String> sectors = new HashSet<>();
    if (payload.get("candidates") instanceof Map<?, ?> c && c.get("rows") instanceof List<?> rows) {
      for (Object row : rows) {
        if (row instanceof List<?> r && !r.isEmpty()) {
          tickers.add(String.valueOf(r.get(0)));
          if (r.size() > 2 && r.get(2) != null) {
            sectors.add(String.valueOf(r.get(2)));
          }
        }
      }
    }
    if (payload.get("sectors") instanceof Map<?, ?> s) {
      for (String key : List.of("top", "bottom")) {
        if (s.get(key) instanceof List<?> rows) {
          rows.forEach(row -> {
            if (row instanceof List<?> r && !r.isEmpty()) {
              sectors.add(String.valueOf(r.get(0)));
            }
          });
        }
      }
    }
    String schema = AdviceSchemaFactory.schemaJson(tickers, new ArrayList<>(sectors));
    List<Set<String>> pickSets = new ArrayList<>();
    List<String> regimes = new ArrayList<>();
    for (int i = 0; i < runs; i++) {
      MarketJudgeClient.CallResult<AdviceResponse> r = judge.call(prompts.adviceSystem(), input.get().userPayload(), schema, AdviceResponse.class);
      execution.recordLlmUsage(r.model(), PromptResources.ADVICE_VERSION, r.usage(), r.reasoningTokens(), r.cachedTokens());
      Set<String> picks = new HashSet<>();
      if (r.value().picks() != null) {
        r.value().picks().forEach(p -> picks.add(p.ticker()));
      }
      pickSets.add(picks);
      regimes.add(r.value().regime() == null ? "-" : r.value().regime().kospiDir());
    }
    double jaccardSum = 0;
    int pairs = 0;
    for (int i = 0; i < pickSets.size(); i++) {
      for (int j = i + 1; j < pickSets.size(); j++) {
        Set<String> union = new HashSet<>(pickSets.get(i));
        union.addAll(pickSets.get(j));
        Set<String> inter = new HashSet<>(pickSets.get(i));
        inter.retainAll(pickSets.get(j));
        jaccardSum += union.isEmpty() ? 1.0 : (double) inter.size() / union.size();
        pairs++;
      }
    }
    double jaccard = pairs == 0 ? 1.0 : jaccardSum / pairs;
    long distinctRegimes = regimes.stream().distinct().count();
    execution.putMetadata("reproJaccard", Math.round(jaccard * 1000) / 1000.0);
    lines.add(String.format("동결 입력 %d회: 픽 집합 Jaccard %.2f · KOSPI 방향 %s", runs, jaccard, distinctRegimes == 1 ? "일치" : "불일치(" + distinctRegimes + "종)"));
    if (jaccard < 0.7) {
      warnings.add(String.format("재현성 낮음 (Jaccard %.2f < 0.70) — LLM 랭킹 관여 축소 검토", jaccard));
    }
  }

  private double degradedRatio(LocalDate from, LocalDate to) {
    Double ratio = jdbc.queryForObject("SELECT COALESCE(AVG(CASE WHEN data_quality <> 'OK' THEN 1.0 ELSE 0.0 END), 0) "
        + "FROM tb_advisor_advice WHERE variant = 'LIVE' AND base_date BETWEEN ? AND ?", Double.class, from, to);
    return ratio == null ? 0 : ratio;
  }

  private List<String> sectorCodes() {
    return jdbc.queryForList("SELECT DISTINCT sector_code FROM tb_stock_sector_map WHERE source = 'KRX' AND valid_to IS NULL ORDER BY sector_code",
        String.class);
  }

  private static double pct(Double v) {
    return v == null ? 0 : v * 100;
  }

  private static double nz(Double v) {
    return v == null ? 0 : v;
  }
}
