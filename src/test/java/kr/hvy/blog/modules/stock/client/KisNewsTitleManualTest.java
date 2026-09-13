package kr.hvy.blog.modules.stock.client;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.dto.KisNewsTitleResponse;
import kr.hvy.blog.modules.stock.client.dto.KisTokenResponse;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 종합 시황/공시(제목) API 실측 (advisor 뉴스 입력 착수 전제, Spring 컨텍스트 미사용). KIS_APP_KEY / KIS_APP_SECRET 이 있을 때만 실행된다.
 * <pre>
 * KIS_APP_KEY=... KIS_APP_SECRET=... ./gradlew test --tests "kr.hvy.blog.modules.stock.client.KisNewsTitleManualTest"
 * </pre>
 * 확인 항목: ① rt_cd=0 인지(아니면 TR ID·경로가 틀린 것 — msg1 확인, yml kis.news.tr-id/path 조정) ② 응답 배열 키가 output1 인지 output 인지
 * ③ data_dt/data_tm/hts_pbnt_titl_cntt/iscd1 이 채워지는지 ④ 전체 시황(iscd 공백) 한 페이지 건수와 종목 태그 비율 ⑤ tr_cont 헤더(M/F)로 연속조회가 되는지
 * ⑥ 제공사 코드 0 이 전체인지. 결과를 보고 KisProperties.News 기본값과 NewsCollectJob 의 페이지 상한을 조정한다.
 */
@Slf4j
class KisNewsTitleManualTest {

  private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
  private static final String APP_KEY = System.getenv("KIS_APP_KEY");
  private static final String APP_SECRET = System.getenv("KIS_APP_SECRET");

  private final RestClient client = RestClient.builder().baseUrl(BASE_URL).build();
  private final KisProperties.News news = new KisProperties.News();

  @Test
  void tokenThenNewsTitles() {
    Assumptions.assumeTrue(StringUtils.isNoneBlank(APP_KEY, APP_SECRET), "KIS_APP_KEY/KIS_APP_SECRET 미설정 → skip");
    KisTokenResponse token = issueToken();
    Assumptions.assumeTrue(StringUtils.isNotBlank(token.accessToken()), "access_token 없음 → 조회 생략");

    String date = MarketClock.today().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    String time = MarketClock.now().format(DateTimeFormatter.ofPattern("HHmmss"));
    for (String iscd : new String[] {"", "005930"}) {
      String[] result = get(token, iscd, date, time, "");
      KisNewsTitleResponse parsed = KisJson.read(result[0], KisNewsTitleResponse.class);
      log.info("[NEWS iscd='{}'] rt_cd={}, msg_cd={}, msg1={}, tr_cont={}, output1={}, output={}, raw(앞 600자)={}", iscd, parsed.rtCd(), parsed.msgCd(),
          parsed.msg1(), result[1], parsed.output1() == null ? null : parsed.output1().size(), parsed.output() == null ? null : parsed.output().size(),
          StringUtils.abbreviate(result[0], 600));
      long tagged = parsed.rows().stream().filter(r -> StringUtils.isNotBlank(r.iscd1())).count();
      log.info("[NEWS iscd='{}'] rows={}, 종목 태그 있는 행={}, 첫 행={}", iscd, parsed.rows().size(), tagged, parsed.rows().isEmpty() ? null : parsed.rows().getFirst());
    }
  }

  /**
   * GET 1회. 반환 [본문, tr_cont 응답 헤더].
   */
  private String[] get(KisTokenResponse token, String iscd, String date, String time, String trCont) {
    return client.get()
        .uri(builder -> builder.path(news.getPath())
            .queryParam("FID_NEWS_OFER_ENTP_CODE", news.getProviderCode())
            .queryParam("FID_COND_MRKT_CLS_CODE", news.getMarketClsCode())
            .queryParam("FID_INPUT_ISCD", iscd)
            .queryParam("FID_TITL_CNTT", "")
            .queryParam("FID_INPUT_DATE_1", date)
            .queryParam("FID_INPUT_HOUR_1", time)
            .queryParam("FID_RANK_SORT_CLS_CODE", news.getSortCode())
            .queryParam("FID_INPUT_SRNO", "")
            .build())
        .header("authorization", "Bearer " + token.accessToken())
        .header("appkey", APP_KEY)
        .header("appsecret", APP_SECRET)
        .header("tr_id", news.getTrId())
        .header("tr_cont", trCont)
        .header("custtype", "P")
        .exchange((request, response) -> {
          String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
          log.info("[{}] status={}", news.getTrId(), response.getStatusCode());
          return new String[] {responseBody, response.getHeaders().getFirst("tr_cont")};
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
