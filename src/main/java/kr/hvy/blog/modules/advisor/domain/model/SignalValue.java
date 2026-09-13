package kr.hvy.blog.modules.advisor.domain.model;

/**
 * 후보 1개의 시그널 1개 스냅샷. pct 는 유니버스 내 백분위[0,1], w 는 그날 적용 가중치, raw 는 원값(NULL 이면 중립 0.5 처리됨).
 */
public record SignalValue(double pct, double w, Double raw) {
}
