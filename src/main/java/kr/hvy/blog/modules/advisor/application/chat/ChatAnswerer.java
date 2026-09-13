package kr.hvy.blog.modules.advisor.application.chat;

/**
 * 질문에 답을 만드는 계약. P1 은 구현 빈이 없어 {@link AdvisorChatService} 가 에코로 대신하고, P2 의 LLM 래퍼가 이 인터페이스로 들어온다.
 * 구현은 예외를 던져도 된다 — 서비스가 FAILED 로 기록하고 스레드에 실패를 알린다.
 */
public interface ChatAnswerer {

  ChatResult answer(IncomingQuestion question);
}
