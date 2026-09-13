package kr.hvy.blog.modules.stock.client;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
 * KIS_APP_KEY=... KIS_APP_SECRET=... ./gradlew test --tests "kr.hvy.blog.modules.stock.client.KisNewsTitleManualTest" -i
 * </pre>
 * 요청 파라미터 조합 3종 × 종목(전체/005930) = 6회를 부른다. 2026-09-13 운영에서 NEWS 잡이 rt_cd=0·40행인데 전부 열흘 넘게 오래된 기사라 0건으로
 * 끝난 뒤, "어느 파라미터가 오래된 구간을 부르는지" 를 한 번에 가르기 위해 변형을 나눴다 — KIS 공식 확인 스크립트(chk_news_title.py)는 필터 8개를 전부
 * 공백으로 보내고, 연속조회는 tr_cont=M 이면 같은 파라미터에 tr_cont=N 만 붙인다(news_title.py). 운영 어댑터는 BLANK 방식이다.
 * <ul>
 *   <li>BLANK: 전부 공백(공식 방식 = 운영 기본)</li>
 *   <li>LEGACY: 2026-09-13 까지 운영이 보내던 값(제공사 0·시장 00·정렬 01·날짜/시각 = 지금) — 오래된 40행이 재현되는지</li>
 *   <li>BLANK_TIME: 필터는 공백, 날짜/시각만 지금 — 날짜/시각이 "이전" 인지 "이후" 인지 판정</li>
 * </ul>
 * 확인 항목(경로·TR ID·응답 필드는 운영 실측으로 이미 확정): ① BLANK 의 첫 행·마지막 행 일시가 최근인지 ② 한 페이지 건수와 종목 태그 비율
 * ③ tr_cont 헤더(M 이면 연속조회 가능 → max-pages 의미 있음) ④ LEGACY 가 오래된 구간을 재현하는지. 페이지당 건수 × max-pages 가 밤사이 건수보다
 * 적으면 scheduler.stock-news 의 cron 을 24시간으로 넓힌다.
 */
@Slf4j
class KisNewsTitleManualTest {

  private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
  private static final String APP_KEY = System.getenv("KIS_APP_KEY");
  private static final String APP_SECRET = System.getenv("KIS_APP_SECRET");

  private final RestClient client = RestClient.builder().baseUrl(BASE_URL).build();
  private final KisProperties.News news = new KisProperties.News();

  /** 요청 파라미터 한 벌 */
  private record Variant(String name, String providerCode, String marketClsCode, String sortCode, String date, String time) {
  }

  @Test
  void tokenThenNewsTitles() {
    Assumptions.assumeTrue(StringUtils.isNoneBlank(APP_KEY, APP_SECRET), "KIS_APP_KEY/KIS_APP_SECRET 미설정 → skip");
    KisTokenResponse token = issueToken();
    Assumptions.assumeTrue(StringUtils.isNotBlank(token.accessToken()), "access_token 없음 → 조회 생략");

    String date = MarketClock.today().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    String time = MarketClock.now().format(DateTimeFormatter.ofPattern("HHmmss"));
    List<Variant> variants = List.of(
        new Variant("BLANK", "", "", "", "", ""),
        new Variant("LEGACY", "0", "00", "01", date, time),
        new Variant("BLANK_TIME", "", "", "", date, time));

    for (Variant v : variants) {
      for (String iscd : new String[] {"", "005930"}) {
        String[] result = get(token, v, iscd, "");
        KisNewsTitleResponse parsed = KisJson.read(result[0], KisNewsTitleResponse.class);
        List<KisNewsTitleResponse.Row> rows = parsed.rows();
        long tagged = rows.stream().filter(r -> StringUtils.isNotBlank(r.iscd1())).count();
        long titled = rows.stream().filter(r -> StringUtils.isNotBlank(r.title())).count();
        long dated = rows.stream().filter(r -> StringUtils.isNoneBlank(r.date(), r.time())).count();
        log.info("[NEWS {} iscd='{}'] rt_cd={}, msg_cd={}, msg1={}, tr_cont={}, output1={}, output={}, rows={}, 제목 있는 행={}, 일시 있는 행={}, 종목 태그 있는 행={}",
            v.name(), iscd, parsed.rtCd(), parsed.msgCd(), parsed.msg1(), result[1],
            parsed.output1() == null ? null : parsed.output1().size(), parsed.output() == null ? null : parsed.output().size(),
            rows.size(), titled, dated, tagged);
        log.info("[NEWS {} iscd='{}'] 첫 행={}, 마지막 행 일시={} {}, raw(앞 600자)={}", v.name(), iscd, rows.isEmpty() ? null : rows.getFirst(),
            rows.isEmpty() ? null : rows.getLast().date(), rows.isEmpty() ? null : rows.getLast().time(), StringUtils.abbreviate(result[0], 600));
      }
    }
  }

  /**
   * GET 1회. 반환 [본문, tr_cont 응답 헤더].
   */
  private String[] get(KisTokenResponse token, Variant v, String iscd, String trCont) {
    return client.get()
        .uri(builder -> builder.path(news.getPath())
            .queryParam("FID_NEWS_OFER_ENTP_CODE", v.providerCode())
            .queryParam("FID_COND_MRKT_CLS_CODE", v.marketClsCode())
            .queryParam("FID_INPUT_ISCD", iscd)
            .queryParam("FID_TITL_CNTT", "")
            .queryParam("FID_INPUT_DATE_1", v.date())
            .queryParam("FID_INPUT_HOUR_1", v.time())
            .queryParam("FID_RANK_SORT_CLS_CODE", v.sortCode())
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
          log.info("[{} {}] status={}", news.getTrId(), v.name(), response.getStatusCode());
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
