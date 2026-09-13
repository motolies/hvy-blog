package kr.hvy.blog.modules.advisor.application.chat.tool;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 질문 1건의 도구 실행 범위 — 호출한 도구 이름(순서대로), 소프트 마감, 도구가 참조한 기준일.
 * <p>
 * Spring AI {@link ToolContext} 에 실어 보내면 {@code @Tool} 메서드가 마지막 인자로 받을 수 있고 모델에게는 노출되지 않는다. 그래서 toolkit 은 싱글톤 빈으로
 * 두고도 요청마다 호출 목록을 모을 수 있다. 도구 루프는 한 스레드에서 돌지만 병렬 도구 호출 가능성에 대비해 동기화한다.
 */
public final class ChatRequestScope {

  public static final String KEY = "advisor.chat.scope";

  private final Instant deadline;
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
  private final List<LocalDate> asOfs = Collections.synchronizedList(new ArrayList<>());

  public ChatRequestScope(Instant deadline) {
    this.deadline = deadline;
  }

  /**
   * ToolContext 에 넣을 맵.
   */
  public Map<String, Object> toToolContext() {
    return Map.of(KEY, this);
  }

  /**
   * ToolContext 에서 꺼낸다. 테스트처럼 컨텍스트 없이 직접 부르면 빈 Optional.
   */
  public static Optional<ChatRequestScope> from(ToolContext context) {
    if (context == null || context.getContext() == null) {
      return Optional.empty();
    }
    Object scope = context.getContext().get(KEY);
    return scope instanceof ChatRequestScope s ? Optional.of(s) : Optional.empty();
  }

  /**
   * 도구 호출을 기록하고 마감 초과 여부를 돌려준다. 초과면 도구는 deadline 오류를 돌려주고 모델이 마무리한다.
   */
  public boolean enter(String toolName) {
    calls.add(toolName);
    return deadline != null && Instant.now().isAfter(deadline);
  }

  /**
   * 도구가 참조한 기준일. 답변의 data_as_of 는 이 중 가장 이른 값.
   */
  public void asOf(LocalDate date) {
    if (date != null) {
      asOfs.add(date);
    }
  }

  public List<String> calls() {
    return List.copyOf(calls);
  }

  public Optional<LocalDate> earliestAsOf() {
    synchronized (asOfs) {
      return asOfs.stream().min(LocalDate::compareTo);
    }
  }
}
