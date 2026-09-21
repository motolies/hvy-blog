package kr.hvy.blog.modules.advisor.domain.model;

/**
 * 주도 섹터 콜 1개. consistent(advice-v6) 는 그 섹터의 업종 지수가 1주·1개월·3개월 세 구간 모두 KOSPI 를 이겼는지 — LLM 출력이 아니라 가드가 입력
 * sectors 의 consistent 에서 채운다(SECTOR 콜 채점을 consistent 로 분할 비교하기 위해 leading_sectors JSON 에 함께 저장). v5 이전 행은 null.
 */
public record SectorCall(String code, String name, String reason, Boolean consistent) {

  /**
   * consistent 미상(옛 호출·픽스처) — null 로 둔다.
   */
  public SectorCall(String code, String name, String reason) {
    this(code, name, reason, null);
  }
}
