package kr.hvy.blog.modules.stock.client;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.dto.KisMarketInvestorResponse;
import kr.hvy.blog.modules.stock.client.dto.KisTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 시장별 투자자매매동향(일별) FHPTJ04040000 실측 (Spring 컨텍스트 미사용). KIS_APP_KEY / KIS_APP_SECRET 이 있을 때만 실행된다.
 * <b>백필 전 필수</b>: KSP 와 KSQ 응답이 다른지(파라미터가 무시되면 코스닥 행에 코스피 값이 조용히 들어간다), 호출당 일수, 금액 단위(HTS 0404 대조).
 * <pre>
 * KIS_APP_KEY=... KIS_APP_SECRET=... ./gradlew test --tests "kr.hvy.blog.modules.stock.client.KisMarketInvestorManualTest"
 * </pre>
 */
@Slf4j
class KisMarketInvestorManualTest {

  private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
  private static final String APP_KEY = System.getenv("KIS_APP_KEY");
  private static final String APP_SECRET = System.getenv("KIS_APP_SECRET");

  private final RestClient client = RestClient.builder().baseUrl(BASE_URL).build();

  /** 최근 영업일 추정치(오늘-1)로 KSP/0001, KSQ/1001, KSQ/0001 세 조합을 호출해 응답 상이 여부·일수·단위를 로그로 남긴다. */
  @Test
  void compareMarketsAndIndexCodes() {
    Assumptions.assumeTrue(StringUtils.isNoneBlank(APP_KEY, APP_SECRET), "KIS_APP_KEY/KIS_APP_SECRET 미설정 → skip");
    KisTokenResponse token = issueToken();
    Assumptions.assumeTrue(StringUtils.isNotBlank(token.accessToken()), "access_token 없음 → 조회 생략");

    String base = KisValues.format(LocalDate.now().minusDays(1));
    for (String[] combo : List.of(new String[]{"KSP", "0001"}, new String[]{"KSQ", "1001"}, new String[]{"KSQ", "0001"})) {
      String body = client.get()
          .uri(builder -> builder.path(KisRestMarketDataAdapter.MARKET_INVESTOR_PATH)
              .queryParam("FID_COND_MRKT_DIV_CODE", "U")
              .queryParam("FID_INPUT_ISCD", combo[1])
              .queryParam("FID_INPUT_DATE_1", base)
              .queryParam("FID_INPUT_ISCD_1", combo[0])
              .queryParam("FID_INPUT_DATE_2", base)
              .queryParam("FID_INPUT_ISCD_2", combo[1])
              .build())
          .header("authorization", "Bearer " + token.accessToken())
          .header("appkey", APP_KEY)
          .header("appsecret", APP_SECRET)
          .header("tr_id", KisRestMarketDataAdapter.MARKET_INVESTOR_TR_ID)
          .header("custtype", "P")
          .exchange((request, response) -> new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
      KisMarketInvestorResponse parsed = KisJson.read(body, KisMarketInvestorResponse.class);
      log.info("[MKT_INV {}/{}] rt_cd={}, msg={}, raw(앞 400자)={}", combo[0], combo[1], parsed.rtCd(), parsed.msg1(),
          StringUtils.abbreviate(body, 400));
      if (parsed.output() != null && !parsed.output().isEmpty()) {
        KisMarketInvestorResponse.Row first = parsed.output().get(0);
        KisMarketInvestorResponse.Row last = parsed.output().get(parsed.output().size() - 1);
        log.info("[MKT_INV {}/{}] rows={}, first={} frgn={} orgn={} prsn={} pension={}, last={}", combo[0], combo[1],
            parsed.output().size(), first.tradeDate(), first.foreignNetAmt(), first.institutionNetAmt(), first.individualNetAmt(),
            first.pensionNetAmt(), last.tradeDate());
      } else {
        log.warn("[MKT_INV {}/{}] output 비어 있음 — 경로·파라미터 확인. body={}", combo[0], combo[1], StringUtils.abbreviate(body, 800));
      }
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
