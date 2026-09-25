package kr.hvy.blog.modules.advisor.application.dto;

import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;

/**
 * KPI 요약: 변형별 픽 KPI(부가가치 = 픽 − 후보군), 국면 콜, 보정 표, 최근 픽. kind·horizonDays 는 집계한 판단 종류·채점 호라이즌(M5, 기본 DAILY·5).
 */
public record ScoreSummaryResponse(
    LocalDate from,
    LocalDate to,
    int horizonDays,
    List<AdvisorKpiService.VariantSummary> variants,
    AdvisorKpiService.RegimeSummary regime,
    List<AdvisorKpiService.CalibrationRow> calibration,
    List<AdvisorKpiService.RecentPick> recent,
    String note,
    AdviceKind kind) {
}
