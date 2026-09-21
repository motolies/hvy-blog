package kr.hvy.blog.modules.advisor.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStatus;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.PickNoteRepository;
import kr.hvy.blog.modules.advisor.repository.jdbc.ScoreWriter;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.repository.StockCollectRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 채점 잡 (SCORE, 관리자 보충 실행) 겸 ADVISE 앞단 훅.
 * <ol>
 *   <li>잠정 채점: 결정 호라이즌·진단 호라이즌마다 청산일이 확보됐는데 아직 채점 행이 없는 판단. 결정 호라이즌 잠정 채점이 저장되면 같은 판단의 12:00 픽 노트를
 *       T+5 초과 부호로 확정한다(note-v1, 격리 — 실패해도 채점은 유지)</li>
 *   <li>확정 재채점: 잠정 상태이고 청산일이 마지막 WEEKLY 성공 시작일보다 앞선 판단(유상증자 계수 반영됨). 노트는 다시 건드리지 않는다(append-only)</li>
 *   <li>IC 증분 계산</li>
 *   <li>스코어보드(Slack 줄은 항상, 프롬프트 블록은 누적 픽 게이트를 넘겼을 때)</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ScoreJob implements AdvisorJob, AdviseJob.ScoreHook {

  static final int SCOREBOARD_DAYS = 28;

  private final AdviceScoringService scoring;
  private final AdviceWriter adviceWriter;
  private final ScoreWriter scoreWriter;
  private final SignalIcService icService;
  private final AdvisorKpiService kpi;
  private final StockCollectRunRepository collectRuns;
  private final JdbcTemplate jdbc;
  private final AdvisorProperties properties;
  private final PickNoteRepository notes;

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.SCORE;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    AdvisorSteps steps = new AdvisorSteps(execution);
    steps.run("SCORE", () -> scoreDue(execution));
    steps.run("IC", () -> icService.computeIncremental(steps::run).ifPresent(r -> r.record(execution)));
  }

  @Override
  public AdviseJob.Scoreboard scoreDue(AdvisorExecution execution) {
    LocalDate today = execution.baseDate();
    List<Integer> horizons = new ArrayList<>();
    horizons.add(properties.getHorizonDays());
    horizons.addAll(properties.getDiagnosticHorizons());
    Map<String, Object> summary = new LinkedHashMap<>();
    int provisional = 0;
    int confirmed = 0;
    int notesFinalized = 0;
    Set<Long> noteHandled = new java.util.HashSet<>();

    // ① 잠정: 호라이즌마다 청산일이 확보된 미채점 판단. 결정 호라이즌이 저장되면 그 판단의 12:00 노트를 확정한다(격리)
    for (int h : horizons) {
      for (AdviceHeader advice : unscored(h)) {
        Optional<AdviceScoringService.Outcome> outcome = scoring.score(advice, h, ScoreStage.PROVISIONAL);
        if (outcome.isPresent()) {
          provisional++;
          if (h == properties.getHorizonDays()) {
            notesFinalized += finalizeNotes(execution, advice);
            noteHandled.add(advice.adviceId());
          }
        }
      }
    }
    // ①-b 확정 누락 보충(격리): 이전 run 에서 확정이 실패해 OPEN 으로 남은 노트를 h=5 채점이 있으면 확정한다 — 이 훅은 ADVISE·WEEKLY·SCORE 어느 run 에서도 돈다
    int notesSwept = sweepOpenNotes(execution, noteHandled);
    // ② 확정: 마지막 WEEKLY 성공 이후 재채점
    Optional<LocalDate> lastWeekly = lastWeeklySuccess();
    if (lastWeekly.isPresent()) {
      for (Long adviceId : scoreWriter.provisionalAdviceIdsExitedBefore(lastWeekly.get())) {
        Optional<AdviceHeader> advice = adviceWriter.findById(adviceId);
        if (advice.isEmpty()) {
          continue;
        }
        for (int h : horizons) {
          if (scoring.score(advice.get(), h, ScoreStage.CONFIRMED).isPresent()) {
            confirmed++;
          }
        }
      }
    }
    summary.put("provisional", provisional);
    summary.put("confirmed", confirmed);
    summary.put("notesFinalized", notesFinalized);
    summary.put("notesSwept", notesSwept);
    execution.putMetadata("scoring", summary);
    log.info("채점 실행: base={}, provisional={}, confirmed={}, notesFinalized={}, notesSwept={}", today, provisional, confirmed, notesFinalized, notesSwept);

    // ③ 스코어보드
    LocalDate from = today.minusDays(SCOREBOARD_DAYS);
    List<String> lines = kpi.slackLines(from, today);
    boolean memoryOn = adviceWriter.countLivePicks() >= properties.getLesson().getMinPicks();
    Map<String, Object> block = memoryOn ? kpi.promptScoreboard(from, today) : null;
    return new AdviseJob.Scoreboard(lines, block);
  }

  /**
   * 결정 호라이즌 잠정 채점이 저장된 직후 같은 LIVE 판단의 OPEN 노트를 확정한다 — final_excess = 픽의 excess_ret(MISSING 제외), 12:00 초과와 부호가 같으면
   * CONFIRMED 아니면 REFUTED. 격리: 실패해도 채점은 유지되고 run 은 PARTIAL 로 남는다. 섀도 판단·CONFIRMED 재채점 경로에서는 부르지 않는다(노트는 append-only).
   * 확정한 노트 수를 돌려준다.
   */
  int finalizeNotes(AdvisorExecution execution, AdviceHeader advice) {
    if (advice.adviceId() == null || advice.variant() != AdviceVariant.LIVE) {
      return 0;
    }
    try {
      Set<String> pickTickers = adviceWriter.picks(advice.adviceId()).stream().map(PickRow::ticker).collect(Collectors.toSet());
      Map<String, Double> finalExcess = new LinkedHashMap<>();
      for (CandidateScoreRow s : scoreWriter.candidateScores(advice.adviceId())) {
        if (s.horizonDays() == properties.getHorizonDays() && s.status() != ScoreStatus.MISSING && s.excessRet() != null && pickTickers.contains(s.ticker())) {
          finalExcess.put(s.ticker(), s.excessRet());
        }
      }
      return finalExcess.isEmpty() ? 0 : notes.finalize(advice.adviceId(), finalExcess, Instant.now());
    } catch (RuntimeException e) {
      execution.recordFailure("NOTE_FINALIZE:" + advice.adviceId(), e.toString());
      log.warn("픽 노트 확정 실패(격리): advice={}", advice.adviceId(), e);
      return 0;
    }
  }

  /**
   * 확정 누락 보충(NOTE_SWEEP): OPEN·미확정 노트가 있는 판단 중 이번 run 에서 이미 다룬 것을 빼고 {@link #finalizeNotes} 를 다시 시도한다 — 결정 호라이즌 채점이
   * 아직 없으면 0건이고, 있으면 그때 확정된다(직후 호출이 격리 실패했던 판단이 영구 OPEN 으로 남지 않게). 조회 자체의 실패는 NOTE_SWEEP 부분 실패로 격리한다.
   * 확정한 노트 수를 돌려준다.
   */
  int sweepOpenNotes(AdvisorExecution execution, Set<Long> alreadyHandled) {
    int swept = 0;
    try {
      for (Long adviceId : notes.findAdviceIdsWithOpenNotes()) {
        if (adviceId == null || alreadyHandled.contains(adviceId)) {
          continue;
        }
        Optional<AdviceHeader> advice = adviceWriter.findById(adviceId);
        if (advice.isPresent()) {
          swept += finalizeNotes(execution, advice.get());
        }
      }
    } catch (RuntimeException e) {
      execution.recordFailure("NOTE_SWEEP", e.toString());
      log.warn("픽 노트 확정 보충 실패(격리)", e);
    }
    return swept;
  }

  /**
   * 호라이즌 h 로 아직 채점되지 않았고 청산일이 캘린더에 있는 판단(모든 변형).
   */
  List<AdviceHeader> unscored(int h) {
    List<Long> ids = jdbc.queryForList("""
        WITH cal AS (SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar),
        last AS (SELECT MAX(rn) AS max_rn FROM cal)
        SELECT a.advice_id
        FROM tb_advisor_advice a
                 JOIN cal c ON c.trade_date = a.base_date
                 CROSS JOIN last
        WHERE c.rn + ? <= last.max_rn
          AND NOT EXISTS (SELECT 1 FROM tb_advisor_candidate_score s WHERE s.advice_id = a.advice_id AND s.horizon_days = ?)
          AND EXISTS (SELECT 1 FROM tb_advisor_candidate cd WHERE cd.advice_id = a.advice_id)
        ORDER BY a.base_date, a.advice_id
        """, Long.class, h, h);
    List<AdviceHeader> result = new ArrayList<>();
    for (Long id : ids) {
      adviceWriter.findById(id).ifPresent(result::add);
    }
    return result;
  }

  /**
   * 마지막 WEEKLY 성공 run 의 대상일 (없으면 empty).
   */
  Optional<LocalDate> lastWeeklySuccess() {
    return collectRuns.findAllByJobTypeOrderByStartedAtDesc(CollectJobType.WEEKLY, PageRequest.of(0, 5)).stream()
        .filter(r -> r.getStatus() == CollectStatus.SUCCESS || r.getStatus() == CollectStatus.PARTIAL)
        .map(StockCollectRun::getTargetDate)
        .filter(d -> d != null)
        .findFirst();
  }
}
