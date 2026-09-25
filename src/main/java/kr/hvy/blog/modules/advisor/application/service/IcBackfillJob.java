package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.model.SignalWeightRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * IC 사전 추정 (관리자 1회, IC_BACKFILL). advisor.ic.backfill-from 부터 계산 가능한 마지막 기준일까지 월 단위 청크로 IC 를 저장하고,
 * 창 통계로 BACKFILL 가중치 세트를 만들어 활성화한다. 요청에 baseDate 가 있으면 그 날부터 계산한다 — 증분 상한 때문에 남은 공백
 * ({@code POST /jobs/IC_BACKFILL?baseDate=<icGapFrom>}) 을 채우는 경로(2026-09-13).
 * <p>
 * 멀티 호라이즌(M5, 2026-09-25): 요청 메타 {@code horizon} 이 있으면 그 호라이즌 하나만, 없으면 가중치 학습 호라이즌(advisor.horizons learn=true) 전부를
 * 차례로 돈다. 학습 호라이즌은 호라이즌별 BACKFILL 세트를 만들어 같은 호라이즌 안에서 활성화하고, 모니터링 호라이즌(60·180)은 IC 만 저장한다.
 * 호라이즌마다 끝이 다르다(캘린더 끝 − h) — h=180 은 약 9개월 전 기준일까지만 계산된다.
 * <p>
 * 유니버스 뷰가 현재 마스터 기준이라 과거 IC 는 생존편향이 있다 — 절대값이 아니라 시그널 간 상대 순위·부호 확인 용도이며 배수는 clip 으로 제한된다.
 * 부호가 음(flagged)인 시그널은 하한 배수만 받고 관리자 API(/weights) 에서 검토한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class IcBackfillJob implements AdvisorJob {

  /** 요청 메타 키: 백필할 호라이즌 (없으면 학습 호라이즌 전부). 관리자 API {@code ?horizon=} 이 싣는다 */
  public static final String HORIZON_METADATA = "horizon";

  private final SignalIcService icService;
  private final WeightSetRepository weightSets;
  private final AdvisorProperties properties;

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.IC_BACKFILL;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    boolean requested = Boolean.TRUE.equals(execution.metadata("requested"));
    LocalDate from = requested ? execution.baseDate() : LocalDate.parse(properties.getIc().getBackfillFrom());
    List<Integer> horizons = targetHorizons(execution);
    AdvisorSteps steps = new AdvisorSteps(execution);
    Map<String, Object> byHorizon = new LinkedHashMap<>();
    for (int h : horizons) {
      backfill(execution, steps, h, from).ifPresent(summary -> byHorizon.put(String.valueOf(h), summary));
    }
    execution.putMetadata("horizons", byHorizon);
    if (byHorizon.isEmpty()) {
      execution.skip("계산 가능한 기준일이 없습니다: " + from + " 이후 (호라이즌 " + horizons + ")");
    }
  }

  /**
   * 대상 호라이즌: 요청 메타 horizon 이 있으면 그 하나(IC 대상 호라이즌이어야 한다), 없으면 학습 호라이즌 전부.
   */
  List<Integer> targetHorizons(AdvisorExecution execution) {
    Object requested = execution.metadata(HORIZON_METADATA);
    if (requested == null) {
      return properties.learnHorizons();
    }
    int h = requested instanceof Number n ? n.intValue() : Integer.parseInt(requested.toString());
    if (!properties.icHorizons().contains(h)) {
      throw new IllegalArgumentException("IC 대상 호라이즌이 아닙니다: " + h + " (허용 " + properties.icHorizons() + ")");
    }
    return List.of(h);
  }

  /**
   * 호라이즌 1개: [from, 캘린더 끝 − h] IC 저장 → 학습 호라이즌이면 BACKFILL 세트 생성·활성화. DAILY 결정 호라이즌은 기존 평면 메타 키
   * (icFrom·icTo·icRows·chunks·weightSetId·weights)도 그대로 남긴다. 계산할 날이 없으면 empty.
   */
  Optional<Map<String, Object>> backfill(AdvisorExecution execution, AdvisorSteps steps, int h, LocalDate from) {
    boolean decision = h == properties.getHorizonDays();
    LocalDate end = icService.latestScorableDate(h)
        .orElseThrow(() -> new IllegalStateException("영업일 캘린더(KOSPI 일봉)가 비어 있습니다"));
    if (end.isBefore(from)) {
      log.info("IC 사전 추정 건너뜀: h={}, {} > {}", h, from, end);
      return Optional.empty();
    }
    int[] result = icService.computeChunked(h, from, end, steps::run);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("icFrom", from.toString());
    summary.put("icTo", end.toString());
    summary.put("icRows", result[0]);
    summary.put("chunks", result[1]);
    if (decision) {
      summary.forEach(execution::putMetadata);
    }
    if (!properties.isLearnHorizon(h)) {
      summary.put("weights", "모니터링 호라이즌 — 가중치 학습 안 함");
      log.info("IC 사전 추정 완료(모니터링): h={}, {}~{}, rows={}", h, from, end, result[0]);
      return Optional.of(summary);
    }

    Optional<WeightSet> proposed = icService.proposeWeightSet(h, end, WeightSetSource.BACKFILL, execution.runId());
    if (proposed.isEmpty()) {
      execution.warn("IC 표본이 부족해 가중치 세트를 만들지 않았습니다 (h=" + h + ", n_eff < " + properties.getIc().getMinNEff() + ")");
      return Optional.of(summary);
    }
    long id = weightSets.insert(proposed.get(), true);
    Map<String, Object> weights = new LinkedHashMap<>();
    for (SignalWeightRow w : proposed.get().weights()) {
      weights.put(w.signalCode(), String.format("m=%.3f w=%.4f ic=%s t=%s%s", w.multiplier(), w.weight(),
          w.icMean() == null ? "-" : String.format("%.4f", w.icMean()), w.tStat() == null ? "-" : String.format("%.2f", w.tStat()),
          w.flagged() ? " FLAG" : ""));
    }
    summary.put("weightSetId", id);
    summary.put("weights", weights);
    if (decision) {
      execution.putMetadata("weightSetId", id);
      execution.putMetadata("weights", weights);
    }
    long flagged = proposed.get().weights().stream().filter(SignalWeightRow::flagged).count();
    if (flagged > 0) {
      execution.warn("IC 부호가 음인 시그널 " + flagged + "개 (h=" + h + ", flagged) — GET /api/advisor/admin/weights?horizon=" + h + " 에서 검토");
    }
    log.info("IC 사전 추정 완료: h={}, {}~{}, rows={}, weightSet={}, flagged={}", h, from, end, result[0], id, flagged);
    return Optional.of(summary);
  }
}
