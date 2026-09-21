package kr.hvy.blog.modules.advisor.client.llm;

import java.util.List;

/**
 * 12:00 픽 회고(note-v1) 보조 모델의 구조화 출력. 픽마다 deviation(얼마나·어떻게 요약), why(thesis 의 어떤 가정이 흔들렸는지·risk 의 첫 신호 발동 여부),
 * hypothesis(종목·날짜 없는 일반화 가설, 위반 시 IntradayCheckJob 가드가 null 로), tags(관련 시그널·섹터·국면).
 * 어떤 문장도 다음 판단 프롬프트에 들어가지 않는다 — DB·Slack·관리자 기록용(사용자 결정 2026-09-21 ①).
 */
public record PickNoteResponse(List<Note> notes) {

  public record Note(String ticker, String deviation, String why, String hypothesis, Tags tags) {
  }

  public record Tags(List<String> signals, String sector, String regime) {
  }
}
