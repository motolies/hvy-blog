package kr.hvy.blog.modules.stats.application.dto;

import java.util.List;

/**
 * 스케줄러 행에서 같은 잡을 수동 실행할 때 부를 모듈·잡 목록 (순차). null 이면 수동 실행 불가.
 * <p>
 * 프론트는 {@code module} 로 베이스 경로를 고른다: {@code STOCK → POST /api/stock/admin/collect/{jobType}},
 * {@code ADVISOR → POST /api/advisor/admin/jobs/{jobType}}. {@code jobTypes} 가 둘 이상이면 앞 잡이 끝난 뒤 다음 잡을 부른다
 * (예: stock-master 는 MASTER → HOLIDAY).
 * <p>
 * 잡 이름을 enum 이 아니라 문자열로 두는 이유: stats 모듈은 횡단 관찰 계층이라 stock/advisor 도메인 enum 에 컴파일 의존을 걸지 않는다.
 * 대신 {@code SchedulerCatalogManualTriggerTest} 가 모든 값을 {@code CollectJobType}/{@code AdvisorJobType.valueOf} 로 대조해 오타를 잡는다
 * (두 enum 모두 code == 상수명 규약이라 REST 경로 변수로 그대로 쓸 수 있다).
 */
public record ManualTrigger(String module, List<String> jobTypes) {

  public static final String MODULE_STOCK = "STOCK";
  public static final String MODULE_ADVISOR = "ADVISOR";

  /**
   * stock 수집 잡 트리거 (순서대로 실행).
   */
  public static ManualTrigger stock(String... jobTypes) {
    return new ManualTrigger(MODULE_STOCK, List.of(jobTypes));
  }

  /**
   * advisor 잡 트리거.
   */
  public static ManualTrigger advisor(String... jobTypes) {
    return new ManualTrigger(MODULE_ADVISOR, List.of(jobTypes));
  }
}
