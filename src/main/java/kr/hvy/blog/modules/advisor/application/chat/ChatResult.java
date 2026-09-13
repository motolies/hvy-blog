package kr.hvy.blog.modules.advisor.application.chat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

/**
 * 질문 1건에 대한 답변 결과 — 본문(마크다운, Slack 변환 전)과 감사에 남길 사용량.
 *
 * @param answer           답변 본문(모델 출력 원문)
 * @param model            응답 모델 ID
 * @param promptVersion    시스템 프롬프트 버전
 * @param historyMessages  프롬프트에 넣은 스레드 이전 메시지 수
 * @param toolCalls        호출한 도구 이름 목록(순서대로, 중복 포함)
 * @param promptTokens     입력 토큰(도구 루프 누적)
 * @param completionTokens 출력 토큰(누적)
 * @param reasoningTokens  추론 토큰
 * @param cachedTokens     프롬프트 캐시 적중 입력 토큰
 * @param costUsd          비용(단가 미설정이면 0)
 * @param dataAsOf         답변이 참조한 데이터 기준일(도구 반환 asOf 중 가장 이른 값, 없으면 null)
 */
@Builder
public record ChatResult(String answer, String model, String promptVersion, int historyMessages, List<String> toolCalls, int promptTokens,
                         int completionTokens, int reasoningTokens, int cachedTokens, BigDecimal costUsd, LocalDate dataAsOf) {

  public ChatResult {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
  }
}
