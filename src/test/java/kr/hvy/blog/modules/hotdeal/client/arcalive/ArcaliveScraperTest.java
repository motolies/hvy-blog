package kr.hvy.blog.modules.hotdeal.client.arcalive;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import kr.hvy.blog.modules.hotdeal.client.BrowserProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@DisplayName("ArcaliveScraper - browserless 요청 조립")
class ArcaliveScraperTest {

  /**
   * browserless src/utils.ts 의 isBase64 정규식.
   * 이 조건을 만족해야 launch 파라미터가 실제로 디코딩되어 적용된다.
   */
  private static final Pattern BROWSERLESS_BASE64 =
      Pattern.compile("^([0-9a-zA-Z+/]{4})*(([0-9a-zA-Z+/]{2}==)|([0-9a-zA-Z+/]{3}=))?$");

  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * 기본값이 채워진 BrowserProperties 를 만든다. (스프링 컨텍스트 없이 순수 객체 사용)
   */
  private BrowserProperties defaultProperties() {
    BrowserProperties properties = new BrowserProperties();
    properties.setContentUrl("http://chromium:3000/content");
    return properties;
  }

  /**
   * 요청 본문 조립만 검증하므로 실제 호출이 일어나지 않는 기본 RestClient 로 충분하다.
   */
  private ArcaliveScraper newScraper() {
    return new ArcaliveScraper(defaultProperties(), objectMapper, RestClient.create());
  }

  @Test
  @DisplayName("stealth/timeout/launch 세 파라미터를 모두 쿼리로 전달한다")
  void buildContentUri_전달파라미터() {
    URI uri = ArcaliveScraper.buildContentUri(defaultProperties());

    assertThat(uri.getRawQuery())
        .contains("stealth=true")
        .contains("timeout=120000")
        .contains("launch=");
  }

  /**
   * 설정은 초 단위이지만 browserless/puppeteer 는 밀리초를 받는다.
   * 아래 테스트들이 기대하는 60000/120000/30000/25000 은 초 설정(60/120/30/25)이
   * toMillis() 로 변환된 결과이며, 변환이 누락되면 즉시 실패한다.
   */
  @Test
  @DisplayName("launch 값이 browserless isBase64 규칙을 만족하고 밀리초 JSON 으로 복원된다")
  void buildContentUri_launch값_base64왕복() {
    URI uri = ArcaliveScraper.buildContentUri(defaultProperties());

    String rawLaunch = extractRawQueryParam(uri, "launch");
    String base64 = URLDecoder.decode(rawLaunch, StandardCharsets.UTF_8);

    // browserless 가 base64 로 인정하지 않으면 원문 문자열로 취급되어 launch 가 조용히 무시된다
    assertThat(BROWSERLESS_BASE64.matcher(base64).matches()).isTrue();

    String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    assertThat(decoded).isEqualTo("{\"timeout\":60000}");
  }

  @Test
  @DisplayName("쿼리에 raw '+' 가 남지 않는다 - Node URLSearchParams 가 공백으로 해석하기 때문")
  void buildContentUri_raw플러스없음() {
    URI uri = ArcaliveScraper.buildContentUri(defaultProperties());

    assertThat(uri.getRawQuery()).doesNotContain("+");
  }

  @Test
  @DisplayName("설정 URL 에 같은 쿼리가 이미 있으면 중복 없이 대체하고 다른 키는 보존한다")
  void buildContentUri_중복키대체() {
    BrowserProperties properties = defaultProperties();
    properties.setContentUrl("http://chromium:3000/content?timeout=5000&token=abc");

    URI uri = ArcaliveScraper.buildContentUri(properties);

    String rawQuery = uri.getRawQuery();
    assertThat(rawQuery).doesNotContain("timeout=5000");
    assertThat(countOccurrences(rawQuery, "timeout=")).isEqualTo(1);
    assertThat(rawQuery).contains("timeout=120000");
    assertThat(rawQuery).contains("token=abc");
  }

  @Test
  @DisplayName("base64 결과에 '+' 가 포함되면 퍼센트 인코딩한다")
  void encodeLaunchParam_플러스_퍼센트인코딩() {
    // {"a":"~~~"} -> eyJhIjoifn5+In0= ('+' 포함)
    String encoded = ArcaliveScraper.encodeLaunchParam("{\"a\":\"~~~\"}");

    assertThat(encoded).contains("%2B");
    assertThat(encoded).doesNotContain("+");
  }

  @Test
  @DisplayName("URL 에 큰따옴표가 있어도 유효한 JSON 본문을 만든다")
  void buildRequestBody_따옴표포함URL() throws JsonProcessingException {
    ArcaliveScraper scraper = newScraper();
    String targetUrl = "https://arca.live/b/\"quoted\"?p=1";

    String body = scraper.buildRequestBody(targetUrl);

    JsonNode node = objectMapper.readTree(body);
    assertThat(node.get("url").asText()).isEqualTo(targetUrl);
  }

  @Test
  @DisplayName("설정된 goto/selector 타임아웃과 셀렉터를 본문에 반영한다")
  void buildRequestBody_타임아웃반영() throws JsonProcessingException {
    ArcaliveScraper scraper = newScraper();

    JsonNode node = objectMapper.readTree(scraper.buildRequestBody("https://arca.live/b/hotdeal?p=1"));

    assertThat(node.get("gotoOptions").get("waitUntil").asText()).isEqualTo("networkidle0");
    assertThat(node.get("gotoOptions").get("timeout").asInt()).isEqualTo(30000);
    assertThat(node.get("waitForSelector").get("selector").asText()).isEqualTo("div.vrow.hybrid");
    assertThat(node.get("waitForSelector").get("timeout").asInt()).isEqualTo(25000);
  }

  /**
   * 인코딩된 상태의 쿼리 파라미터 값을 그대로 꺼낸다. (URI.getQuery 는 디코딩되므로 사용 불가)
   */
  private String extractRawQueryParam(URI uri, String key) {
    for (String pair : uri.getRawQuery().split("&")) {
      if (pair.startsWith(key + "=")) {
        return pair.substring(key.length() + 1);
      }
    }
    throw new IllegalArgumentException("쿼리 파라미터 없음: " + key);
  }

  private int countOccurrences(String text, String target) {
    int count = 0;
    int index = text.indexOf(target);
    while (index >= 0) {
      count++;
      index = text.indexOf(target, index + target.length());
    }
    return count;
  }
}
