package kr.hvy.blog.modules.stock.client;

/**
 * KIS 호출 1건의 소속 정보. 실패 기록이 어느 run·어느 종목인지 남기고 통계를 누적한다.
 *
 * @param runId     소속 run (없으면 null)
 * @param targetKey 대상 종목/지수 코드 (없으면 null)
 * @param stats     run 단위 공유 통계
 */
public record KisCallContext(Long runId, String targetKey, KisCallStats stats) {

  /**
   * run 에 속하지 않는 단발 호출용 컨텍스트.
   */
  public static KisCallContext adhoc() {
    return new KisCallContext(null, null, new KisCallStats());
  }

  /**
   * run 소속 컨텍스트를 만든다.
   */
  public static KisCallContext of(Long runId) {
    return new KisCallContext(runId, null, new KisCallStats());
  }

  /**
   * 통계는 공유하면서 대상 종목만 바꾼 컨텍스트를 만든다.
   */
  public KisCallContext withTarget(String target) {
    return new KisCallContext(runId, target, stats);
  }
}
