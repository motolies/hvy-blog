package kr.hvy.blog.modules.log.application;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.Instant;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
            .content(searchRequestBody(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 8))))
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
            .content(searchRequestBody(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 8))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").value(2))
        .andExpect(jsonPath("$.data.list[0].traceId").value("included-end"))
        .andExpect(jsonPath("$.data.list[1].traceId").value("included-start"));
  }

  private String searchRequestBody(LocalDate from, LocalDate to) throws Exception {
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
