package kr.hvy.blog.modules.stock.client;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.hvy.blog.modules.stock.client.dto.KisKsdInfoResponse;
import kr.hvy.blog.modules.stock.client.dto.KisTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 예탁원정보(ksdinfo) 연속조회 실측 (Spring 컨텍스트 미사용). KIS_APP_KEY / KIS_APP_SECRET 이 있을 때만 실행된다.
 * 2026-09-09 운영에서 전 종목 92일 청크가 페이지 상한에 걸려 잘렸는데, 페이지 크기와 "tr_cont=N 만으로 다음 페이지가 오는지(승계 키 없이)" 가
 * 미확인이라 아래를 찍는다: 페이지별 행 수·첫/끝 (record_date, sht_cd)·응답 헤더 tr_cont·직전 페이지와 동일 여부·최상위 JSON 키,
 * 그리고 단일 종목 전 기간 조회의 최소·최대 기준일(기간 상한 유무).
 * <pre>
 * KIS_APP_KEY=... KIS_APP_SECRET=... ./gradlew test --tests "kr.hvy.blog.modules.stock.client.KisKsdInfoManualTest"
 * </pre>
 */
@Slf4j
class KisKsdInfoManualTest {

  private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
  private static final String APP_KEY = System.getenv("KIS_APP_KEY");
  private static final String APP_SECRET = System.getenv("KIS_APP_SECRET");
  private static final int PAGES = 3;

  private final RestClient client = RestClient.builder().baseUrl(BASE_URL).build();

  /** 배당 2024-01-01~03-31 전 종목 3페이지 + 삼성전자 2015~오늘 1페이지. */
  @Test
  void dumpPagingBehavior() {
    Assumptions.assumeTrue(StringUtils.isNoneBlank(APP_KEY, APP_SECRET), "KIS_APP_KEY/KIS_APP_SECRET 미설정 → skip");
    KisTokenResponse token = issueToken();
    Assumptions.assumeTrue(StringUtils.isNotBlank(token.accessToken()), "access_token 없음 → 조회 생략");

    KsdInfoKind kind = KsdInfoKind.DIVIDEND;
    Map<String, String> params = params(kind, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31), "");
    List<Map<String, String>> previous = null;
    for (int page = 1; page <= PAGES; page++) {
      String[] exchanged = call(kind, params, page == 1 ? null : "N", token.accessToken());
      String body = exchanged[0];
      KisKsdInfoResponse parsed = KisJson.read(body, KisKsdInfoResponse.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> top = KisJson.read(body, Map.class);
      List<Map<String, String>> rows = parsed.output1() == null ? List.of() : parsed.output1();
      log.info("[KSD {} page {}] rt_cd={}, msg={}, header tr_cont={}, rows={}, first={}, last={}, sameAsPrevious={}, topKeys={}",
          kind, page, parsed.rtCd(), parsed.msg1(), exchanged[1], rows.size(),
          rows.isEmpty() ? null : summary(rows.get(0)), rows.isEmpty() ? null : summary(rows.get(rows.size() - 1)),
          previous != null && Objects.equals(previous, rows), top.keySet());
      if (!"F".equals(exchanged[1]) && !"M".equals(exchanged[1])) {
        log.info("[KSD {}] 마지막 페이지(tr_cont={}) — 총 {}페이지", kind, exchanged[1], page);
        break;
      }
      previous = rows;
    }

    String[] single = call(kind, params(kind, LocalDate.of(2015, 1, 1), LocalDate.now(), "005930"), null, token.accessToken());
    KisKsdInfoResponse parsed = KisJson.read(single[0], KisKsdInfoResponse.class);
    List<Map<String, String>> rows = parsed.output1() == null ? List.of() : parsed.output1();
    log.info("[KSD {} 005930 2015~] rows={}, header tr_cont={}, minRecordDate={}, maxRecordDate={}", kind, rows.size(), single[1],
        rows.stream().map(r -> r.get("record_date")).filter(Objects::nonNull).min(String::compareTo).orElse(null),
        rows.stream().map(r -> r.get("record_date")).filter(Objects::nonNull).max(String::compareTo).orElse(null));
  }

  private static Map<String, String> params(KsdInfoKind kind, LocalDate from, LocalDate to, String ticker) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("CTS", "");
    params.put("F_DT", KisValues.format(from));
    params.put("T_DT", KisValues.format(to));
    params.put("SHT_CD", ticker);
    params.putAll(kind.getFixedParams());
    return params;
  }

  private static String summary(Map<String, String> row) {
    return row.get("record_date") + "/" + row.get("sht_cd");
  }

  /** 본문과 응답 헤더 tr_cont 를 함께 돌려준다. */
  private String[] call(KsdInfoKind kind, Map<String, String> params, String trCont, String accessToken) {
    return client.get()
        .uri(builder -> {
          builder.path(kind.getPath());
          params.forEach(builder::queryParam);
          return builder.build();
        })
        .header("authorization", "Bearer " + accessToken)
        .header("appkey", APP_KEY)
        .header("appsecret", APP_SECRET)
        .header("tr_id", kind.getTrId())
        .header("custtype", "P")
        .header("tr_cont", trCont == null ? "" : trCont)
        .exchange((request, response) -> new String[]{
            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
            response.getHeaders().getFirst("tr_cont")});
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
