package kr.hvy.blog.modules.advisor.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.dto.AdviceDetailResponse;
import kr.hvy.blog.modules.advisor.application.dto.AdvisorRunResponse;
import kr.hvy.blog.modules.advisor.application.dto.ScoreSummaryResponse;
import kr.hvy.blog.modules.advisor.application.service.AdvisorAlreadyRunningException;
import kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService;
import kr.hvy.blog.modules.advisor.application.service.AdvisorOrchestrator;
import kr.hvy.blog.modules.advisor.application.service.AdvisorRequestException;
import kr.hvy.blog.modules.advisor.application.service.AdvisorRunService;
import kr.hvy.blog.modules.advisor.application.service.SignalWeightMath;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.LessonStatus;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.LessonRow;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalIcRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.IntradayCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.LessonRepository;
import kr.hvy.blog.modules.advisor.repository.jdbc.PromptInputWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.ScoreWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.SignalIcWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.common.aop.advice.dto.ApiResponse;
import kr.hvy.common.core.code.ApiResponseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 시장 판단 관리자 Controller (/api/advisor/admin). SecurityConfig 의 {@code /api/{module}/admin/**} 규칙으로 ROLE_ADMIN 이 강제된다.
 * <p>
 * 수동 트리거·판단 조회·KPI·가중치·교훈. 중복 실행(409)·요청 오류(400)·없음(404)은 여기서 처리해 전역 핸들러의 Slack 알림을 피한다.
 * advisor.enabled=false 면 이 컨트롤러도 뜨지 않는다(오케스트레이터 빈이 없다).
 */
@Slf4j
@RestController
@RequestMapping("/api/advisor/admin")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class AdvisorAdminController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private final AdvisorOrchestrator orchestrator;
  private final AdvisorRunService runService;
  private final AdviceWriter adviceWriter;
  private final ScoreWriter scoreWriter;
  private final IntradayCheckWriter intradayChecks;
  private final PromptInputWriter promptInputs;
  private final AdvisorKpiService kpi;
  private final WeightSetRepository weightSets;
  private final SignalIcWriter icWriter;
  private final LessonRepository lessons;
  private final AdvisorProperties properties;

  // ========== 실행 ==========

  /**
   * 잡 실행. 장시간 잡(ADVISE·SCORE·WEEKLY_REVIEW·IC_BACKFILL)은 advisorExecutor 제출 후 202, INTRADAY 는 완료 후 200.
   * baseDate 를 주면 그 날짜 기준(과거 보충). 이미 LIVE 판단이 있는 날은 SKIPPED 로 닫히므로 재판단은 DELETE /advices/{id} 뒤에.
   */
  @PostMapping("/jobs/{jobType}")
  public ResponseEntity<AdvisorRunResponse> trigger(@PathVariable AdvisorJobType jobType,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate) {
    AdvisorOrchestrator.TriggerResult result = orchestrator.trigger(jobType, baseDate, AdvisorTriggerType.API);
    log.info("advisor 잡 트리거: job={}, runId={}, base={}, async={}", jobType, result.run().getRunId(), baseDate, result.async());
    return ResponseEntity.status(result.async() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(AdvisorRunResponse.from(result.run()));
  }

  @GetMapping("/runs")
  public List<AdvisorRunResponse> runs(@RequestParam(required = false) AdvisorJobType jobType, @RequestParam(defaultValue = "50") int limit) {
    return runService.findRecent(jobType, clamp(limit)).stream().map(AdvisorRunResponse::from).toList();
  }

  @GetMapping("/runs/{runId}")
  public AdvisorRunResponse run(@PathVariable Long runId) {
    return AdvisorRunResponse.from(runService.get(runId));
  }

  // ========== 판단 ==========

  @GetMapping("/advices")
  public List<AdviceHeader> advices(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) AdviceVariant variant, @RequestParam(defaultValue = "50") int limit) {
    LocalDate end = to == null ? MarketClock.today() : to;
    LocalDate start = from == null ? end.minusDays(30) : from;
    return adviceWriter.findRange(start, end, variant, clamp(limit));
  }

  @GetMapping("/advices/{adviceId}")
  public AdviceDetailResponse advice(@PathVariable long adviceId) {
    AdviceHeader header = adviceWriter.findById(adviceId).orElseThrow(() -> new NoSuchElementException("판단을 찾을 수 없습니다: " + adviceId));
    return new AdviceDetailResponse(header, adviceWriter.candidates(adviceId), adviceWriter.picks(adviceId), scoreWriter.candidateScores(adviceId),
        scoreWriter.callScores(adviceId), intradayChecks.findByAdvice(adviceId));
  }

  /**
   * 재현용 입력·출력 원문 (LIVE + 있으면 LLM_NOMEM).
   */
  @GetMapping("/advices/{adviceId}/prompt")
  public Map<String, PromptInputRow> prompt(@PathVariable long adviceId) {
    AdviceHeader header = adviceWriter.findById(adviceId).orElseThrow(() -> new NoSuchElementException("판단을 찾을 수 없습니다: " + adviceId));
    Map<String, PromptInputRow> result = new LinkedHashMap<>();
    for (AdviceVariant variant : List.of(AdviceVariant.LIVE, AdviceVariant.LLM_NOMEM)) {
      Optional<PromptInputRow> row = promptInputs.find(header.runId(), variant);
      row.ifPresent(r -> result.put(variant.getCode(), r));
    }
    return result;
  }

  /**
   * 판단 삭제 (후보·픽·채점·장중 점검 CASCADE). 같은 날 재판단 전에만 쓴다.
   */
  @org.springframework.web.bind.annotation.DeleteMapping("/advices/{adviceId}")
  public Map<String, Object> deleteAdvice(@PathVariable long adviceId) {
    int deleted = adviceWriter.delete(adviceId);
    if (deleted == 0) {
      throw new NoSuchElementException("판단을 찾을 수 없습니다: " + adviceId);
    }
    log.warn("advisor 판단 삭제: adviceId={}", adviceId);
    return Map.of("adviceId", adviceId, "deleted", deleted);
  }

  // ========== KPI ==========

  @GetMapping("/scores/summary")
  public ScoreSummaryResponse summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    LocalDate end = to == null ? MarketClock.today() : to;
    LocalDate start = from == null ? end.minusDays(90) : from;
    return new ScoreSummaryResponse(start, end, properties.getHorizonDays(), kpi.variantSummaries(start, end),
        kpi.regimeSummary(AdviceVariant.LIVE, start, end), kpi.calibration(start, end), kpi.recentPicks(start, end, 20),
        "판정은 최소 6개월 뒤(승률 55% 검정 ≈620 독립 관측, 초과수익 0.5% 검출 ≈400). 부가가치 = 픽 − 후보군 평균이 LLM 층의 1차 KPI");
  }

  @GetMapping("/scores/calibration")
  public List<AdvisorKpiService.CalibrationRow> calibration(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    LocalDate end = to == null ? MarketClock.today() : to;
    return kpi.calibration(from == null ? end.minusDays(90) : from, end);
  }

  /**
   * 시그널 IC 창 통계 (asOf 이하 window 영업일).
   */
  @GetMapping("/scores/ic")
  public Map<String, SignalWeightMath.IcStat> ic(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(required = false) Integer window) {
    LocalDate end = asOf == null ? MarketClock.today() : asOf;
    int days = window == null ? properties.getIc().getWindowDays() : Math.max(5, window);
    List<SignalIcRow> rows = icWriter.window(end, days);
    return SignalWeightMath.aggregate(rows, properties.getHorizonDays());
  }

  // ========== 가중치 ==========

  @GetMapping("/weights")
  public WeightSet activeWeights() {
    return weightSets.active().orElseThrow(() -> new NoSuchElementException("활성 가중치 세트가 없습니다 (advisor-seed.sql 적용 필요)"));
  }

  @GetMapping("/weights/sets")
  public List<WeightSet> weightSets(@RequestParam(defaultValue = "20") int limit) {
    return weightSets.recent(clamp(limit));
  }

  /**
   * 기존 세트를 활성화한다 (수동 롤백). 다음 ADVISE 부터 적용.
   */
  @PostMapping("/weights/sets/{weightSetId}/activate")
  public WeightSet activate(@PathVariable long weightSetId) {
    weightSets.activate(weightSetId);
    log.warn("advisor 가중치 세트 수동 활성화: {}", weightSetId);
    return weightSets.find(weightSetId).orElseThrow();
  }

  // ========== 교훈 ==========

  @GetMapping("/lessons")
  public List<LessonRow> lessons(@RequestParam(required = false) LessonStatus status, @RequestParam(defaultValue = "100") int limit) {
    return status == null ? lessons.findAll(clamp(limit)) : lessons.findByStatus(status);
  }

  /**
   * 잘못된 교훈 수동 폐기.
   */
  @PostMapping("/lessons/{lessonId}/retire")
  public LessonRow retire(@PathVariable long lessonId, @RequestParam(defaultValue = "관리자 수동 폐기") String reason) {
    lessons.find(lessonId).orElseThrow(() -> new NoSuchElementException("교훈을 찾을 수 없습니다: " + lessonId));
    lessons.updateStatus(lessonId, LessonStatus.RETIRED, java.time.Instant.now(), reason);
    return lessons.find(lessonId).orElseThrow();
  }

  // ========== 컨트롤러 지역 예외 처리 (Slack 미발송) ==========

  @ExceptionHandler(AdvisorAlreadyRunningException.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleAlreadyRunning(AdvisorAlreadyRunningException ex) {
    log.info("advisor 잡 중복 실행 거부: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.<Map<String, Object>>builder()
        .status(ApiResponseStatus.FAIL)
        .message(ex.getMessage())
        .data(Map.of("jobType", ex.getJobType().getCode(), "runningRunId", String.valueOf(ex.getRunningRunId())))
        .build());
  }

  @ExceptionHandler(AdvisorRequestException.class)
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(AdvisorRequestException ex) {
    log.info("advisor 요청 거부: {}", ex.getMessage());
    return ResponseEntity.badRequest().body(ApiResponse.<Void>builder().status(ApiResponseStatus.FAIL).message(ex.getMessage()).build());
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ApiResponse<Void>> handleNotFound(NoSuchElementException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.<Void>builder().status(ApiResponseStatus.FAIL).message(ex.getMessage()).build());
  }

  private int clamp(int limit) {
    return Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
  }
}
