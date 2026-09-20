package kr.hvy.blog.modules.stock.application;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.StockCollectRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 수집 run 목록 필터(status·from·to·jobType)와 없는 run 의 404 지역 처리를 검증한다 (2026-09-20 관리자 화면용).
 * <p>
 * 기간 경계가 핵심이다: DB·JVM 은 UTC 지만 운영자가 고르는 날짜는 KST 다. 09-10 00:30 KST(= 09-09 15:30 UTC) run 이
 * {@code from=2026-09-10} 에 포함되고 09-09 23:30 KST run 은 빠져야 한다.
 * 애노테이션 구성은 LogSearchStatusFilterIntegrationTest 와 동일하게 두어 Spring 컨텍스트 캐시를 재사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockCollectAdminRunsFilterIntegrationTest {

  private static final String RUNS_URI = "/api/stock/admin/collect/runs";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private StockCollectRunRepository repository;

  @MockitoBean
  private RedissonClient redissonClient;

  private Long late0909;
  private Long early0910;
  private Long daily0910Failed;
  private Long daily0911Ok;
  private Long master0912Failed;
  private Long weeklyRunning;

  @BeforeEach
  void setUp() {
    repository.deleteAllInBatch();
    late0909 = save(CollectJobType.WEEKLY, CollectStatus.SUCCESS, kst(2026, 9, 9, 23, 30));
    early0910 = save(CollectJobType.MASTER, CollectStatus.SUCCESS, kst(2026, 9, 10, 0, 30));
    daily0910Failed = save(CollectJobType.DAILY, CollectStatus.FAILED, kst(2026, 9, 10, 18, 30));
    daily0911Ok = save(CollectJobType.DAILY, CollectStatus.SUCCESS, kst(2026, 9, 11, 18, 30));
    master0912Failed = save(CollectJobType.MASTER, CollectStatus.FAILED, kst(2026, 9, 12, 5, 30));
    weeklyRunning = save(CollectJobType.WEEKLY, CollectStatus.RUNNING, kst(2026, 9, 13, 3, 0));
  }

  @Test
  @DisplayName("조건 없이 부르면 전체가 startedAt 최신순으로 온다 (기존 findRecent 호환)")
  void noFilterReturnsAllNewestFirst() throws Exception {
    runs(get(RUNS_URI))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(6)))
        .andExpect(jsonPath("$.data[0].runId").value(weeklyRunning))
        .andExpect(jsonPath("$.data[5].runId").value(late0909));
  }

  @Test
  @DisplayName("status 만 주면 그 상태의 run 만 온다")
  void statusFilter() throws Exception {
    runs(get(RUNS_URI).param("status", "FAILED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(2)))
        .andExpect(jsonPath("$.data[0].runId").value(master0912Failed))
        .andExpect(jsonPath("$.data[0].status").value("FAILED"))
        .andExpect(jsonPath("$.data[1].runId").value(daily0910Failed));
  }

  @Test
  @DisplayName("from·to 는 startedAt 의 KST 날짜 경계다 — 00:30 KST 는 포함, 전날 23:30 KST 는 제외")
  void dateRangeUsesKstDayBoundaries() throws Exception {
    runs(get(RUNS_URI).param("from", "2026-09-10").param("to", "2026-09-10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(2)))
        .andExpect(jsonPath("$.data[0].runId").value(daily0910Failed))
        .andExpect(jsonPath("$.data[1].runId").value(early0910));
  }

  @Test
  @DisplayName("from 만 주면 그 날 이후 전부, to 만 주면 그 날까지 전부")
  void openEndedRanges() throws Exception {
    runs(get(RUNS_URI).param("from", "2026-09-12"))
        .andExpect(jsonPath("$.data", hasSize(2)))
        .andExpect(jsonPath("$.data[0].runId").value(weeklyRunning))
        .andExpect(jsonPath("$.data[1].runId").value(master0912Failed));

    runs(get(RUNS_URI).param("to", "2026-09-09"))
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].runId").value(late0909));
  }

  @Test
  @DisplayName("jobType·status·기간을 함께 걸면 교집합이다")
  void combinedFilters() throws Exception {
    runs(get(RUNS_URI).param("jobType", "DAILY").param("status", "FAILED")
        .param("from", "2026-09-01").param("to", "2026-09-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].runId").value(daily0910Failed))
        .andExpect(jsonPath("$.data[0].jobType").value("DAILY"));

    runs(get(RUNS_URI).param("jobType", "DAILY").param("status", "SUCCESS"))
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].runId").value(daily0911Ok));
  }

  @Test
  @DisplayName("limit 은 최신순 상위 N 건으로 자른다")
  void limitCapsResult() throws Exception {
    runs(get(RUNS_URI).param("limit", "2"))
        .andExpect(jsonPath("$.data", hasSize(2)))
        .andExpect(jsonPath("$.data[0].runId").value(weeklyRunning))
        .andExpect(jsonPath("$.data[1].runId").value(master0912Failed));
  }

  /**
   * 없는 run 은 전역 핸들러(500 + Slack)가 아니라 컨트롤러 지역 핸들러가 404 + ApiResponse FAIL 로 답해야 한다.
   * 관리자 화면의 ?run= 딥링크가 지워진 run 을 가리키는 흔한 조작 실수라 알림이 울리면 안 된다.
   */
  @Test
  @DisplayName("없는 run 상세는 404 + ApiResponse FAIL (Slack 미발송 경로)")
  void unknownRunReturns404WithFailBody() throws Exception {
    runs(get(RUNS_URI + "/999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value("FAIL"))
        .andExpect(jsonPath("$.message", containsString("999999")));
  }

  /**
   * 잘못된 형식의 파라미터는 전역 ResponseEntityExceptionHandler 로 가면 ProblemDetail 이 ApiResponse SUCCESS 로 감싸져 message 가 빈다.
   * 컨트롤러 지역 핸들러가 400 + FAIL + 한글 메시지로 답해야 프론트 토스트가 뜬다.
   */
  @Test
  @DisplayName("ISO 가 아닌 from 은 400 + ApiResponse FAIL + 메시지 (ProblemDetail 이 SUCCESS 로 감싸지지 않는다)")
  void malformedDateReturns400WithFailBody() throws Exception {
    runs(get(RUNS_URI).param("from", "2026-9-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("FAIL"))
        .andExpect(jsonPath("$.message", containsString("from")))
        .andExpect(jsonPath("$.message", containsString("2026-9-1")));
  }

  @Test
  @DisplayName("없는 run 취소도 같은 404 경로를 탄다")
  void cancelUnknownRunReturns404() throws Exception {
    runs(post(RUNS_URI + "/999999/cancel"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value("FAIL"))
        .andExpect(jsonPath("$.message", containsString("999999")));
  }

  // ── 헬퍼 ─────────────────────────────────────────────────────────────────

  private ResultActions runs(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request.with(user("admin").authorities(() -> "ROLE_ADMIN")));
  }

  private Long save(CollectJobType jobType, CollectStatus status, Instant startedAt) {
    return repository.saveAndFlush(StockCollectRun.builder()
        .jobType(jobType)
        .triggerType(TriggerType.SCHEDULER)
        .targetDate(LocalDate.ofInstant(startedAt, MarketClock.KST))
        .status(status)
        .startedAt(startedAt)
        .build()).getRunId();
  }

  private static Instant kst(int year, int month, int day, int hour, int minute) {
    return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, MarketClock.KST).toInstant();
  }
}
