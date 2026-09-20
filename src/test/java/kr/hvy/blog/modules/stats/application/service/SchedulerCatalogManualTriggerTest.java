package kr.hvy.blog.modules.stats.application.service;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.stats.application.dto.ManualTrigger;
import kr.hvy.blog.modules.stats.application.service.SchedulerCatalog.ResolvedJob;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 스케줄러 ↔ 수동 실행 잡 매핑({@link ManualTrigger})을 고정한다.
 * <p>
 * 잡 이름이 문자열이라 컴파일러가 오타를 못 잡는다 — 여기서 모든 값을 {@code CollectJobType}/{@code AdvisorJobType.valueOf} 로 대조하고,
 * stock·advisor 11행(eventfeed am/pm 별도)만 매핑을 갖고 나머지(공인 IP·핫딜·로그 정리·Jira·Claude)는 null 임을 못 박는다.
 * {@code resolve()} 가 lock-name 프로퍼티 없는 정의를 카탈로그에서 제외하므로 MockEnvironment 에 16개 키를 전부 채운다.
 */
class SchedulerCatalogManualTriggerTest {

  private static final List<String> LOCK_NAME_KEYS = List.of(
      "scheduler.public-ip.lock-name", "scheduler.hotdeal.lock-name", "scheduler.log-cleaner.lock-name", "scheduler.jira.lock-name",
      "scheduler.claude.lock-name",
      "scheduler.stock-master.lock-name", "scheduler.stock-daily.lock-name", "scheduler.stock-overseas.lock-name",
      "scheduler.stock-macro.lock-name", "scheduler.stock-eventfeed.lock-name-am", "scheduler.stock-eventfeed.lock-name-pm",
      "scheduler.stock-weekly.lock-name",
      "scheduler.advisor-advise.lock-name", "scheduler.advisor-intraday.lock-name", "scheduler.advisor-morning-check.lock-name",
      "scheduler.advisor-weekly-review.lock-name");

  /** 정본은 infra/scheduler/*Scheduler 가 부르는 잡 — 스케줄러 본문이 바뀌면 이 표와 SchedulerCatalog 를 함께 고친다 */
  private static final Map<String, ManualTrigger> EXPECTED = Map.ofEntries(
      entry("scheduler.stock-master.lock-name", ManualTrigger.stock("MASTER", "HOLIDAY")),
      entry("scheduler.stock-daily.lock-name", ManualTrigger.stock("DAILY")),
      entry("scheduler.stock-overseas.lock-name", ManualTrigger.stock("OVERSEAS_DAILY")),
      entry("scheduler.stock-macro.lock-name", ManualTrigger.stock("MACRO")),
      entry("scheduler.stock-eventfeed.lock-name-am", ManualTrigger.stock("NEWS")),
      entry("scheduler.stock-eventfeed.lock-name-pm", ManualTrigger.stock("NEWS")),
      entry("scheduler.stock-weekly.lock-name", ManualTrigger.stock("WEEKLY")),
      entry("scheduler.advisor-advise.lock-name", ManualTrigger.advisor("ADVISE")),
      entry("scheduler.advisor-intraday.lock-name", ManualTrigger.advisor("INTRADAY")),
      entry("scheduler.advisor-morning-check.lock-name", ManualTrigger.advisor("MORNING_CHECK")),
      entry("scheduler.advisor-weekly-review.lock-name", ManualTrigger.advisor("WEEKLY_REVIEW")));

  private Map<String, ResolvedJob> jobsByLockName;

  @BeforeEach
  void setUp() {
    MockEnvironment environment = new MockEnvironment();
    // lock-name 값은 키 자체로 둔다 — 프로파일 접미(-TEST/-PROD)는 이 테스트의 관심사가 아니고, 키로 되찾기 쉽다
    LOCK_NAME_KEYS.forEach(key -> environment.setProperty(key, key));
    jobsByLockName = new SchedulerCatalog(environment).resolveAll().stream()
        .collect(Collectors.toMap(ResolvedJob::lockName, Function.identity()));
  }

  @Test
  @DisplayName("lock-name 을 전부 채우면 16개 정의가 모두 해석된다 (정의 누락·중복 감지)")
  void allDefinitionsResolve() {
    assertThat(jobsByLockName).containsOnlyKeys(LOCK_NAME_KEYS);
  }

  @Test
  @DisplayName("stock·advisor 11행(eventfeed am/pm 포함)만 manualTrigger 를 갖고 나머지는 null")
  void onlyStockAndAdvisorRowsHaveManualTrigger() {
    jobsByLockName.forEach((lockName, job) -> {
      if (EXPECTED.containsKey(lockName)) {
        assertThat(job.manualTrigger()).as(lockName).isEqualTo(EXPECTED.get(lockName));
      } else {
        assertThat(job.manualTrigger()).as(lockName + " 은 수동 실행 대상이 아니다").isNull();
      }
    });
  }

  @Test
  @DisplayName("모든 manualTrigger.jobTypes 는 module 에 맞는 enum 상수로 해석된다 (문자열 오타 방어)")
  void everyJobTypeResolvesToEnum() {
    jobsByLockName.values().stream()
        .map(ResolvedJob::manualTrigger)
        .filter(trigger -> trigger != null)
        .forEach(trigger -> {
          assertThat(trigger.jobTypes()).as("빈 잡 목록은 버튼이 아무것도 안 부른다").isNotEmpty();
          for (String jobType : trigger.jobTypes()) {
            switch (trigger.module()) {
              case ManualTrigger.MODULE_STOCK -> assertThatCode(() -> CollectJobType.valueOf(jobType))
                  .as("STOCK 잡 %s 는 CollectJobType 에 있어야 한다", jobType).doesNotThrowAnyException();
              case ManualTrigger.MODULE_ADVISOR -> assertThatCode(() -> AdvisorJobType.valueOf(jobType))
                  .as("ADVISOR 잡 %s 는 AdvisorJobType 에 있어야 한다", jobType).doesNotThrowAnyException();
              default -> throw new AssertionError("알 수 없는 module: " + trigger.module());
            }
          }
        });
  }

  @Test
  @DisplayName("code == 상수명 규약: jobTypes 문자열은 REST 경로 변수로 그대로 쓸 수 있다")
  void jobTypeStringsEqualEnumCodes() {
    for (CollectJobType type : CollectJobType.values()) {
      assertThat(type.getCode()).isEqualTo(type.name());
    }
    for (AdvisorJobType type : AdvisorJobType.values()) {
      assertThat(type.getCode()).isEqualTo(type.name());
    }
  }
}
