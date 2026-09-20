package kr.hvy.blog.modules.stats.application.service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import kr.hvy.blog.modules.stats.application.dto.ManualTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * 스케줄러 잡 카탈로그 — shedlock 행에 "이 잡이 무엇이고 얼마마다 도는가"를 붙인다.
 * <p>
 * <b>왜 명시 카탈로그인가.</b> 순수 설정 주입이 불가능하다:
 * <ul>
 *   <li>{@code ClaudeTokenRefreshScheduler} 는 yml 에 cron 이 <b>없고</b>
 *       {@code @Scheduled} 3개(KST 06:05/11:05/16:05)를 클래스에 하드코딩하고 있다.</li>
 *   <li>shedlock {@code name} 은 프로파일별로 다르다({@code HOTDEAL-PROD} / {@code HOTDEAL-TEST}).
 *       하드코딩하면 dev/prod 중 하나에서 반드시 깨진다 → {@code Environment} 로 해석한다.</li>
 * </ul>
 * {@code ScheduledAnnotationBeanPostProcessor} 인트로스펙션으로 자동 수집하는 방법도 동작하지만
 * Spring 내부 API 의존이라 부트 업그레이드 때 조용히 깨진다. 잡이 9개뿐이므로 명시가 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerCatalog {

  /**
   * @param lockNameKey  shedlock.name 을 담고 있는 프로퍼티 키 (프로파일별로 값이 다르다)
   * @param displayName  화면 표기명
   * @param cronKey      cron 프로퍼티 키. null 이면 fallbackCrons 를 쓴다
   * @param fallbackCrons yml 에 cron 이 없는 잡의 클래스 하드코딩값
   * @param enabledKey   on/off 프로퍼티 키. null 이면 항상 활성
   * @param zone         cron 해석 타임존
   * @param manualTrigger 이 스케줄러가 부르는 잡을 관리자 화면에서 수동 실행할 때의 모듈·잡 목록. 수동 실행 대상이 아니면 null.
   *                      정본은 {@code infra/scheduler/*Scheduler} 가 실제로 부르는 잡이다 — 스케줄러 본문을 바꾸면 여기도 같이 바꾼다
   *                      ({@code SchedulerCatalogManualTriggerTest} 가 enum 존재만 검증하고 "어느 잡을 부르는가" 까지는 못 잡는다)
   */
  private record Definition(String lockNameKey, String displayName, String cronKey,
                            List<String> fallbackCrons, String enabledKey, String zone,
                            ManualTrigger manualTrigger) {}

  private static final List<Definition> DEFINITIONS = List.of(
      new Definition("scheduler.public-ip.lock-name", "공인 IP 변경 감지",
          "scheduler.public-ip.cron-expression", List.of(), null, "UTC", null),
      new Definition("scheduler.hotdeal.lock-name", "핫딜 수집",
          "scheduler.hotdeal.cron-expression", List.of(), "scheduler.hotdeal.enabled", "UTC", null),
      new Definition("scheduler.log-cleaner.lock-name", "로그 정리",
          "scheduler.log-cleaner.cron-expression", List.of(), null, "UTC", null),
      new Definition("scheduler.jira.lock-name", "Jira 이슈 수집",
          "scheduler.jira.cron-expression", List.of(), "scheduler.jira.enabled", "UTC", null),
      // Claude 만 yml 에 cron 이 없다 — ClaudeTokenRefreshScheduler 의 하드코딩값을 그대로 옮긴다
      new Definition("scheduler.claude.lock-name", "Claude 토큰 갱신",
          null, List.of("0 5 6 * * ?", "0 5 11 * * ?", "0 5 16 * * ?"),
          "scheduler.claude.enabled", "Asia/Seoul", null),
      // 주식(KIS) 수집 — cron 은 yml, 타임존은 KST. 2026-09-13 prod 활성화(enabled 는 기동 시 평가).
      // manualTrigger 는 각 *Scheduler 가 부르는 잡과 같다 (StockMasterScheduler 는 MASTER 뒤 HOLIDAY 를 순차 호출)
      new Definition("scheduler.stock-master.lock-name", "주식 마스터·휴장일 갱신",
          "scheduler.stock-master.cron-expression", List.of("0 30 5 * * MON-FRI"), "scheduler.stock-master.enabled", "Asia/Seoul",
          ManualTrigger.stock("MASTER", "HOLIDAY")),
      new Definition("scheduler.stock-daily.lock-name", "주식 일일 증분 수집",
          "scheduler.stock-daily.cron-expression", List.of("0 30 18 * * MON-FRI"), "scheduler.stock-daily.enabled", "Asia/Seoul",
          ManualTrigger.stock("DAILY")),
      new Definition("scheduler.stock-overseas.lock-name", "해외 지표 증분 수집",
          "scheduler.stock-overseas.cron-expression", List.of("0 30 6 * * TUE-SAT"), "scheduler.stock-overseas.enabled", "Asia/Seoul",
          ManualTrigger.stock("OVERSEAS_DAILY")),
      new Definition("scheduler.stock-macro.lock-name", "거시 위험 지표 수집(VIX·미국 국채)",
          "scheduler.stock-macro.cron-expression", List.of("0 35 6,8 * * TUE-SAT"), "scheduler.stock-macro.enabled", "Asia/Seoul",
          ManualTrigger.stock("MACRO")),
      // 사건 피드(GDELT)는 한 클래스가 아침·저녁 두 cron 을 돌리므로 락도 둘, on/off 키는 하나. 둘 다 같은 NEWS 잡이라 화면은 한 버튼으로 묶는다
      new Definition("scheduler.stock-eventfeed.lock-name-am", "사건 피드 수집(아침 시계열)",
          "scheduler.stock-eventfeed.cron-am", List.of("0 40 6 * * TUE-SAT"), "scheduler.stock-eventfeed.enabled", "Asia/Seoul",
          ManualTrigger.stock("NEWS")),
      new Definition("scheduler.stock-eventfeed.lock-name-pm", "사건 피드 수집(저녁 헤드라인)",
          "scheduler.stock-eventfeed.cron-pm", List.of("0 20 19 * * MON-FRI"), "scheduler.stock-eventfeed.enabled", "Asia/Seoul",
          ManualTrigger.stock("NEWS")),
      new Definition("scheduler.stock-weekly.lock-name", "주식 주간 수집(기업행사·계수·재무)",
          "scheduler.stock-weekly.cron-expression", List.of("0 0 3 * * SUN"), "scheduler.stock-weekly.enabled", "Asia/Seoul",
          ManualTrigger.stock("WEEKLY")),
      // AI 시장 판단(advisor) (2026-09-13). ADVISE 는 실제 cron 이 19:30~19:55 5분 간격 6회라 expectedInterval 이 5분으로 잡혀 STALE 오판이
      // 나므로 cronKey 를 null 로 두고 대표 주기(하루 1회)만 표시한다 — 실행 cron 은 스케줄러 클래스가 yml 에서 직접 읽는다
      new Definition("scheduler.advisor-advise.lock-name", "AI 일일 시장 판단",
          null, List.of("0 30 19 * * MON-FRI"), "scheduler.advisor-advise.enabled", "Asia/Seoul",
          ManualTrigger.advisor("ADVISE")),
      new Definition("scheduler.advisor-intraday.lock-name", "AI 장중 점검",
          "scheduler.advisor-intraday.cron-expression", List.of("0 0 12 * * MON-FRI"), "scheduler.advisor-intraday.enabled", "Asia/Seoul",
          ManualTrigger.advisor("INTRADAY")),
      new Definition("scheduler.advisor-morning-check.lock-name", "AI 아침 해외 반영 점검",
          "scheduler.advisor-morning-check.cron-expression", List.of("0 30 7 * * MON-FRI"), "scheduler.advisor-morning-check.enabled", "Asia/Seoul",
          ManualTrigger.advisor("MORNING_CHECK")),
      new Definition("scheduler.advisor-weekly-review.lock-name", "AI 주간 검토(가중치·교훈·보고)",
          "scheduler.advisor-weekly-review.cron-expression", List.of("0 0 8 * * SUN"), "scheduler.advisor-weekly-review.enabled", "Asia/Seoul",
          ManualTrigger.advisor("WEEKLY_REVIEW")));

  /** shedlock 의 lock_at 이 예상 주기의 이 배수를 넘도록 갱신되지 않으면 지연으로 본다. */
  private static final int STALE_MULTIPLIER = 3;

  private final Environment environment;

  /** 카탈로그 항목 1개를 프로파일 해석된 실제 값으로 펼친 것. manualTrigger 는 수동 실행 대상이 아니면 null. */
  public record ResolvedJob(String lockName, String displayName, String cronExpression,
                            Duration expectedInterval, boolean enabled, ManualTrigger manualTrigger) {}

  public List<ResolvedJob> resolveAll() {
    return DEFINITIONS.stream().map(this::resolve).filter(java.util.Objects::nonNull).toList();
  }

  public static int staleMultiplier() {
    return STALE_MULTIPLIER;
  }

  private ResolvedJob resolve(Definition definition) {
    String lockName = environment.getProperty(definition.lockNameKey());
    if (lockName == null || lockName.isBlank()) {
      // 프로파일에 해당 잡 설정이 아예 없으면 카탈로그에서 제외한다
      return null;
    }
    List<String> crons = definition.cronKey() == null
        ? definition.fallbackCrons()
        : resolveCron(definition.cronKey(), definition.fallbackCrons());

    boolean enabled = definition.enabledKey() == null
        || environment.getProperty(definition.enabledKey(), Boolean.class, Boolean.TRUE);

    return new ResolvedJob(lockName, definition.displayName(),
        String.join(", ", crons), expectedInterval(crons, definition.zone()), enabled, definition.manualTrigger());
  }

  private List<String> resolveCron(String cronKey, List<String> fallback) {
    String value = environment.getProperty(cronKey);
    return (value == null || value.isBlank()) ? fallback : List.of(value);
  }

  /**
   * cron 이 여러 개면 가장 짧은 간격을 예상 주기로 삼는다.
   * 해석 실패 시 null 을 반환하고, 호출부는 지연 판정을 건너뛴다 — 못 읽는 cron 때문에
   * 멀쩡한 잡을 STALE 로 표시하는 것이 더 나쁘다.
   */
  private Duration expectedInterval(List<String> crons, String zone) {
    Duration shortest = null;
    for (String cron : crons) {
      try {
        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime base = ZonedDateTime.now(ZoneId.of(zone));
        ZonedDateTime first = expression.next(base);
        ZonedDateTime second = first == null ? null : expression.next(first);
        if (first == null || second == null) {
          continue;
        }
        Duration interval = Duration.between(first, second);
        if (shortest == null || interval.compareTo(shortest) < 0) {
          shortest = interval;
        }
      } catch (Exception e) {
        log.debug("cron 해석 실패 — 지연 판정을 건너뛴다: cron={}, cause={}", cron, e.getMessage());
      }
    }
    return shortest;
  }
}
