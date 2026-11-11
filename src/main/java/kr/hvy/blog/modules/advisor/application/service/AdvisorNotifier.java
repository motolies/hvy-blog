package kr.hvy.blog.modules.advisor.application.service;

import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * advisor Slack 알림 (CollectNotifier 패턴: Optional<Notify> 주입, 전송 실패는 삼키고 로그, 빈이 없으면 WARN 로그만).
 * <ul>
 *   <li>일일 추천·장중 점검·주간 보고: {@link #publish(SlackMessage)} 로 #hvy-advisor Block Kit, 멘션 없음 (잡이 직접 호출)</li>
 *   <li>PARTIAL 종료(가드 제거율 초과·섀도 실패 등): #hvy-notify 텍스트</li>
 *   <li>잡 예외·스케줄 트리거 거부·게이트 마감 초과: #hvy-error + 멘션 (run 당 1통)</li>
 * </ul>
 * 알림 실패가 잡을 죽이지 않도록 어떤 메서드도 예외를 던지지 않는다.
 * <p>
 * 결과를 돌려주는 메서드({@link #publish}·{@link #alert})는 <b>Slack 이 메시지를 받았는가</b>를 돌려준다 — {@code Notify} 가 전송 결과를
 * 알려 주므로(hvy-common 0.4.1) 전송 예외뿐 아니라 Slack 의 거부(ok=false: 채널 없음·토큰 무효·블록 형식 오류)도 false 다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisorNotifier {

  private final Optional<Notify> notify;

  /**
   * 정상 종료 후. SUCCESS 는 잡이 스스로 발행했으므로 조용히, PARTIAL 은 경고 요약을 #hvy-notify 로.
   */
  public void afterRun(AdvisorRun run, AdvisorExecution execution, AdvisorStatus status) {
    if (status != AdvisorStatus.PARTIAL) {
      return;
    }
    String text = String.format("[AI 판단 %s] run=%d %s(%s) base=%s%n%s",
        status, run.getRunId(), run.getJobType(), run.getJobType().getDesc(), execution.baseDate(),
        String.valueOf(execution.summary()));
    sendText(SlackChannel.NOTIFY, text, false);
  }

  /**
   * 잡 본문이 예외로 죽었을 때.
   */
  public void afterFailure(AdvisorRun run, AdvisorExecution execution, Exception cause) {
    String text = String.format("[AI 판단 실패] run=%d %s(%s) base=%s%n원인: %s%nLLM 호출 %d회, 토큰 in %d / out %d",
        run.getRunId(), run.getJobType(), run.getJobType().getDesc(), execution.baseDate(), cause,
        execution.llmCalls(), execution.promptTokens(), execution.completionTokens());
    sendText(SlackChannel.ERROR, text, true);
  }

  /**
   * 스케줄 트리거가 run 을 만들기 전에 거부됐을 때(설정 누락·이미 실행 중·DB 오류). AbstractScheduler 가 예외를 로그로만 삼키므로 여기서 알린다.
   */
  public void afterTriggerRejected(AdvisorJobType jobType, Exception cause) {
    String text = String.format("[AI 판단 미실행] SCHEDULER %s(%s)%n원인: %s%nrun 이 생성되지 않았습니다 — 원인 해소 후 POST /api/advisor/admin/jobs/%s 로 보충",
        jobType, jobType.getDesc(), cause, jobType.getCode());
    sendText(SlackChannel.ERROR, text, true);
  }

  /**
   * 잡 내부의 임의 경보 (게이트 마감 초과 등). error=true 면 #hvy-error + 멘션, 아니면 #hvy-notify.
   *
   * @return Slack 이 메시지를 받았는가 — Notify 빈이 없거나, 전송이 실패했거나(네트워크·HTTP 오류), Slack 이 거부했으면(ok=false) false.
   *     예외는 던지지 않는다. 다시 보낼지 정해야 하는 호출부(LlmProviderGate)가 본다
   */
  public boolean alert(String text, boolean error) {
    return sendText(error ? SlackChannel.ERROR : SlackChannel.NOTIFY, text, error);
  }

  /**
   * Block Kit 메시지를 발행한다. Slack 이 받았는지를 돌려주되 예외는 던지지 않는다 — 잡은 true 일 때만 "발행됨" 을 기록한다.
   * Slack 이 블록을 거부하면(invalid_blocks 등, HTTP 200 의 ok=false) false 다.
   */
  public boolean publish(SlackMessage message) {
    if (notify.isEmpty()) {
      log.warn("Slack Notify 빈 없음 — 발행 생략: {}", message.getFallbackText());
      return false;
    }
    try {
      return notify.get().sendMessage(message);
    } catch (Exception e) {
      log.error("advisor Slack 발행 실패(무시): {}", e.getMessage());
      return false;
    }
  }

  /**
   * 텍스트 알림을 보낸다. Slack 이 받았는지를 돌려주되 예외는 던지지 않는다(빈이 없으면 WARN 로그만).
   */
  private boolean sendText(SlackChannel channel, String text, boolean mention) {
    if (notify.isEmpty()) {
      log.warn("Slack Notify 빈 없음 — 알림 생략: {}", text.replace('\n', ' '));
      return false;
    }
    try {
      return notify.get().sendMessage(NotifyRequest.builder()
          .channel(channel.getChannel())
          .message(text)
          .isNotify(mention)
          .build());
    } catch (Exception e) {
      log.error("advisor Slack 전송 실패(무시): {}", e.getMessage());
      return false;
    }
  }
}
