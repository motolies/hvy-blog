package kr.hvy.blog.modules.advisor.client.llm;

import java.util.List;

/**
 * 주간 검토 보조 모델의 구조화 출력: 규칙 후보(proposals)·유의하지 않은 가설(nullResults, 확증 편향 차단)·폐기 후보(retireCandidates).
 * condition 은 기계 판정 술어이며 LessonService 가 형식·통계·티커 금지 규칙으로 검증한 뒤에만 저장한다.
 */
public record LessonProposalResponse(List<Proposal> proposals, List<String> nullResults, List<Retire> retireCandidates) {

  public record Proposal(String scope, Condition condition, String observation, Evidence evidence, String rule) {
  }

  /** trend 는 lesson-v2 에서 추가된 규칙 기반 중기 추세 조건 (BULL|SIDEWAYS|BEAR|null) */
  public record Condition(String regime, String trend, String signal, String op, Double pct, String sector) {
  }

  public record Evidence(Integer n, String from, String to, Double excess, Double t) {
  }

  public record Retire(Long lessonId, String reason) {
  }
}
