package kr.hvy.blog.modules.advisor.application;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import kr.hvy.blog.common.AbstractTestContainers;
import kr.hvy.blog.modules.advisor.application.service.AdvisorGateService;
import kr.hvy.blog.modules.advisor.application.service.AdvisorGateService.Decision;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.repository.AdvisorRunRepository;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code GET /api/advisor/admin/gate} 의 HTTP 계약(baseDate 기본값·Decision 펼침·파생값 {@code ready}/{@code waitQuietly})과
 * {@code GET /runs} 필터(status·from·to·jobType)를 검증한다 (2026-09-20 관리자 화면용).
 * <p>
 * 컨텍스트는 AdvisorContextBootTest.WithApiKey 와 같은 방식으로 띄운다(advisor.enabled=true·Redis Testcontainers·HTTP 클라이언트 목).
 * 게이트 판정 자체는 {@link AdvisorGateService} 를 목으로 바꾼다 — H2 에는 advisor·휴장일 테이블(JDBC Writer 조회 대상)이 없어 실제 판정을
 * 돌릴 수 없고, 판정 규칙은 AdvisorGateServiceTest 가 고정한다. 여기서는 실제 {@link Decision} record 를 돌려줘 파생 메서드 계산이
 * 응답 값으로 그대로 펼쳐지는지를 본다.
 */
