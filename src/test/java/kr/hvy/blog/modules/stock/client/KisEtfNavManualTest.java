package kr.hvy.blog.modules.stock.client;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.dto.KisEtfNavResponse;
import kr.hvy.blog.modules.stock.client.dto.KisTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * ETF NAV 비교추이(일) FHPST02440200 실측 (Spring 컨텍스트 미사용). KIS_APP_KEY / KIS_APP_SECRET 이 있을 때만 실행된다.
 * 확인 항목: output 키명이 DTO 와 맞는지, 100건 창의 소급 범위, 괴리율(dprt) 부호, NAV 소수 자릿수.
 * <pre>
 * KIS_APP_KEY=... KIS_APP_SECRET=... ./gradlew test --tests "kr.hvy.blog.modules.stock.client.KisEtfNavManualTest"
 * </pre>
 */
@Slf4j
class KisEtfNavManualTest {

  private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
  private static final String APP_KEY = System.getenv("KIS_APP_KEY");
  private static final String APP_SECRET = System.getenv("KIS_APP_SECRET");
  private static final String KODEX_200 = "069500";

  private final RestClient client = RestClient.builder().baseUrl(BASE_URL).build();

  /** 토큰 발급 후 KODEX 200 의 NAV 비교추이 최근 140일 창을 조회해 원문과 파싱 결과를 로그로 남긴다. */
  @Test
  void tokenThenEtfNavDailyTrend() {
    Assumptions.assumeTrue(StringUtils.isNoneBlank(APP_KEY, APP_SECRET), "KIS_APP_KEY/KIS_APP_SECRET 미설정 → skip");
    KisTokenResponse token = issueToken();
    Assumptions.assumeTrue(StringUtils.isNotBlank(token.accessToken()), "access_token 없음 → 조회 생략");

    LocalDate end = LocalDate.now();
    LocalDate start = end.minusDays(140);
    String body = client.get()
        .uri(builder -> builder.path(KisRestMarketDataAdapter.ETF_NAV_PATH)
            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
            .queryParam("FID_INPUT_ISCD", KODEX_200)
            .queryParam("FID_INPUT_DATE_1", KisValues.format(start))
            .queryParam("FID_INPUT_DATE_2", KisValues.format(end))
            .build())
        .header("authorization", "Bearer " + token.accessToken())
        .header("appkey", APP_KEY)
        .header("appsecret", APP_SECRET)
        .header("tr_id", KisRestMarketDataAdapter.ETF_NAV_TR_ID)
        .header("custtype", "P")
        .exchange((request, response) -> {
          String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
          log.info("[ETF_NAV] status={}, raw(앞 600자)={}", response.getStatusCode(), StringUtils.abbreviate(responseBody, 600));
          return responseBody;
        });

    KisEtfNavResponse parsed = KisJson.read(body, KisEtfNavResponse.class);
    log.info("[ETF_NAV] rt_cd={}, msg_cd={}, msg1={}", parsed.rtCd(), parsed.msgCd(), parsed.msg1());
    if (parsed.output() != null && !parsed.output().isEmpty()) {
      KisEtfNavResponse.Row latest = parsed.output().get(0);
      KisEtfNavResponse.Row oldest = parsed.output().get(parsed.output().size() - 1);
      log.info("[ETF_NAV] rows={}, latest={} close={} nav={} dprt={} navDiff={}, oldest={} close={} nav={}",
          parsed.output().size(), latest.tradeDate(), latest.close(), latest.nav(), latest.disparityRate(), latest.navDiff(),
          oldest.tradeDate(), oldest.close(), oldest.nav());
    } else {
      log.warn("[ETF_NAV] output 비어 있음 — 키명이 output 이 아니거나 파라미터 오류. body={}", StringUtils.abbreviate(body, 800));
    }
  }

  private KisTokenResponse issueToken() {
    Map<String, String> body = Map.of("grant_type", "client_credentials", "appkey", APP_KEY, "appsecret", APP_SECRET);
    return client.post()
        .uri("/oauth2/tokenP")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange((request, response) -> {
          String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
          if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("[TOKEN] 발급 실패 body={}", responseBody);
            return new KisTokenResponse(null, null, null, null);
          }
          return KisJson.read(responseBody, KisTokenResponse.class);
        });
  }
}
