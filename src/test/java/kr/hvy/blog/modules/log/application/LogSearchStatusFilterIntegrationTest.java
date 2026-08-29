package kr.hvy.blog.modules.log.application;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import kr.hvy.common.aop.logging.entity.ApiLog;
import kr.hvy.common.aop.logging.entity.SystemLog;
import kr.hvy.common.aop.logging.repository.ApiLogRepository;
import kr.hvy.common.aop.logging.repository.SystemLogRepository;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import kr.hvy.common.core.code.ApiResponseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그 검색의 성공/실패 필터 검증.
 *
 * 두 로그의 판정 방식이 서로 다르다는 것이 이 테스트의 존재 이유다.
 * - 시스템 로그: status 컬럼(SUCC/FAIL)을 등호로 비교한다. 요청은 enum name('SUCCESS')으로 받는다.
 * - API 로그: status 컬럼이 없어 response_status(HTTP 코드 문자열)로 파생 판정한다.
 *   그 값은 ApiLogInterceptor 가 String.valueOf(HttpStatusCode) 로 저장해 "200" 일 수도 "200 OK" 일 수도 있다.
 *
 * 날짜 조건 없이 조회해 status 조건만 분리 검증한다(날짜 경계는 LogSearchTimezoneIntegrationTest 담당).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogSearchStatusFilterIntegrationTest {

  private static final String SYSTEM_SEARCH_URI = "/api/log/admin/system/search";
  private static final String API_SEARCH_URI = "/api/log/admin/api/search";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private SystemLogRepository systemLogRepository;

  @Autowired
  private ApiLogRepository apiLogRepository;

  @MockitoBean
  private RedissonClient redissonClient;

  @BeforeEach
  void setUp() {
    apiLogRepository.deleteAllInBatch();
    systemLogRepository.deleteAllInBatch();
  }

  // ── 시스템 로그 ────────────────────────────────────────────────────────────

  /**
   * 요청은 enum name('SUCCESS')으로 오는데 DB 에는 code('SUCC')가 들어 있다.
   * 그 사이를 MyBatis 타입핸들러(ApiResponseStatusConverter)가 잇는다 — 이 경로가 깨지면
   * 프론트 필터가 조용히 0건을 반환하므로 회귀 방지로 못 박는다.
   */
  @Test
  @DisplayName("시스템 로그: 요청 'SUCCESS' 가 DB 의 'SUCC' 행을 찾아온다")
  void systemLogSuccessRequestMatchesSuccCode() throws Exception {
    systemLogRepository.saveAndFlush(systemLog("sys-ok", ApiResponseStatus.SUCCESS));
    systemLogRepository.saveAndFlush(systemLog("sys-ng", ApiResponseStatus.FAIL));

    searchSystem(Map.of("status", "SUCCESS"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(1))
        .andExpect(jsonPath("$.data.list[0].status").value("SUCC"));
  }

  @Test
  @DisplayName("시스템 로그: 'FAIL' 은 실패 행만 가져온다")
  void systemLogFailRequestReturnsOnlyFailures() throws Exception {
    systemLogRepository.saveAndFlush(systemLog("sys-ok", ApiResponseStatus.SUCCESS));
    systemLogRepository.saveAndFlush(systemLog("sys-ng", ApiResponseStatus.FAIL));

    searchSystem(Map.of("status", "FAIL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(1))
        .andExpect(jsonPath("$.data.list[0].status").value("FAIL"));
  }

  /**
   * 프론트가 DB 코드('SUCC')를 그대로 보내면 안 되는 이유의 실행 가능한 증거.
   * ApiResponseStatus 에 @JsonCreator 가 없고 READ_UNKNOWN_ENUM_VALUES_AS_NULL 도 꺼져 있어
   * 조용히 무시되지 않고 역직렬화가 실패한다.
   */
  @Test
  @DisplayName("시스템 로그: DB 코드 'SUCC' 를 보내면 400 이다 — 프론트는 'SUCCESS' 를 보내야 한다")
  void systemLogRejectsDbCodeAsRequestValue() throws Exception {
    searchSystem(Map.of("status", "SUCC"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("시스템 로그: status 를 안 주면 전체가 나온다")
  void systemLogWithoutStatusReturnsAll() throws Exception {
    systemLogRepository.saveAndFlush(systemLog("sys-ok", ApiResponseStatus.SUCCESS));
    systemLogRepository.saveAndFlush(systemLog("sys-ng", ApiResponseStatus.FAIL));

    searchSystem(Map.of())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(2));
  }

  // ── API 로그 ──────────────────────────────────────────────────────────────

  /**
   * prefix LIKE 가 "200" 과 "200 OK" 두 저장 포맷을 모두 커버한다는 주장 자체의 검증.
   * 이게 깨지면 필터가 절반만 잡는데 화면에서는 알아채기 어렵다.
   */
  @Test
  @DisplayName("API 로그: 2xx·3xx 는 성공이다 — \"200\" 과 \"200 OK\" 두 포맷 모두")
  void apiLogSuccessCoversBothStoredFormats() throws Exception {
    apiLogRepository.saveAndFlush(apiLog("api-200", "200"));
    apiLogRepository.saveAndFlush(apiLog("api-200-ok", "200 OK"));
    apiLogRepository.saveAndFlush(apiLog("api-302", "302"));
    apiLogRepository.saveAndFlush(apiLog("api-404", "404"));

    searchApi(Map.of("status", "SUCCESS"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(3));
  }

  /**
   * NULL 과 빈 문자열이 핵심이다. NOT LIKE 는 NULL 에 NULL 을 내므로
   * 매퍼가 IS NULL 을 따로 잡지 않으면 "응답을 못 받은 호출"이 실패에서 통째로 빠진다.
   */
  @Test
  @DisplayName("API 로그: 2xx·3xx 가 아니면 전부 실패다 — NULL·빈 문자열 포함")
  void apiLogFailureIncludesNullAndBlank() throws Exception {
    apiLogRepository.saveAndFlush(apiLog("api-200", "200"));
    apiLogRepository.saveAndFlush(apiLog("api-404", "404"));
    apiLogRepository.saveAndFlush(apiLog("api-500", "500 INTERNAL_SERVER_ERROR"));
    apiLogRepository.saveAndFlush(apiLog("api-null", null));
    apiLogRepository.saveAndFlush(apiLog("api-blank", ""));

    searchApi(Map.of("status", "FAIL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(4));
  }

  @Test
  @DisplayName("API 로그: 성공과 실패를 합치면 전체가 된다 — 어느 쪽에도 안 걸리는 행이 없다")
  void apiLogSuccessAndFailurePartitionAllRows() throws Exception {
    apiLogRepository.saveAndFlush(apiLog("api-200", "200"));
    apiLogRepository.saveAndFlush(apiLog("api-302-found", "302 FOUND"));
    apiLogRepository.saveAndFlush(apiLog("api-404", "404"));
    apiLogRepository.saveAndFlush(apiLog("api-null", null));

    searchApi(Map.of("status", "SUCCESS"))
        .andExpect(jsonPath("$.data.totalCount").value(2));
    searchApi(Map.of("status", "FAIL"))
        .andExpect(jsonPath("$.data.totalCount").value(2));
    searchApi(Map.of())
        .andExpect(jsonPath("$.data.totalCount").value(4));
  }

  @Test
  @DisplayName("API 로그: 기존 responseStatus 완전일치 검색과 함께 걸 수 있다")
  void apiLogStatusCombinesWithExactResponseStatus() throws Exception {
    apiLogRepository.saveAndFlush(apiLog("api-404", "404"));
    apiLogRepository.saveAndFlush(apiLog("api-500", "500"));

    Map<String, Object> params = new HashMap<>();
    params.put("status", "FAIL");
    params.put("responseStatus", "404");

    searchApi(params)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(1));
  }

  // ── 헬퍼 ─────────────────────────────────────────────────────────────────

  /** 날짜 조건 없이 status 만으로 조회한다 — 이 테스트의 관심사는 status 조건 하나다. */
  private org.springframework.test.web.servlet.ResultActions searchSystem(Map<String, Object> extra)
      throws Exception {
    return mockMvc.perform(post(SYSTEM_SEARCH_URI)
        .with(user("admin").authorities(() -> "ROLE_ADMIN"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(searchBody(extra)));
  }

  private org.springframework.test.web.servlet.ResultActions searchApi(Map<String, Object> extra)
      throws Exception {
    return mockMvc.perform(post(API_SEARCH_URI)
        .with(user("admin").authorities(() -> "ROLE_ADMIN"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(searchBody(extra)));
  }

  private String searchBody(Map<String, Object> extra) {
    Map<String, Object> body = new HashMap<>();
    body.put("page", 0);
    body.put("pageSize", 10);
    body.putAll(extra);
    return objectMapper.writeValueAsString(body);
  }

  private SystemLog systemLog(String traceId, ApiResponseStatus status) {
    return SystemLog.builder()
        .traceId(traceId)
        .spanId(spanId(traceId))
        .requestUri("/api/test/" + traceId)
        .controllerName("TestController")
        .methodName("search")
        .httpMethodType("GET")
        .paramData("{}")
        .responseBody("{}")
        .stackTrace("")
        .remoteAddr("127.0.0.1")
        .processTime(10L)
        .status(status)
        .created(EventLogEntity.builder()
            .at(Instant.parse("2026-03-08T01:00:00Z"))
            .by("tester")
            .build())
        .build();
  }

  /** responseStatus 를 인자로 받는다 — null·빈 문자열까지 넣어야 판정식을 제대로 검증한다. */
  private ApiLog apiLog(String traceId, String responseStatus) {
    return ApiLog.builder()
        .traceId(traceId)
        .spanId(spanId(traceId))
        .requestUri("/external/" + traceId)
        .httpMethodType("GET")
        .requestHeader("{}")
        .requestParam("{}")
        .requestBody("{}")
        .responseStatus(responseStatus)
        .responseBody("{}")
        .processTime(15L)
        .created(EventLogEntity.builder()
            .at(Instant.parse("2026-03-08T01:00:00Z"))
            .by("tester")
            .build())
        .build();
  }

  private String spanId(String traceId) {
    return "s" + Integer.toUnsignedString(traceId.hashCode(), 16);
  }
}
