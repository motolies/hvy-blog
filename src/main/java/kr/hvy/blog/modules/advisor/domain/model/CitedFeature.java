package kr.hvy.blog.modules.advisor.domain.model;

/**
 * LLM 이 근거로 인용한 특징. 입력값과 대조해 허용오차 밖이면 픽을 폐기한다(환각 가드).
 */
public record CitedFeature(String name, double value) {
}
