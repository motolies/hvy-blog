package kr.hvy.blog.modules.stock.application;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.application.dto.CollectCheckpointResponse;
import kr.hvy.blog.modules.stock.application.dto.CollectRunResponse;
import kr.hvy.blog.modules.stock.application.service.CollectAlreadyRunningException;
import kr.hvy.blog.modules.stock.application.service.CollectCheckpointService;
import kr.hvy.blog.modules.stock.application.service.CollectRequestException;
import kr.hvy.blog.modules.stock.application.service.CollectRunService;
import kr.hvy.blog.modules.stock.application.service.StockCollectOrchestrator;
import kr.hvy.blog.modules.stock.client.KisTokenManager;
import kr.hvy.blog.modules.stock.client.KisTokenStatus;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.common.aop.advice.dto.ApiResponse;
import kr.hvy.common.core.code.ApiResponseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주식 수집 관리자 Controller (/api/stock/admin/collect).
 * <p>
 * SecurityConfig 의 {@code /api/{module}/admin/**} 규칙으로 ROLE_ADMIN 인가가 강제된다.
 * 수집 잡은 외부 API 호출과 대량 쓰기를 유발하므로 인증 없이 열지 않는다.
 * 중복 실행(409)·요청 오류(400)는 조작 실수라 컨트롤러 안에서 처리해 전역 핸들러의 Slack 알림을 피한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/stock/admin/collect")
@RequiredArgsConstructor
public class StockCollectAdminController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private final StockCollectOrchestrator orchestrator;
  private final CollectRunService collectRunService;
  private final CollectCheckpointService checkpointService;
  private final KisTokenManager tokenManager;

  // ========== 실행 트리거 ==========

  /**
   * 잡 실행. 장시간 잡(백필)은 백그라운드 제출 후 202, 짧은 잡은 완료 후 200.
   */
  @PostMapping("/{jobType}")
  public ResponseEntity<CollectRunResponse> trigger(@PathVariable CollectJobType jobType,
      @RequestBody(required = false) BackfillRequest request) {
    StockCollectOrchestrator.TriggerResult result = orchestrator.trigger(jobType, request, TriggerType.API);
    log.info("수집 잡 트리거: job={}, runId={}, async={}", jobType, result.run().getRunId(), result.async());
    return ResponseEntity.status(result.async() ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .body(CollectRunResponse.from(result.run()));
  }

  /**
   * 특정 종목·기간 부분 재적재 (tickers 필수).
   */
  @PostMapping("/reload")
  public ResponseEntity<CollectRunResponse> reload(@RequestBody BackfillRequest request) {
    return trigger(CollectJobType.RELOAD, request);
  }

  // ========== 실행 이력 ==========

  /** 최근 run 목록 (jobType 미지정 시 전체) */
  @GetMapping("/runs")
  public List<CollectRunResponse> runs(@RequestParam(required = false) CollectJobType jobType,
      @RequestParam(defaultValue = "50") int limit) {
    return collectRunService.findRecent(jobType, clamp(limit)).stream()
        .map(CollectRunResponse::from)
        .toList();
  }

  /** run 단건 */
  @GetMapping("/runs/{runId}")
  public CollectRunResponse run(@PathVariable Long runId) {
    return CollectRunResponse.from(collectRunService.get(runId));
  }

  /** 협조적 취소 요청. 잡 루프가 종목 경계에서 감지해 스스로 종료한다 */
  @PostMapping("/runs/{runId}/cancel")
  public CollectRunResponse cancel(@PathVariable Long runId) {
    boolean canceled = collectRunService.requestCancel(runId);
    log.info("수집 run 취소 요청: runId={}, applied={}", runId, canceled);
    return CollectRunResponse.from(collectRunService.get(runId));
  }

  // ========== 체크포인트 ==========

  /** 잡별 체크포인트 목록 (status 필터 선택) */
  @GetMapping("/checkpoints")
  public List<CollectCheckpointResponse> checkpoints(@RequestParam CollectJobType jobType,
      @RequestParam(required = false) CheckpointStatus status,
      @RequestParam(defaultValue = "50") int limit) {
    return checkpointService.findRecent(jobType, status, clamp(limit)).stream()
        .map(CollectCheckpointResponse::from)
        .toList();
  }

  /** 잡별 상태 건수 (진행률) */
  @GetMapping("/checkpoints/summary")
  public Map<CheckpointStatus, Long> checkpointSummary(@RequestParam CollectJobType jobType) {
    return checkpointService.summary(jobType);
  }

  // ========== 토큰 ==========

  /** 토큰 상태 (값은 노출하지 않음) */
  @GetMapping("/token")
  public KisTokenStatus token() {
    return tokenManager.status();
  }

  /** 강제 재발급. 1분 게이트에 걸리면 기존 토큰이 유지된다 */
  @PostMapping("/token/refresh")
  public KisTokenStatus refreshToken() {
    tokenManager.forceRefresh();
    return tokenManager.status();
  }

  // ========== 컨트롤러 지역 예외 처리 (Slack 미발송) ==========

  /** 동일 잡 실행 중 → 409 */
  @ExceptionHandler(CollectAlreadyRunningException.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleAlreadyRunning(CollectAlreadyRunningException ex) {
    log.info("수집 잡 중복 실행 거부: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.<Map<String, Object>>builder()
        .status(ApiResponseStatus.FAIL)
        .message(ex.getMessage())
        .data(Map.of("jobType", ex.getJobType().getCode(), "runningRunId", String.valueOf(ex.getRunningRunId())))
        .build());
  }

  /** 요청 형식·전제조건 오류 → 400 */
  @ExceptionHandler(CollectRequestException.class)
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(CollectRequestException ex) {
    log.info("수집 요청 거부: {}", ex.getMessage());
    return ResponseEntity.badRequest().body(ApiResponse.<Void>builder()
        .status(ApiResponseStatus.FAIL)
        .message(ex.getMessage())
        .build());
  }

  private int clamp(int limit) {
    return Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
  }
}
