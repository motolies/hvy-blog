package kr.hvy.blog.modules.hotdeal.client;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.stereotype.Component;

/**
 * browserless(Chromium 사이드카) 호출 설정.
 *
 * browserless v2 는 서버 전역 실행 옵션이 없어 puppeteer 브라우저 기동 타임아웃을
 * 요청 단위 launch 파라미터로만 올릴 수 있다 (upstream issue #4946).
 *
 * requestTimeout > sessionTimeout >= launchTimeout + gotoTimeout + selectorTimeout 순서를 지켜야
 * 클라이언트가 먼저 끊어 browserless 의 동시 실행 슬롯을 낭비하는 일이 없다.
 *
 * 설정값은 초 단위로 적는다(단위 접미사 s/m 도 허용). browserless/puppeteer 로 전송할 때만
 * toMillis() 로 변환하며, API 스펙이 밀리초이므로 이 변환을 생략하면 안 된다.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "browser")
public class BrowserProperties {

  /**
   * browserless /content 엔드포인트. 쿼리스트링 없이 지정한다
   */
  private String contentUrl;

  /**
   * TCP 연결 타임아웃
   */
  @DurationUnit(ChronoUnit.SECONDS)
  private Duration connectTimeout = Duration.ofSeconds(10);

  /**
   * Java HttpClient 응답 대기 타임아웃. sessionTimeout 보다 길어야 한다
   */
  @DurationUnit(ChronoUnit.SECONDS)
  private Duration requestTimeout = Duration.ofSeconds(135);

  /**
   * browserless 세션 전체 상한. 쿼리 파라미터 timeout 으로 전달
   */
  @DurationUnit(ChronoUnit.SECONDS)
  private Duration sessionTimeout = Duration.ofSeconds(120);

  /**
   * puppeteer 브라우저 기동 타임아웃. launch.timeout 으로 전달, 미지정 시 기본 30초
   */
  @DurationUnit(ChronoUnit.SECONDS)
  private Duration launchTimeout = Duration.ofSeconds(60);

  /**
   * page.goto 타임아웃
   */
  @DurationUnit(ChronoUnit.SECONDS)
  private Duration gotoTimeout = Duration.ofSeconds(30);

  /**
   * waitForSelector 타임아웃
   */
  @DurationUnit(ChronoUnit.SECONDS)
  private Duration selectorTimeout = Duration.ofSeconds(25);

  /**
   * 기동 시 타임아웃 계층 역전을 경고한다.
   * 스크래퍼 설정 오류로 블로그 전체 기동이 막히면 안 되므로 예외를 던지지 않는다.
   */
  @PostConstruct
  public void warnOnTimeoutViolation() {
    validateTimeoutHierarchy().forEach(message -> log.warn("browserless 타임아웃 설정 경고: {}", message));
  }

  /**
   * 타임아웃 계층 위반 목록을 반환한다. 위반이 없으면 빈 리스트.
   */
  public List<String> validateTimeoutHierarchy() {
    List<String> violations = new ArrayList<>();
    Duration browserWorstCase = launchTimeout.plus(gotoTimeout).plus(selectorTimeout);

    if (sessionTimeout.compareTo(browserWorstCase) < 0) {
      violations.add("sessionTimeout(%d초) < launch+goto+selector(%d초) - 세션이 먼저 끊겨 launchTimeout 상향이 무의미해진다"
          .formatted(sessionTimeout.toSeconds(), browserWorstCase.toSeconds()));
    }
    if (requestTimeout.compareTo(sessionTimeout) <= 0) {
      violations.add("requestTimeout(%d초) <= sessionTimeout(%d초) - 클라이언트가 먼저 끊어 browserless 슬롯이 낭비된다"
          .formatted(requestTimeout.toSeconds(), sessionTimeout.toSeconds()));
    }
    return violations;
  }
}
