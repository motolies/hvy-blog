package kr.hvy.blog.modules.advisor.domain.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 정량 스크리닝 결과. candidates 는 종합 점수 내림차순(quantRank 1 부터).
 *
 * @param universeSize 기준일 유니버스 종목 수
 * @param cutSize      1차 컷(모멘텀 양 또는 거래대금 급증, MA60 위) 통과 수
 * @param weightSetId  적용한 가중치 세트
 */
public record ScreeningResult(LocalDate baseDate, int universeSize, int cutSize, long weightSetId, List<CandidateRow> candidates) {
}