@SpringBootTest(properties = {"advisor.enabled=true", "spring.ai.openai.api-key=test-key"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdvisorAdminGateIntegrationTest extends AbstractTestContainers {

  private static final String GATE_URI = "/api/advisor/admin/gate";
  private static final String RUNS_URI = "/api/advisor/admin/runs";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AdvisorRunRepository repository;

  /** OpenAI 는 호출하지 않는다 — HTTP 클라이언트를 목으로 바꿔 두기만 한다 */
  @MockitoBean
  private OpenAiResponsesClient responsesClient;

  @MockitoBean
  private AdvisorGateService gate;

  @BeforeEach
  void setUp() {
    repository.deleteAllInBatch();
  }

  // ── 게이트 ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("baseDate 를 안 주면 오늘(KST) 기준이고, 마감 지난 DAILY 미완은 ready=false·waitQuietly=false (경보 대상)")
  void gateDefaultsToTodayAndFlattensDerivedFields() throws Exception {
    LocalDate today = MarketClock.today();
    when(gate.decide(today)).thenReturn(
        new Decision(true, false, false, true, DataQuality.OK, "DAILY 수집이 아직 끝나지 않았습니다: " + today));

    admin(get(GATE_URI))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.baseDate").value(today.toString()))
        .andExpect(jsonPath("$.data.tradingDay").value(true))
        .andExpect(jsonPath("$.data.alreadyDone").value(false))
        .andExpect(jsonPath("$.data.dataReady").value(false))
        .andExpect(jsonPath("$.data.pastDeadline").value(true))
        .andExpect(jsonPath("$.data.quality").value("OK"))
        .andExpect(jsonPath("$.data.reason", containsString("DAILY 수집이 아직")))
        .andExpect(jsonPath("$.data.ready").value(false))
        .andExpect(jsonPath("$.data.waitQuietly").value(false));
  }

  @Test
  @DisplayName("baseDate 를 주면 그 날짜로 판정하고, 준비 완료면 ready=true (DEGRADED 품질도 그대로 노출)")
  void gateWithBaseDateReady() throws Exception {
    LocalDate base = LocalDate.of(2026, 9, 18);
    when(gate.decide(base)).thenReturn(new Decision(true, false, true, false, DataQuality.DEGRADED, "DAILY 완료"));

    admin(get(GATE_URI).param("baseDate", "2026-09-18"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.baseDate").value("2026-09-18"))
        .andExpect(jsonPath("$.data.ready").value(true))
        .andExpect(jsonPath("$.data.waitQuietly").value(false))
        .andExpect(jsonPath("$.data.pastDeadline").value(false))
        .andExpect(jsonPath("$.data.quality").value("DEGRADED"))
        .andExpect(jsonPath("$.data.reason").value("DAILY 완료"));
  }

  @Test
  @DisplayName("휴장일은 ready=false·waitQuietly=true 로 펼쳐진다 (화면 칩 우선순위 1순위 neutral)")
  void gateHoliday() throws Exception {
    LocalDate saturday = LocalDate.of(2026, 9, 19);
    when(gate.decide(saturday)).thenReturn(new Decision(false, false, false, false, DataQuality.OK, "휴장일 " + saturday));

    admin(get(GATE_URI).param("baseDate", "2026-09-19"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tradingDay").value(false))
        .andExpect(jsonPath("$.data.ready").value(false))
        .andExpect(jsonPath("$.data.waitQuietly").value(true))
        .andExpect(jsonPath("$.data.reason", containsString("휴장일")));
  }

  // ── run 목록 필터 ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("status·from·to·jobType 필터가 stock 과 같은 의미로 동작한다 (KST 날짜 경계, 최신순)")
  void runsFilter() throws Exception {
    Long skipped0909 = save(AdvisorJobType.ADVISE, AdvisorStatus.SKIPPED, kst(2026, 9, 9, 23, 30));
    Long earlyIntraday0910 = save(AdvisorJobType.INTRADAY, AdvisorStatus.SUCCESS, kst(2026, 9, 10, 0, 30));
    Long failedAdvise0910 = save(AdvisorJobType.ADVISE, AdvisorStatus.FAILED, kst(2026, 9, 10, 19, 30));
    Long okAdvise0911 = save(AdvisorJobType.ADVISE, AdvisorStatus.SUCCESS, kst(2026, 9, 11, 19, 30));
    Long runningReview = save(AdvisorJobType.WEEKLY_REVIEW, AdvisorStatus.RUNNING, kst(2026, 9, 13, 8, 0));

    admin(get(RUNS_URI))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(5)))
        .andExpect(jsonPath("$.data[0].runId").value(runningReview))
        .andExpect(jsonPath("$.data[4].runId").value(skipped0909));

    admin(get(RUNS_URI).param("status", "FAILED"))
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].runId").value(failedAdvise0910))
        .andExpect(jsonPath("$.data[0].status").value("FAILED"));

    // 09-10 00:30 KST(= 09-09 15:30 UTC) 는 포함, 09-09 23:30 KST 는 제외
    admin(get(RUNS_URI).param("from", "2026-09-10").param("to", "2026-09-10"))
        .andExpect(jsonPath("$.data", hasSize(2)))
        .andExpect(jsonPath("$.data[0].runId").value(failedAdvise0910))
        .andExpect(jsonPath("$.data[1].runId").value(earlyIntraday0910));

    admin(get(RUNS_URI).param("jobType", "ADVISE").param("status", "SUCCESS").param("from", "2026-09-01").param("to", "2026-09-30"))
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].runId").value(okAdvise0911))
        .andExpect(jsonPath("$.data[0].jobType").value("ADVISE"));
  }

  @Test
  @DisplayName("enum 밖의 status 는 400 + ApiResponse FAIL + 메시지 (ProblemDetail 이 SUCCESS 로 감싸지지 않는다)")
  void unknownStatusReturns400WithFailBody() throws Exception {
    admin(get(RUNS_URI).param("status", "FOO"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("FAIL"))
        .andExpect(jsonPath("$.message", containsString("status")))
        .andExpect(jsonPath("$.message", containsString("FOO")));
  }

  @Test
  @DisplayName("없는 run 상세는 404 + ApiResponse FAIL (기존 지역 핸들러 — stock 과 동형임을 함께 고정)")
  void unknownRunReturns404() throws Exception {
    admin(get(RUNS_URI + "/999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value("FAIL"))
        .andExpect(jsonPath("$.message", containsString("999999")));
  }

  // ── 헬퍼 ─────────────────────────────────────────────────────────────────

  private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request.with(user("admin").authorities(() -> "ROLE_ADMIN")));
  }

  private Long save(AdvisorJobType jobType, AdvisorStatus status, Instant startedAt) {
    return repository.saveAndFlush(AdvisorRun.builder()
        .jobType(jobType)
        .triggerType(AdvisorTriggerType.SCHEDULER)
        .baseDate(LocalDate.ofInstant(startedAt, MarketClock.KST))
        .status(status)
        .startedAt(startedAt)
        .build()).getRunId();
  }

  private static Instant kst(int year, int month, int day, int hour, int minute) {
    return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, MarketClock.KST).toInstant();
  }
}
