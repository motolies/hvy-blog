package kr.hvy.blog.modules.advisor.application.dto;

import java.time.LocalDate;
import kr.hvy.blog.modules.advisor.application.service.AdvisorGateService;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;

/**
 * ADVISE 게이트 판정 응답 ({@code GET /api/advisor/admin/gate}).
 * <p>
 * {@link AdvisorGateService.Decision} 의 컴포넌트 6개에 파생값 {@code ready}·{@code waitQuietly} 를 <b>값으로 펼쳐</b> 담는다 —
 * record 의 파생 메서드는 Jackson 이 직렬화하지 않고, 프론트가 같은 규칙을 다시 계산하면 판정 규칙이 두 곳에 생기기 때문이다.
 * {@code reason} 은 게이트가 만드는 한글 사유 문장 그대로다("휴장일 …", "DAILY 수집이 아직 끝나지 않았습니다: …").
 */
public record AdvisorGateResponse(
    LocalDate baseDate,
    boolean tradingDay,
    boolean alreadyDone,
    boolean dataReady,
    boolean pastDeadline,
    DataQuality quality,
    String reason,
    boolean ready,
    boolean waitQuietly) {

  /**
   * 게이트 판정을 응답으로 변환한다. 파생값은 Decision 의 메서드로 계산해 규칙의 단일 출처를 유지한다.
   */
  public static AdvisorGateResponse from(LocalDate baseDate, AdvisorGateService.Decision decision) {
    return new AdvisorGateResponse(baseDate, decision.tradingDay(), decision.alreadyDone(), decision.dataReady(),
        decision.pastDeadline(), decision.quality(), decision.reason(), decision.ready(), decision.waitQuietly());
  }
}
