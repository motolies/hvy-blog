package kr.hvy.blog.modules.stock.client;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.dto.KisDailyChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * KIS 실전 API 수동 진단 테스트 (Spring 컨텍스트 미사용).
 * <p>
 * 환경변수 KIS_APP_KEY / KIS_APP_SECRET 이 있을 때만 실행되며, 없으면 skip 된다.
 * 토큰 발급 → 삼성전자(005930) 일봉 100건 조회를 재현해 앱키 유효성, 응답 필드, 소급 범위를 눈으로 확인한다.
 * <p>
 * <b>주의:</b> KIS 토큰 발급은 1분 1회 제한이다. 앱이 같은 앱키로 떠 있으면 1분 안에 재발급이 거부될 수 있다.
 * <pre>
 * KIS_APP_KEY=... KIS_APP_SECRET=... ./gradlew test --tests "kr.hvy.blog.modules.stock.client.KisDailyChartManualTest"
 * </pre>
 */
@Slf4j
class KisDailyChartManualTest {

  private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
  private static final String APP_KEY = System.getenv("KIS_APP_KEY");
  private static final String APP_SECRET = System.getenv("KIS_APP_SECRET");

  private final RestClient client = RestClient.builder().baseUrl(BASE_URL).build();

  /** 토큰 발급 후 삼성전자 일봉(원주가) 최근 100건을 조회해 결과를 로그로 남긴다. */
  @Test
  void tokenThenSamsungDailyChart() {
    Assumptions.assumeTrue(StringUtils.isNoneBlank(APP_KEY, APP_SECRET), "KIS_APP_KEY/KIS_APP_SECRET 미설정 → skip");

    KisTokenResponse token = issueToken();
    log.info("[TOKEN] type={}, expires_in={}s, expired_at={}", token.tokenType(), token.expiresIn(), token.accessTokenExpired());
    Assumptions.assumeTrue(StringUtils.isNotBlank(token.accessToken()), "access_token 없음 → 조회 생략");

    LocalDate end = LocalDate.now();
    LocalDate start = end.minusDays(140);
    String body = client.get()
        .uri(builder -> builder.path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
            .queryParam("FID_INPUT_ISCD", "005930")
            .queryParam("FID_INPUT_DATE_1", KisValues.format(start))
            .queryParam("FID_INPUT_DATE_2", KisValues.format(end))
            .queryParam("FID_PERIOD_DIV_CODE", "D")
            .queryParam("FID_ORG_ADJ_PRC", "1")
            .build())
        .header("authorization", "Bearer " + token.accessToken())
        .header("appkey", APP_KEY)
        .header("appsecret", APP_SECRET)
        .header("tr_id", "FHKST03010100")
        .header("custtype", "P")
        .exchange((request, response) -> {
          String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
          log.info("[CHART] status={}, tr_cont={}", response.getStatusCode(), response.getHeaders().getFirst("tr_cont"));
          return responseBody;
        });

    KisDailyChartResponse chart = KisJson.read(body, KisDailyChartResponse.class);
    log.info("[CHART] rt_cd={}, msg_cd={}, msg1={}", chart.rtCd(), chart.msgCd(), chart.msg1());
    if (chart.output2() != null && !chart.output2().isEmpty()) {
      KisDailyChartResponse.Candle latest = chart.output2().get(0);
      KisDailyChartResponse.Candle oldest = chart.output2().get(chart.output2().size() - 1);
      log.info("[CHART] rows={}, latest={} close={} vol={}, oldest={} close={}, flng/prtt/mod 샘플={}/{}/{}",
          chart.output2().size(), latest.tradeDate(), latest.close(), latest.volume(),
          oldest.tradeDate(), oldest.close(), latest.flngClsCode(), latest.splitRate(), latest.modYn());
    } else {
      log.warn("[CHART] output2 비어 있음. body={}", StringUtils.abbreviate(body, 500));
    }
  }

  private KisTokenResponse issueToken() {
    Map<String, String> body = Map.of(
        "grant_type", "client_credentials",
        "appkey", APP_KEY,
        "appsecret", APP_SECRET);
    return client.post()
        .uri("/oauth2/tokenP")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange((request, response) -> {
          String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
          log.info("[TOKEN] status={}", response.getStatusCode());
          if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("[TOKEN] 발급 실패 body={}", responseBody);
            return new KisTokenResponse(null, null, null, null);
          }
          return KisJson.read(responseBody, KisTokenResponse.class);
        });
  }
}
