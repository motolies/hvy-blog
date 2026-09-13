package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.AdvisorSyntheticData;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.CallSubject;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStatus;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CallScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.ScoreWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.SignalIcWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import kr.hvy.blog.modules.stock.repository.StockCollectRunRepository;
import kr.hvy.blog.modules.stock.repository.jdbc.BatchUpsertSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 채점 규약을 합성 데이터로 고정한다: D+1 시가 진입/D+h 종가 청산, 벤치마크(지수 상수 → 0), 배당 가산, 정지 → 마지막 종가 SUSPENDED, 진입가 없음 MISSING,
 * 미도래 empty, 국면 밴드·Brier, 섹터 MV 폴백, 잠정→확정 덮어쓰기, ScoreJob 의 미채점 탐색·스코어보드, KPI 집계.
 */
@Testcontainers
class AdviceScoringPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static final List<LocalDate> D = AdvisorSyntheticData.DATES;

  private JdbcTemplate jdbc;
  private AdviceWriter adviceWriter;
  private ScoreWriter scoreWriter;
  private AdviceScoringService scoring;
  private AdvisorKpiService kpi;
  private ScoreJob scoreJob;
  private AdvisorProperties properties;
  private kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter morningChecks;
  private long runId;

  @BeforeAll
  static void install() throws Exception {
    AdvisorSyntheticData.install(POSTGRES);
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(ds);
    properties = new AdvisorProperties(new MockEnvironment());
    properties.getLesson().setMinPicks(1);
    adviceWriter = new AdviceWriter(jdbc);
    scoreWriter = new ScoreWriter(new BatchUpsertSupport(jdbc), jdbc);
    morningChecks = new kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter(jdbc);
    scoring = new AdviceScoringService(named, scoreWriter, properties, morningChecks);
    kpi = new AdvisorKpiService(named, properties);
    StockCollectRunRepository collectRuns = mock(StockCollectRunRepository.class);
    when(collectRuns.findAllByJobTypeOrderByStartedAtDesc(any(), any())).thenReturn(List.of());
    SignalIcService icService = new SignalIcService(named, new SignalIcWriter(new BatchUpsertSupport(jdbc), jdbc), new WeightSetRepository(jdbc), properties);
    scoreJob = new ScoreJob(scoring, adviceWriter, scoreWriter, icService, kpi, collectRuns, jdbc, properties);

    jdbc.update("TRUNCATE tb_advisor_advice CASCADE");
    jdbc.update("TRUNCATE tb_advisor_run CASCADE");
    jdbc.update("TRUNCATE tb_advisor_signal_ic_daily, tb_stock_corporate_action");
    // 합성 데이터 원복 (정지·결측 시나리오가 지운 행)
    for (int k = 11; k <= 15; k++) {
      restore(1, k);
    }
    restore(20, 11);
    runId = jdbc.queryForObject("INSERT INTO tb_advisor_run (job_type, trigger_type, status, created_at, updated_at) "
        + "VALUES ('ADVISE', 'API', 'SUCCESS', NOW(), NOW()) RETURNING run_id", Long.class);
  }

  @Test
  @DisplayName("종목 채점: D+1 시가 → D+5 종가, 벤치 0, 배당 가산, 정지는 마지막 종가 SUSPENDED, 진입가 없음 MISSING, 미도래 empty")
  void candidateScoringRules() {
    // T01 은 D12 이후 거래정지, T20 은 진입일(D11) 행 없음, T10 은 D13 배당락 10원
    for (int k = 13; k <= 15; k++) {
      jdbc.update("DELETE FROM tb_stock_daily_price WHERE ticker = 'T01' AND trade_date = ?", D.get(k));
      jdbc.update("DELETE FROM tb_stock_daily_metric WHERE ticker = 'T01' AND trade_date = ?", D.get(k));
    }
    jdbc.update("DELETE FROM tb_stock_daily_price WHERE ticker = 'T20' AND trade_date = ?", D.get(11));
    jdbc.update("INSERT INTO tb_stock_corporate_action (ticker, effective_date, action_type, cash_amount, source) VALUES ('T10', ?, 'DIVIDEND', 10, 'KSD')", D.get(13));
    AdvisorSyntheticData.refresh(jdbc);

    long adviceId = insertAdvice(D.get(10), AdviceVariant.LIVE, List.of(1, 5, 10, 20), Map.of(5, PickDirection.LONG, 10, PickDirection.AVOID));
    AdviceHeader advice = adviceWriter.findById(adviceId).orElseThrow();

    assertThat(scoring.score(advice, 20, ScoreStage.PROVISIONAL)).as("D+20 은 캘린더 밖 → 미도래").isEmpty();
    AdviceScoringService.Outcome outcome = scoring.score(advice, 5, ScoreStage.PROVISIONAL).orElseThrow();
    assertThat(outcome.candidates()).isEqualTo(4);
    assertThat(outcome.missing()).isEqualTo(1);

    Map<String, CandidateScoreRow> rows = new java.util.HashMap<>();
    scoreWriter.candidateScores(adviceId).forEach(r -> rows.put(r.ticker(), r));
    CandidateScoreRow t05 = rows.get("T05");
    assertThat(t05.status()).isEqualTo(ScoreStatus.SCORED);
    assertThat(t05.entryDate()).isEqualTo(D.get(11));
    assertThat(t05.exitDate()).isEqualTo(D.get(15));
    assertThat(t05.entryPrice()).isEqualTo(AdvisorSyntheticData.close(5, 11));
    assertThat(t05.exitPrice()).isEqualTo(AdvisorSyntheticData.close(5, 15));
    double expected = AdvisorSyntheticData.close(5, 15) / AdvisorSyntheticData.close(5, 11) - 1;
    assertThat(t05.ret()).isCloseTo(expected, within(1e-9));
    assertThat(t05.benchRet()).isCloseTo(0.0, within(1e-9));
    assertThat(t05.excessRet()).isCloseTo(expected, within(1e-9));
    assertThat(t05.costAdjExcess()).isCloseTo(expected - 0.003, within(1e-9));

    CandidateScoreRow t10 = rows.get("T10");
    assertThat(t10.dividendRet()).isCloseTo(10 / AdvisorSyntheticData.close(10, 11), within(1e-9));
    assertThat(t10.ret()).isCloseTo(AdvisorSyntheticData.close(10, 15) / AdvisorSyntheticData.close(10, 11) - 1 + t10.dividendRet(), within(1e-9));

    CandidateScoreRow t01 = rows.get("T01");
    assertThat(t01.status()).isEqualTo(ScoreStatus.SUSPENDED);
    assertThat(t01.exitDate()).isEqualTo(D.get(12));
    assertThat(t01.exitPrice()).isEqualTo(AdvisorSyntheticData.close(1, 12));
    assertThat(t01.excessRet()).as("정지도 학습 포함").isNotNull();

    CandidateScoreRow t20 = rows.get("T20");
    assertThat(t20.status()).isEqualTo(ScoreStatus.MISSING);
    assertThat(t20.ret()).isNull();

    // 국면: 지수 상수 → 수익률 0, σ 0 → 밴드 0 → NEUTRAL, 예측 UP 은 빗나감, Brier = (0.7 − 0)^2
    List<CallScoreRow> calls = scoreWriter.callScores(adviceId);
    CallScoreRow kospi = calls.stream().filter(c -> c.subjectType() == CallSubject.INDEX && c.subjectCode().equals("0001")).findFirst().orElseThrow();
    assertThat(kospi.actualDir()).isEqualTo("NEUTRAL");
    assertThat(kospi.hit()).isFalse();
    assertThat(kospi.brier()).isCloseTo(0.49, within(1e-9));
    assertThat(kospi.stage()).isEqualTo(ScoreStage.PROVISIONAL);
    // 섹터: 업종 지수 없음 → MV 동일가중 폴백 (change_rate 0.5 × 5일 / 100), 시장 0 대비 초과 → 적중
    CallScoreRow sector = calls.stream().filter(c -> c.subjectType() == CallSubject.SECTOR).findFirst().orElseThrow();
    assertThat(sector.subjectCode()).isEqualTo("S1");
    assertThat(sector.actualRet()).isCloseTo(0.025, within(1e-9));
    assertThat(sector.hit()).isTrue();

    // 확정 재채점은 같은 키를 덮어쓴다
    scoring.score(advice, 5, ScoreStage.CONFIRMED);
    assertThat(scoreWriter.candidateScores(adviceId)).allMatch(r -> r.stage() == ScoreStage.CONFIRMED);
    assertThat(scoreWriter.callScores(adviceId)).allMatch(r -> r.stage() == ScoreStage.CONFIRMED);
  }

  @Test
  @DisplayName("ScoreJob: 청산일이 확보된 미채점 판단만 찾아 잠정 채점하고 스코어보드를 만든다. KPI 는 LONG 픽·후보군·AVOID·보정을 나눈다")
  void scoreJobAndKpi() {
    long a1 = insertAdvice(D.get(10), AdviceVariant.LIVE, List.of(1, 5, 10, 30), Map.of(5, PickDirection.LONG, 30, PickDirection.LONG, 10, PickDirection.AVOID));
    long a2 = insertAdvice(D.get(26), AdviceVariant.LIVE, List.of(3, 7), Map.of(3, PickDirection.LONG)); // D+5 = D31 없음 → 미도래
    long shadow = insertAdvice(D.get(10), AdviceVariant.QUANT_TOPN, List.of(1, 5, 10, 30), Map.of(1, PickDirection.LONG, 30, PickDirection.LONG));

    assertThat(scoreJob.unscored(5)).extracting(AdviceHeader::adviceId).containsExactlyInAnyOrder(a1, shadow);
    assertThat(scoreJob.unscored(1)).extracting(AdviceHeader::adviceId).containsExactlyInAnyOrder(a1, a2, shadow);

    AdvisorRun run = AdvisorRun.builder().runId(runId).jobType(AdvisorJobType.SCORE).triggerType(AdvisorTriggerType.API).baseDate(D.getLast()).build();
    AdvisorExecution execution = new AdvisorExecution(run, D.getLast(), properties);
    AdviseJob.Scoreboard board = scoreJob.scoreDue(execution);

    assertThat(scoreJob.unscored(5)).isEmpty();
    assertThat(scoreJob.unscored(1)).isEmpty();
    assertThat(scoreJob.unscored(20)).as("D+20 은 청산일이 캘린더에 없어 후보 목록에서 빠진다").isEmpty();
    assertThat(execution.metadata("scoring")).isNotNull();
    assertThat(board.slackLines()).isNotEmpty();
    assertThat(board.slackLines().getFirst()).contains("5일 픽 2개").contains("승률 100.0%");
    assertThat(board.promptBlock()).as("min-picks=1 이라 블록 생성").isNotNull().containsKeys("picks", "regime", "calibration");

    AdvisorKpiService.VariantSummary live = kpi.variantSummary(AdviceVariant.LIVE, D.getFirst(), D.getLast());
    assertThat(live.picks()).isEqualTo(2);
    assertThat(live.avoidPicks()).isEqualTo(1);
    assertThat(live.hitRate()).isEqualTo(1.0);
    double e5 = AdvisorSyntheticData.close(5, 15) / AdvisorSyntheticData.close(5, 11) - 1;
    double e30 = AdvisorSyntheticData.close(30, 15) / AdvisorSyntheticData.close(30, 11) - 1;
    assertThat(live.meanExcess()).isCloseTo((e5 + e30) / 2, within(1e-9));
    assertThat(live.seExcess()).isPositive();
    assertThat(live.poolMeanExcess()).isNotNull();
    assertThat(live.valueAdd()).isCloseTo(live.meanExcess() - live.poolMeanExcess(), within(1e-12));

    AdvisorKpiService.VariantSummary quant = kpi.variantSummary(AdviceVariant.QUANT_TOPN, D.getFirst(), D.getLast());
    assertThat(quant.picks()).isEqualTo(2);
    assertThat(kpi.regimeSummary(AdviceVariant.LIVE, D.getFirst(), D.getLast()).calls()).isEqualTo(2);
    assertThat(kpi.calibration(D.getFirst(), D.getLast())).hasSize(1).first().extracting(AdvisorKpiService.CalibrationRow::n).isEqualTo(2);
    assertThat(kpi.recentPicks(D.getFirst(), D.getLast(), 5)).hasSize(2);
  }

  @Test
  @DisplayName("추세 전망 채점(h=20): 라벨이 안 바뀌면 BEYOND_20D 적중, WITHIN_5D 는 빗나감, 무효화는 QUIET·전환 없음이면 적중, NONE 은 MISSING, √h 밴드")
  void trendOutlookScoring() {
    // 합성 지수는 2500 상수·MA 성분 0, 종목은 전부 MA20 위라 breadth +1 → 두 시장 모두 SIDEWAYS 로 고정
    AdviceHeader advice = AdviceHeader.builder().adviceId(9L).runId(runId).baseDate(D.get(5)).adviceKind(AdviceHeader.KIND_DAILY).variant(AdviceVariant.LIVE)
        .horizonDays(5).kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.UP).pUp(0.7)
        .trendKospi(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.SIDEWAYS).trendKosdaq(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.SIDEWAYS)
        .outlooks(List.of(
            new kr.hvy.blog.modules.advisor.domain.model.TrendOutlook("0001", kr.hvy.blog.modules.advisor.domain.code.TrendHorizon.BEYOND_20D, 0.70,
                kr.hvy.blog.modules.advisor.domain.code.InvalidationType.BELOW_MA20),
            new kr.hvy.blog.modules.advisor.domain.model.TrendOutlook("1001", kr.hvy.blog.modules.advisor.domain.code.TrendHorizon.WITHIN_5D, 0.60,
                kr.hvy.blog.modules.advisor.domain.code.InvalidationType.NONE)))
        .build();

    assertThat(scoring.trendScores(advice, 5, ScoreStage.PROVISIONAL)).as("결정 호라이즌 패스에서는 채점하지 않는다").isEmpty();
    List<CallScoreRow> rows = scoring.trendScores(advice, 20, ScoreStage.PROVISIONAL);
    assertThat(rows).hasSize(4);
    Map<String, CallScoreRow> byKey = new java.util.HashMap<>();
    rows.forEach(r -> byKey.put(r.subjectType() + ":" + r.subjectCode(), r));

    CallScoreRow kospi = byKey.get("TREND:0001");
    assertThat(kospi.status()).isEqualTo(ScoreStatus.SCORED);
    assertThat(kospi.predicted()).isEqualTo("BEYOND_20D");
    assertThat(kospi.actualDir()).isEqualTo("BEYOND_20D");
    assertThat(kospi.hit()).isTrue();
    assertThat(kospi.brier()).isCloseTo(Math.pow(0.70 - 1, 2), within(1e-9));
    assertThat(kospi.eventDate()).isNull();
    assertThat(kospi.baseValue()).isEqualTo(2500.0);
    assertThat(kospi.exitValue()).isEqualTo(2500.0);

    CallScoreRow kosdaq = byKey.get("TREND:1001");
    assertThat(kosdaq.hit()).isFalse();
    assertThat(kosdaq.brier()).isCloseTo(Math.pow(0.60, 2), within(1e-9));

    CallScoreRow inv = byKey.get("TREND_INV:0001");
    assertThat(inv.status()).isEqualTo(ScoreStatus.SCORED);
    assertThat(inv.predicted()).isEqualTo("BELOW_MA20");
    assertThat(inv.actualDir()).as("종가 == MA20 이라 하향 이탈 없음").isEqualTo("QUIET");
    assertThat(inv.hit()).as("전환도 발동도 없음 = 적중").isTrue();
    assertThat(byKey.get("TREND_INV:1001").status()).isEqualTo(ScoreStatus.MISSING);

    // 저장·재조회 (event_date 컬럼 왕복)
    long adviceId = insertAdvice(D.get(5), AdviceVariant.LIVE, List.of(1), Map.of(1, PickDirection.LONG));
    List<CallScoreRow> persisted = rows.stream().map(r -> r.toBuilder().adviceId(adviceId).build()).toList();
    scoreWriter.upsertCallScores(persisted);
    assertThat(scoreWriter.callScores(adviceId)).hasSize(4).extracting(CallScoreRow::subjectType)
        .containsExactly(CallSubject.TREND, CallSubject.TREND, CallSubject.TREND_INV, CallSubject.TREND_INV);
    AdviceHeader saved = adviceWriter.findById(adviceId).orElseThrow();
    assertThat(saved.trendKospi()).isNull();

    // INDEX 진단 채점의 σ 가 √h 로 커진다 (변동성 0 인 합성 데이터라 밴드는 0 이지만 :h 파라미터 SQL 이 실행되는지 확인)
    assertThat(scoring.indexScores(advice, 20, ScoreStage.PROVISIONAL)).hasSize(2).allMatch(r -> r.band() != null && r.band() == 0.0);
  }

  @Test
  @DisplayName("아침 점검 채점(h=1): 예상 갭 부호가 D+1 시가 갭(합성 0)과 어긋나면 빗나감, HOLD 는 |갭|<임계 적중, 예상 갭 없음은 MISSING, 점검 없는 판단은 빈 목록")
  void morningScoring() {
    long adviceId = insertAdvice(D.get(10), AdviceVariant.LIVE, List.of(1), Map.of(1, PickDirection.LONG));
    AdviceHeader advice = adviceWriter.findById(adviceId).orElseThrow();
    assertThat(scoring.morningScores(advice, 1, ScoreStage.PROVISIONAL)).as("점검 행 없음").isEmpty();

    Map<String, Object> detail = Map.of("index", Map.of(
        "0001", Map.of("symbol", "SPX", "beta", 0.6, "usR1", 0.02, "gapEst", 0.012, "threshold", 0.01, "predicted", "UP", "verdict", "REINFORCE"),
        "1001", new java.util.HashMap<>(Map.of("symbol", "COMP", "threshold", 0.01, "verdict", "HOLD"))));
    long checkId = morningChecks.insert(kr.hvy.blog.modules.advisor.domain.model.MorningCheckRow.builder().adviceId(adviceId).runId(runId)
        .baseDate(D.get(10)).usDate(D.get(10)).gapKospi(0.012).verdict(kr.hvy.blog.modules.advisor.domain.code.MorningVerdict.REINFORCE).detailJson(detail).build());
    assertThat(checkId).isPositive();
    assertThat(morningChecks.findByAdvice(adviceId)).isPresent();
    assertThat(morningChecks.findByAdvice(adviceId).get().detailJson()).containsKey("index");

    assertThat(scoring.morningScores(advice, 5, ScoreStage.PROVISIONAL)).as("h=1 패스에서만").isEmpty();
    List<CallScoreRow> rows = scoring.morningScores(advice, 1, ScoreStage.PROVISIONAL);
    assertThat(rows).hasSize(2).allMatch(r -> r.subjectType() == CallSubject.MORNING);
    CallScoreRow kospi = rows.stream().filter(r -> r.subjectCode().equals("0001")).findFirst().orElseThrow();
    assertThat(kospi.status()).isEqualTo(ScoreStatus.SCORED);
    assertThat(kospi.predicted()).isEqualTo("REINFORCE");
    assertThat(kospi.actualRet()).as("합성 지수는 시가=종가라 갭 0").isEqualTo(0.0);
    assertThat(kospi.actualDir()).isEqualTo("NEUTRAL");
    assertThat(kospi.hit()).as("+갭 예상인데 갭 0 → 부호 불일치").isFalse();
    assertThat(kospi.band()).isEqualTo(0.01);
    CallScoreRow kosdaq = rows.stream().filter(r -> r.subjectCode().equals("1001")).findFirst().orElseThrow();
    assertThat(kosdaq.status()).as("예상 갭 없음").isEqualTo(ScoreStatus.MISSING);

    // HOLD 는 |실제 갭| < 임계가 적중
    jdbc.update("UPDATE tb_advisor_morning_check SET detail_json = jsonb_set(detail_json, '{index,0001,verdict}', '\"HOLD\"') WHERE check_id = ?", checkId);
    CallScoreRow hold = scoring.morningScores(advice, 1, ScoreStage.PROVISIONAL).stream().filter(r -> r.subjectCode().equals("0001")).findFirst().orElseThrow();
    assertThat(hold.hit()).isTrue();

    scoreWriter.upsertCallScores(rows);
    assertThat(kpi.morningSummary(AdviceVariant.LIVE, D.getFirst(), D.getLast()).calls()).isEqualTo(1);
  }

  @Test
  @DisplayName("advice-v2 헤더 컬럼(규칙 추세·전망·관측 기준일·적용 구간)이 JSONB 로 왕복한다")
  void headerRoundTripsTrendColumns() {
    kr.hvy.blog.modules.advisor.domain.model.MarketTrend trend = AdvicePromptBuilderTest.trend();
    AdviceHeader header = AdviceHeader.builder().runId(runId).baseDate(D.get(3)).adviceKind(AdviceHeader.KIND_DAILY).variant(AdviceVariant.LIVE).horizonDays(5)
        .regimeCode(MarketRegimeCode.NEUTRAL).kospiDir(DirectionCall.NEUTRAL).kosdaqDir(DirectionCall.NEUTRAL).pUp(0.55)
        .trendKospi(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.BULL).trendKosdaq(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.BEAR)
        .trends(List.of(trend))
        .outlooks(List.of(new kr.hvy.blog.modules.advisor.domain.model.TrendOutlook("0001", kr.hvy.blog.modules.advisor.domain.code.TrendHorizon.ABOUT_20D, 0.65,
            kr.hvy.blog.modules.advisor.domain.code.InvalidationType.BELOW_MA60)))
        .dataAsOf(Map.of("domestic", D.get(3).toString(), "globalAgeTradingDays", 1, "flowProvisional", true))
        .entryDate(D.get(4)).exitDate(D.get(8)).newsIds(List.of("N1", "N2")).dataQuality(DataQuality.OK).promptVersion("advice-v2").model("m").build();

    assertThat(adviceWriter.firstNewsAdviceDate()).isEmpty();
    long id = adviceWriter.insertHeader(header);
    AdviceHeader saved = adviceWriter.findById(id).orElseThrow();
    assertThat(saved.newsIds()).containsExactly("N1", "N2");
    assertThat(adviceWriter.firstNewsAdviceDate()).contains(D.get(3));
    adviceWriter.insertCandidates(id, List.of(CandidateRow.builder().ticker(AdvisorSyntheticData.ticker(1)).quantRank(1).quantScore(0.5).stockName("종목1")
        .marketType("KOSDAQ").benchIndexCode("1001").sectorCode("S1").signals(Map.of()).features(Map.of()).appliedLessonIds(List.of()).build()));
    adviceWriter.insertPicks(id, List.of(PickRow.builder().ticker(AdvisorSyntheticData.ticker(1)).pickRank(1).direction(PickDirection.LONG).conviction(0.7)
        .citedNews(List.of("N2")).build()));
    assertThat(adviceWriter.picks(id).getFirst().citedNews()).containsExactly("N2");

    assertThat(saved.trendKospi()).isEqualTo(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.BULL);
    assertThat(saved.trendKosdaq()).isEqualTo(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.BEAR);
    assertThat(saved.trends()).hasSize(1);
    assertThat(saved.trends().getFirst()).isEqualTo(trend);
    assertThat(saved.outlooks()).containsExactlyElementsOf(header.outlooks());
    assertThat(saved.dataAsOf()).containsEntry("domestic", D.get(3).toString()).containsEntry("globalAgeTradingDays", 1).containsEntry("flowProvisional", true);
    assertThat(saved.entryDate()).isEqualTo(D.get(4));
    assertThat(saved.exitDate()).isEqualTo(D.get(8));
    assertThat(saved.trendOf("1001")).isEqualTo(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.BEAR);
    assertThat(saved.outlookOf("0001").invalidation()).isEqualTo(kr.hvy.blog.modules.advisor.domain.code.InvalidationType.BELOW_MA60);
  }

  private long insertAdvice(LocalDate baseDate, AdviceVariant variant, List<Integer> candidateIdx, Map<Integer, PickDirection> picks) {
    AdviceHeader header = AdviceHeader.builder().runId(runId).baseDate(baseDate).adviceKind(AdviceHeader.KIND_DAILY).variant(variant).horizonDays(5)
        .regimeCode(MarketRegimeCode.RISK_ON).kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.UP).pUp(0.7)
        .leadingSectors(List.of(new SectorCall("S1", "섹터1", "r"))).dataQuality(DataQuality.OK).promptVersion("advice-v1").model("m").build();
    long id = adviceWriter.insertHeader(header);
    List<CandidateRow> candidates = candidateIdx.stream().map(i -> CandidateRow.builder().ticker(AdvisorSyntheticData.ticker(i)).quantRank(1)
        .quantScore(0.5).stockName("종목" + i).marketType(i % 2 == 0 ? "KOSPI" : "KOSDAQ").benchIndexCode(i % 2 == 0 ? "0001" : "1001")
        .sectorCode("S" + (i % 4)).signals(Map.of()).features(Map.of()).appliedLessonIds(List.of()).build()).toList();
    adviceWriter.insertCandidates(id, candidates);
    List<PickRow> pickRows = new java.util.ArrayList<>();
    int rank = 1;
    for (Map.Entry<Integer, PickDirection> e : picks.entrySet()) {
      pickRows.add(PickRow.builder().ticker(AdvisorSyntheticData.ticker(e.getKey())).pickRank(rank++).direction(e.getValue()).conviction(0.7).build());
    }
    adviceWriter.insertPicks(id, pickRows);
    return id;
  }

  private void restore(int i, int k) {
    LocalDate d = D.get(k);
    String t = AdvisorSyntheticData.ticker(i);
    Integer price = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_daily_price WHERE ticker = ? AND trade_date = ?", Integer.class, t, d);
    if (price == 0) {
      AdvisorSyntheticData.insertPrice(jdbc, t, d, AdvisorSyntheticData.close(i, k));
    }
    Integer metric = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_daily_metric WHERE ticker = ? AND trade_date = ?", Integer.class, t, d);
    if (metric == 0) {
      jdbc.update("INSERT INTO tb_stock_daily_metric (ticker, trade_date, adj_close, ret_1d, ret_20d, ma_60, tv_avg_5d, tv_avg_60d) VALUES (?, ?, ?, 0, ?, 90, 2e9, 2e9)",
          t, d, AdvisorSyntheticData.close(i, k), i / 40.0);
    }
  }
}
