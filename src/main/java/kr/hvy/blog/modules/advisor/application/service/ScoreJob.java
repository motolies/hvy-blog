package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
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
 *   <li>잠정 채점: 결정 호라이즌·진단 호라이즌마다 청산일이 확보됐는데 아직 채점 행이 없는 판단</li>
 *   <li>확정 재채점: 잠정 상태이고 청산일이 마지막 WEEKLY 성공 시작일보다 앞선 판단(유상증자 계수 반영됨)</li>
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

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.SCORE;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    AdvisorSteps steps = new AdvisorSteps(execution);
    steps.run("SCORE", () -> scoreDue(execution));
    steps.run("IC", () -> icService.computeIncremental().ifPresent(r -> execution.putMetadata("icRange", r[0] + "~" + r[1])));
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

    // ① 잠정: 호라이즌마다 청산일이 확보된 미채점 판단
    for (int h : horizons) {
      for (AdviceHeader advice : unscored(h)) {
        Optional<AdviceScoringService.Outcome> outcome = scoring.score(advice, h, ScoreStage.PROVISIONAL);
        if (outcome.isPresent()) {
          provisional++;
        }
      }
    }
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
    execution.putMetadata("scoring", summary);
    log.info("채점 실행: base={}, provisional={}, confirmed={}", today, provisional, confirmed);

    // ③ 스코어보드
    LocalDate from = today.minusDays(SCOREBOARD_DAYS);
    List<String> lines = kpi.slackLines(from, today);
    boolean memoryOn = adviceWriter.countLivePicks() >= properties.getLesson().getMinPicks();
    Map<String, Object> block = memoryOn ? kpi.promptScoreboard(from, today) : null;
    return new AdviseJob.Scoreboard(lines, block);
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
