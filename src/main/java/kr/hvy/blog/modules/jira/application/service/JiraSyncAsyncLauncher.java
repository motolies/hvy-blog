package kr.hvy.blog.modules.jira.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Jira 동기화를 백그라운드로 던지는 전용 진입점. REST 트리거처럼 즉시 응답해야 하는 호출부가 쓴다.
 * <p>
 * <b>왜 {@link JiraBatchService} 안에 {@code @Async} 메서드를 두지 않는가</b> —
 * 같은 빈 안에서 {@code asyncMethod() -> syncAllIssuesAndWorklogs()} 로 호출하면 self-invocation 이라
 * 프록시를 타지 않는다. 그러면 대상 메서드의 {@code @DistributedLock} 이 <b>조용히 무시되어</b>
 * 스케줄러 실행과 REST 트리거가 동시에 돌 수 있다. 별도 빈으로 분리하면 여기서 나가는 호출이
 * {@code JiraBatchService} 프록시를 정상적으로 통과해 락이 걸린다.
 * <p>
 * {@code @Async} 기본 실행기(@Primary {@code virtualThreadExecutor})에는 {@code TraceTaskDecorator} 가
 * 붙어 있어, 요청 스레드의 traceId 가 동기화 작업 로그까지 따라간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JiraSyncAsyncLauncher {

  private final JiraBatchService jiraBatchService;

  /**
   * 동기화를 비동기로 시작한다. 예외는 호출부로 돌아가지 않으므로 여기서 로그로 남긴다.
   */
  @Async
  public void syncAllIssuesAndWorklogsAsync() {
    try {
      jiraBatchService.syncAllIssuesAndWorklogs();
    } catch (Exception e) {
      log.error("Jira 비동기 동기화 실패: {}", e.getMessage(), e);
    }
  }
}
