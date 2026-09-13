package kr.hvy.blog.modules.stock.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.dto.KisIndexPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 국내업종 현재지수(inquire-index-price) TR ID·응답 필드 실측 + 주식현재가의 prdy_ctrt 필드 확인 (advisor 장중 점검용, Spring 컨텍스트 미사용).
 * KIS_APP_KEY / KIS_APP_SECRET 이 있을 때만 실행된다.
 * <pre>
 * KIS_APP_KEY=... KIS_APP_SECRET=... ./gradlew test --tests "kr.hvy.blog.modules.stock.client.KisIndexPriceManualTest"
 * </pre>
 * 확인 항목: rt_cd=0 인지(아니면 TR ID 가 틀린 것 — msg1 확인), output.bstp_nmix_prpr / bstp_nmix_prdy_ctrt 가 채워지는지.
 */
@Slf4j
class KisIndexPriceManualTest {

  private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
  private static final String APP_KEY = System.getenv("KIS_APP_KEY");
  private static final String APP_SECRET = System.getenv("KIS_APP_SECRET");

  private final RestClient client = RestClient.builder().baseUrl(BASE_URL).build();

  @Test
  void tokenThenIndexPriceAndStockPrice() {
    Assumptions.assumeTrue(StringUtils.isNoneBlank(APP_KEY, APP_SECRET), "KIS_APP_KEY/KIS_APP_SECRET 미설정 → skip");
    KisTokenResponse token = issueToken();
    Assumptions.assumeTrue(StringUtils.isNotBlank(token.accessToken()), "access_token 없음 → 조회 생략");

    for (String code : new String[] {"0001", "1001"}) {
      String body = get(token, KisRestMarketDataAdapter.INDEX_PRICE_PATH, KisRestMarketDataAdapter.INDEX_PRICE_TR_ID, "U", code);
      KisIndexPriceResponse parsed = KisJson.read(body, KisIndexPriceResponse.class);
      log.info("[INDEX_PRICE {}] rt_cd={}, msg_cd={}, msg1={}, prpr={}, ctrt={}, raw(앞 400자)={}", code, parsed.rtCd(), parsed.msgCd(), parsed.msg1(),
          parsed.output() == null ? null : parsed.output().currentValue(), parsed.output() == null ? null : parsed.output().changeRate(),
          StringUtils.abbreviate(body, 400));
    }
    String body = get(token, KisRestMarketDataAdapter.PRICE_PATH, KisRestMarketDataAdapter.PRICE_TR_ID, "J", "005930");
    KisPriceResponse parsed = KisJson.read(body, KisPriceResponse.class);
    log.info("[PRICE 005930] rt_cd={}, prpr={}, prdy_ctrt={}, acml_vol={}", parsed.rtCd(),
        parsed.output() == null ? null : parsed.output().currentPrice(), parsed.output() == null ? null : parsed.output().changeRate(),
        parsed.output() == null ? null : parsed.output().accumulatedVolume());
  }

  private String get(KisTokenResponse token, String path, String trId, String marketDiv, String code) {
    return client.get()
        .uri(builder -> builder.path(path).queryParam("FID_COND_MRKT_DIV_CODE", marketDiv).queryParam("FID_INPUT_ISCD", code).build())
        .header("authorization", "Bearer " + token.accessToken())
        .header("appkey", APP_KEY)
        .header("appsecret", APP_SECRET)
        .header("tr_id", trId)
        .header("custtype", "P")
        .exchange((request, response) -> {
          String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
          log.info("[{}] status={}", trId, response.getStatusCode());
          return responseBody;
        });
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
