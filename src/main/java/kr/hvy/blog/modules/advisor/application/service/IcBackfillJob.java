package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 * 창 통계로 BACKFILL 가중치 세트를 만들어 활성화한다.
 * <p>
 * 유니버스 뷰가 현재 마스터 기준이라 과거 IC 는 생존편향이 있다 — 절대값이 아니라 시그널 간 상대 순위·부호 확인 용도이며 배수는 clip 으로 제한된다.
 * 부호가 음(flagged)인 시그널은 하한 배수만 받고 관리자 API(/weights) 에서 검토한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class IcBackfillJob implements AdvisorJob {

  private final SignalIcService icService;
  private final WeightSetRepository weightSets;
  private final AdvisorProperties properties;

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.IC_BACKFILL;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    LocalDate from = LocalDate.parse(properties.getIc().getBackfillFrom());
    LocalDate end = icService.latestScorableDate(properties.getHorizonDays())
        .orElseThrow(() -> new IllegalStateException("영업일 캘린더(KOSPI 일봉)가 비어 있습니다"));
    if (end.isBefore(from)) {
      execution.skip("계산 가능한 기준일이 없습니다: " + from + " > " + end);
      return;
    }
    AdvisorSteps steps = new AdvisorSteps(execution);
    int total = 0;
    LocalDate cursor = from;
    int chunks = 0;
    while (!cursor.isAfter(end)) {
      LocalDate chunkEnd = cursor.plusMonths(1).minusDays(1);
      if (chunkEnd.isAfter(end)) {
        chunkEnd = end;
      }
      LocalDate f = cursor;
      LocalDate t = chunkEnd;
      final int[] rows = {0};
      steps.run("IC:" + f.getYear() + "-" + String.format("%02d", f.getMonthValue()), () -> rows[0] = icService.computeAndStore(f, t));
      total += rows[0];
      chunks++;
      cursor = chunkEnd.plusDays(1);
    }
    execution.putMetadata("icFrom", from.toString());
    execution.putMetadata("icTo", end.toString());
    execution.putMetadata("icRows", total);
    execution.putMetadata("chunks", chunks);

    Optional<WeightSet> proposed = icService.proposeWeightSet(end, WeightSetSource.BACKFILL, execution.runId());
    if (proposed.isEmpty()) {
      execution.warn("IC 표본이 부족해 가중치 세트를 만들지 않았습니다 (n_eff < " + properties.getIc().getMinNEff() + ")");
      return;
    }
    long id = weightSets.insert(proposed.get(), true);
    Map<String, Object> summary = new LinkedHashMap<>();
    for (SignalWeightRow w : proposed.get().weights()) {
      summary.put(w.signalCode(), String.format("m=%.3f w=%.4f ic=%s t=%s%s", w.multiplier(), w.weight(),
          w.icMean() == null ? "-" : String.format("%.4f", w.icMean()), w.tStat() == null ? "-" : String.format("%.2f", w.tStat()),
          w.flagged() ? " FLAG" : ""));
    }
    execution.putMetadata("weightSetId", id);
    execution.putMetadata("weights", summary);
    long flagged = proposed.get().weights().stream().filter(SignalWeightRow::flagged).count();
    if (flagged > 0) {
      execution.warn("IC 부호가 음인 시그널 " + flagged + "개 (flagged) — GET /api/advisor/admin/weights 에서 검토");
    }
    log.info("IC 사전 추정 완료: {}~{}, rows={}, weightSet={}, flagged={}", from, end, total, id, flagged);
  }
}
