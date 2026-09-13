package kr.hvy.blog.modules.advisor.application.chat;

/**
 * Slack 에서 받은 질문 1건. Bolt 이벤트 클래스(MessageEvent·MessageThreadBroadcastEvent)를 이 하나로 평탄화해 라우터·서비스가
 * Bolt 타입을 모르게 한다(단위 테스트에서 이벤트 객체를 조립할 필요가 없다).
 *
 * @param eventId   Slack event_id — Redis·DB 중복 제거 키
 * @param channelId 채널 ID(C…)
 * @param userId    보낸 사용자 ID(U…). 봇 메시지는 봇 user id
 * @param botId     봇이 보낸 메시지면 bot_id, 사람 메시지면 null
 * @param ts        메시지 ts
 * @param threadTs  스레드 댓글이면 원 스레드 ts, 새 글이면 null
 * @param text      본문
 */
public record IncomingQuestion(String eventId, String channelId, String userId, String botId, String ts, String threadTs, String text) {

  /**
   * 답글을 달 스레드 ts — 댓글이면 원 스레드, 새 글이면 그 글 자신(그 아래 스레드를 연다).
   */
  public String replyThreadTs() {
    return threadTs == null || threadTs.isBlank() ? ts : threadTs;
  }

  /**
   * 기존 스레드의 댓글인가(히스토리 조회 대상).
   */
  public boolean isThreadReply() {
    return threadTs != null && !threadTs.isBlank() && !threadTs.equals(ts);
  }
}
