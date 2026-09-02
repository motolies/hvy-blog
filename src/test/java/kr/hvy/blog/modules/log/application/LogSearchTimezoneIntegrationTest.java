package kr.hvy.blog.modules.log.application;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import kr.hvy.common.core.time.ClientTimeZoneResolver;
import kr.hvy.common.aop.logging.entity.ApiLog;
import kr.hvy.common.aop.logging.entity.SystemLog;
import kr.hvy.common.aop.logging.repository.ApiLogRepository;
import kr.hvy.common.aop.logging.repository.SystemLogRepository;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import kr.hvy.common.core.code.ApiResponseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogSearchTimezoneIntegrationTest {

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

  @Test
  void systemLogSearchUsesBrowserTimezoneBoundaries() throws Exception {
    systemLogRepository.saveAndFlush(createSystemLog("excluded-before", Instant.parse("2026-03-07T14:59:59Z")));
    systemLogRepository.saveAndFlush(createSystemLog("included-start", Instant.parse("2026-03-07T15:00:00Z")));
    systemLogRepository.saveAndFlush(createSystemLog("included-end", Instant.parse("2026-03-08T14:59:59Z")));
    systemLogRepository.saveAndFlush(createSystemLog("excluded-after", Instant.parse("2026-03-08T15:00:00Z")));

    mockMvc.perform(post("/api/log/admin/system/search")
            .with(user("admin").authorities(() -> "ROLE_ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .header(ClientTimeZoneResolver.TIMEZONE_HEADER, "Asia/Seoul")
            .header(ClientTimeZoneResolver.OFFSET_HEADER, "540")
            .content(searchRequestBody(wholeDay(LocalDate.of(2026, 3, 8)), endOfDay(LocalDate.of(2026, 3, 8)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(2))
        .andExpect(jsonPath("$.data.list[0].traceId").value("included-end"))
        .andExpect(jsonPath("$.data.list[1].traceId").value("included-start"));
  }

  @Test
  void apiLogSearchUsesBrowserTimezoneBoundaries() throws Exception {
    apiLogRepository.saveAndFlush(createApiLog("excluded-before", Instant.parse("2026-03-07T14:59:59Z")));
    apiLogRepository.saveAndFlush(createApiLog("included-start", Instant.parse("2026-03-07T15:00:00Z")));
    apiLogRepository.saveAndFlush(createApiLog("included-end", Instant.parse("2026-03-08T14:59:59Z")));
    apiLogRepository.saveAndFlush(createApiLog("excluded-after", Instant.parse("2026-03-08T15:00:00Z")));

    mockMvc.perform(post("/api/log/admin/api/search")
            .with(user("admin").authorities(() -> "ROLE_ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .header(ClientTimeZoneResolver.TIMEZONE_HEADER, "Asia/Seoul")
            .header(ClientTimeZoneResolver.OFFSET_HEADER, "540")
            .content(searchRequestBody(wholeDay(LocalDate.of(2026, 3, 8)), endOfDay(LocalDate.of(2026, 3, 8)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(2))
        .andExpect(jsonPath("$.data.list[0].traceId").value("included-end"))
        .andExpect(jsonPath("$.data.list[1].traceId").value("included-start"));
  }

  /**
   * "24시간" 프리셋 — 자정을 갓 넘긴 시점에도 어제 저녁 기록이 잡힌다.
   * 같은 데이터를 날짜 필터(오늘 하루)로 조회하면 1건뿐이라는 대비까지 함께 검증한다 —
   * 이 차이가 datetime 필터를 도입한 이유다.
   */
  @Test
  void systemLogSearchSupportsSlidingLast24Hours() throws Exception {
    // KST 기준 조회 시점을 2026-03-08 00:30 으로 두고 24시간을 거슬러 올라간다
    systemLogRepository.saveAndFlush(createSystemLog("too-old", Instant.parse("2026-03-06T15:29:59Z")));
    systemLogRepository.saveAndFlush(createSystemLog("window-start", Instant.parse("2026-03-06T15:30:00Z")));
    systemLogRepository.saveAndFlush(createSystemLog("yesterday-evening", Instant.parse("2026-03-07T12:00:00Z")));
    systemLogRepository.saveAndFlush(createSystemLog("just-now", Instant.parse("2026-03-07T15:29:00Z")));

    LocalDateTime now = LocalDateTime.of(2026, 3, 8, 0, 30, 0);

    mockMvc.perform(searchSystemLog(searchRequestBody(now.minusHours(24), now)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(3))
        .andExpect(jsonPath("$.data.list[0].traceId").value("just-now"))
        .andExpect(jsonPath("$.data.list[1].traceId").value("yesterday-evening"))
        .andExpect(jsonPath("$.data.list[2].traceId").value("window-start"));

    // 같은 데이터를 오늘 하루로 좁히면 자정 이후 1건만 남는다
    LocalDate today = LocalDate.of(2026, 3, 8);
    mockMvc.perform(searchSystemLog(searchRequestBody(wholeDay(today), endOfDay(today))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(1))
        .andExpect(jsonPath("$.data.list[0].traceId").value("just-now"));
  }

  private MockHttpServletRequestBuilder searchSystemLog(String body) {
    return post("/api/log/admin/system/search")
        .with(user("admin").authorities(() -> "ROLE_ADMIN"))
        .contentType(MediaType.APPLICATION_JSON)
        .header(ClientTimeZoneResolver.TIMEZONE_HEADER, "Asia/Seoul")
        .header(ClientTimeZoneResolver.OFFSET_HEADER, "540")
        .content(body);
  }

  /** 하루의 시작. 날짜 프리셋이 만드는 값과 같은 꼴이다. */
  private LocalDateTime wholeDay(LocalDate date) {
    return date.atStartOfDay();
  }

  /** 하루의 끝(포함) — 서버가 1초를 더해 다음 날 자정을 배타 상한으로 삼는다. */
  private LocalDateTime endOfDay(LocalDate date) {
    return date.atTime(23, 59, 59);
  }

  private String searchRequestBody(LocalDateTime from, LocalDateTime to) throws Exception {
    return objectMapper.writeValueAsString(Map.of(
        "page", 0,
        "pageSize", 10,
        "createdAtFrom", from,
        "createdAtTo", to
    ));
  }

  private SystemLog createSystemLog(String traceId, Instant createdAt) {
    return SystemLog.builder()
        .traceId(traceId)
        .spanId(buildSpanId(traceId))
        .requestUri("/api/test/" + traceId)
        .controllerName("TestController")
        .methodName("search")
        .httpMethodType("GET")
        .paramData("{}")
        .responseBody("{}")
        .stackTrace("")
        .remoteAddr("127.0.0.1")
        .processTime(10L)
        .status(ApiResponseStatus.SUCCESS)
        .created(EventLogEntity.builder()
            .at(createdAt)
            .by("tester")
            .build())
        .build();
  }

  private ApiLog createApiLog(String traceId, Instant createdAt) {
    return ApiLog.builder()
        .traceId(traceId)
        .spanId(buildSpanId(traceId))
        .requestUri("/external/" + traceId)
        .httpMethodType("GET")
        .requestHeader("{}")
        .requestParam("{}")
        .requestBody("{}")
        .responseStatus("200")
        .responseBody("{}")
        .processTime(15L)
        .created(EventLogEntity.builder()
            .at(createdAt)
            .by("tester")
            .build())
        .build();
  }

  private String buildSpanId(String traceId) {
    return "s" + Integer.toUnsignedString(traceId.hashCode(), 16);
  }
}
