package kr.hvy.blog.modules.hotdeal.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

@DisplayName("BrowserProperties - 타임아웃 계층 검증")
class BrowserPropertiesTest {

  @Test
  @DisplayName("기본값은 타임아웃 계층 불변식을 만족한다")
  void validateTimeoutHierarchy_기본값_위반없음() {
    List<String> violations = new BrowserProperties().validateTimeoutHierarchy();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("application.yml 의 browser 블록이 바인딩되고 불변식을 만족한다")
  void applicationYml_바인딩_위반없음() throws IOException {
    // 스프링 컨텍스트(Testcontainers) 없이 실제 yml 만 읽어 바인딩한다
    StandardEnvironment environment = new StandardEnvironment();
    List<PropertySource<?>> sources = new YamlPropertySourceLoader()
        .load("application", new ClassPathResource("application.yml"));
    sources.forEach(source -> environment.getPropertySources().addLast(source));

    BrowserProperties properties = Binder.get(environment)
        .bind("browser", BrowserProperties.class)
        .orElseThrow(() -> new IllegalStateException("browser 블록 바인딩 실패"));

    assertThat(properties.getContentUrl()).endsWith("/content");
    // yml 에는 초로 적혀 있고 @DurationUnit(SECONDS) 로 해석된다
    assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(135));
    assertThat(properties.getSessionTimeout()).isEqualTo(Duration.ofSeconds(120));
    assertThat(properties.getLaunchTimeout()).isEqualTo(Duration.ofSeconds(60));
    assertThat(properties.validateTimeoutHierarchy()).isEmpty();
  }

  @Test
  @DisplayName("세션이 launch+goto+selector 합보다 짧으면 위반으로 잡는다")
  void validateTimeoutHierarchy_세션부족() {
    // 진단 문서의 권장 조합(session 90s / launch 120s) - 세션이 먼저 끊겨 launch 상향이 무의미해진다
    BrowserProperties properties = new BrowserProperties();
    properties.setSessionTimeout(Duration.ofSeconds(90));
    properties.setLaunchTimeout(Duration.ofSeconds(120));
    properties.setRequestTimeout(Duration.ofSeconds(200));

    List<String> violations = properties.validateTimeoutHierarchy();

    assertThat(violations).hasSize(1);
    assertThat(violations.get(0)).contains("sessionTimeout");
  }

  @Test
  @DisplayName("요청 타임아웃이 세션보다 짧으면 위반으로 잡는다")
  void validateTimeoutHierarchy_요청타임아웃부족() {
    // 수정 전 상황(Java 40초) - 클라이언트가 먼저 끊어 browserless 슬롯이 낭비된다
    BrowserProperties properties = new BrowserProperties();
    properties.setRequestTimeout(Duration.ofSeconds(40));

    List<String> violations = properties.validateTimeoutHierarchy();

    assertThat(violations).hasSize(1);
    assertThat(violations.get(0)).contains("requestTimeout");
  }
}
