package kr.hvy.blog.modules.advisor.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.CallSubject;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import kr.hvy.blog.modules.advisor.domain.code.LessonScope;
import kr.hvy.blog.modules.advisor.domain.code.LessonStatus;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStatus;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CallScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.CitedFeature;
import kr.hvy.blog.modules.advisor.domain.model.IntradayCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.LessonRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.domain.model.SignalIcRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;
import kr.hvy.blog.modules.advisor.domain.model.SignalWeightRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.stock.repository.jdbc.BatchUpsertSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 PostgreSQL 로 검증하는 advisor 스키마·writer 테스트 (Spring 컨텍스트 미사용).
 * <p>
 * stock 스키마·파생 뷰 위에 advisor 스키마와 시드를 얹는다(스크리닝·채점 SQL 이 stock 테이블을 읽으므로 같은 DB 여야 한다).
 * 부분 유니크 인덱스(RUNNING 1개, 활성 세트 1개)·복합 FK(픽 ⊂ 후보)·JSONB 왕복은 H2 로 검증할 수 없다.
 * Docker 소켓은 colima 사용 시 DOCKER_HOST 로 지정한다(testcontainers-colima-socket 메모).
 */
@Testcontainers
class AdvisorSchemaPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  private JdbcTemplate jdbc;
  private AdviceWriter adviceWriter;
  private ScoreWriter scoreWriter;
  private WeightSetRepository weightSets;
  private SignalIcWriter icWriter;
  private LessonRepository lessons;
  private IntradayCheckWriter intradayWriter;
  private PromptInputWriter promptInputs;

  @BeforeAll
  static void schema() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      // ; 분리는 직접 하지 않는다 — 주석·달러 인용을 잘못 자른다
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-derived.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-seed.sql"));
    }
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    adviceWriter = new AdviceWriter(jdbc);
    scoreWriter = new ScoreWriter(new BatchUpsertSupport(jdbc), jdbc);
    weightSets = new WeightSetRepository(jdbc);
    icWriter = new SignalIcWriter(new BatchUpsertSupport(jdbc), jdbc);
    lessons = new LessonRepository(jdbc);
    intradayWriter = new IntradayCheckWriter(jdbc);
    promptInputs = new PromptInputWriter(jdbc);
    jdbc.update("TRUNCATE tb_advisor_advice CASCADE");
    jdbc.update("TRUNCATE tb_advisor_run CASCADE");
    jdbc.update("TRUNCATE tb_advisor_lesson, tb_advisor_signal_ic_daily");
  }

  @Test
  @DisplayName("advisor 스키마는 tb_advisor_ 접두 테이블 14개를 만들고 stock 25개는 그대로다")
  void schemaCreatesAdvisorTables() {
    assertThat(count("tb\\_advisor\\_%")).isEqualTo(14);
    assertThat(count("tb\\_stock\\_%")).isEqualTo(25);
  }

  @Test
  @DisplayName("같은 job_type 의 RUNNING run 은 부분 유니크 인덱스가 두 번째를 막는다")
  void runningPartialUniqueIndex() {
    insertRun("ADVISE", "RUNNING");
    assertThatThrownBy(() -> insertRun("ADVISE", "RUNNING")).isInstanceOf(DataIntegrityViolationException.class);
    insertRun("ADVISE", "SUCCESS"); // 종료 상태는 여러 개 가능
    insertRun("SCORE", "RUNNING");  // 다른 잡은 동시 실행 가능
  }

  @Test
  @DisplayName("시드는 SEED 세트를 활성으로 만들고 시그널 12개를 담는다. 활성 세트는 하나뿐이다")
  void seedActivatesWeightSet() {
    WeightSet active = weightSets.active().orElseThrow();
    assertThat(active.source()).isEqualTo(WeightSetSource.SEED);
    assertThat(active.weights()).hasSize(12);
    assertThat(active.enabledWeights()).hasSize(11).doesNotContainKey("GLOBAL_LINK");
    double sum = active.enabledWeights().values().stream().mapToDouble(Double::doubleValue).sum();
    assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));

    long id = weightSets.insert(active.toBuilder().source(WeightSetSource.WEEKLY).asOf(LocalDate.of(2026, 9, 12)).reason("test").build(), true);
    assertThat(weightSets.active().orElseThrow().weightSetId()).isEqualTo(id);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_advisor_weight_set WHERE is_active", Integer.class)).isEqualTo(1);

    weightSets.activate(active.weightSetId());
    assertThat(weightSets.active().orElseThrow().weightSetId()).isEqualTo(active.weightSetId());
  }

  @Test
  @DisplayName("헤더·후보·픽 저장 후 그대로 읽히고, 후보 밖 픽은 FK 가 막고, 같은 날 같은 변형은 유니크가 막는다")
  void adviceRoundTrip() {
    long runId = insertRun("ADVISE", "RUNNING");
    AdviceHeader header = header(runId, LocalDate.of(2026, 9, 11), AdviceVariant.LIVE);
    long adviceId = adviceWriter.insertHeader(header);
    List<CandidateRow> candidates = List.of(candidate("005930", 1, 0.72), candidate("000660", 2, 0.61));
    assertThat(adviceWriter.insertCandidates(adviceId, candidates)).isEqualTo(2);
    assertThat(adviceWriter.insertPicks(adviceId, List.of(pick("005930", 1)))).isEqualTo(1);

    AdviceHeader read = adviceWriter.find(LocalDate.of(2026, 9, 11), AdviceHeader.KIND_DAILY, AdviceVariant.LIVE).orElseThrow();
    assertThat(read.adviceId()).isEqualTo(adviceId);
    assertThat(read.regimeCode()).isEqualTo(MarketRegimeCode.RISK_ON);
    assertThat(read.leadingSectors()).extracting(SectorCall::code).containsExactly("G2510");
    assertThat(read.activeLessonIds()).containsExactly(3L, 7L);
    assertThat(read.guard()).containsEntry("removed", 1);
    assertThat(read.publishedAt()).isNull();

    List<CandidateRow> readCandidates = adviceWriter.candidates(adviceId);
    assertThat(readCandidates).extracting(CandidateRow::ticker).containsExactly("005930", "000660");
    assertThat(readCandidates.getFirst().signals()).containsKey("MOM_20D");
    assertThat(readCandidates.getFirst().signals().get("MOM_20D").pct()).isEqualTo(0.94);
    assertThat(readCandidates.getFirst().features()).containsEntry("ret20", 0.081);

    List<PickRow> picks = adviceWriter.picks(adviceId);
    assertThat(picks).hasSize(1);
    assertThat(picks.getFirst().cited()).extracting(CitedFeature::name).containsExactly("ret20");
    assertThat(adviceWriter.countLivePicks()).isEqualTo(1);

    adviceWriter.markPublished(adviceId, Instant.parse("2026-09-11T10:31:00Z"));
    assertThat(adviceWriter.findById(adviceId).orElseThrow().publishedAt()).isEqualTo(Instant.parse("2026-09-11T10:31:00Z"));

    assertThatThrownBy(() -> adviceWriter.insertPicks(adviceId, List.of(pick("999999", 2))))
        .as("후보 밖 티커는 복합 FK 가 막는다")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> adviceWriter.insertHeader(header))
        .as("같은 (base_date, kind, variant) 는 유니크")
        .isInstanceOf(DataIntegrityViolationException.class);

    long shadow = adviceWriter.insertHeader(header(runId, LocalDate.of(2026, 9, 11), AdviceVariant.QUANT_TOPN));
    assertThat(adviceWriter.findByBaseDate(LocalDate.of(2026, 9, 11))).extracting(AdviceHeader::variant)
        .containsExactly(AdviceVariant.LIVE, AdviceVariant.QUANT_TOPN);
    assertThat(shadow).isNotEqualTo(adviceId);

    assertThat(adviceWriter.delete(adviceId)).isEqualTo(1);
    assertThat(adviceWriter.candidates(adviceId)).as("CASCADE 로 후보·픽도 지워진다").isEmpty();
  }

  @Test
  @DisplayName("채점 upsert 는 같은 키를 덮어써 잠정 → 확정 재채점이 멱등하다")
  void scoreUpsertIsIdempotent() {
    long runId = insertRun("ADVISE", "RUNNING");
    long adviceId = adviceWriter.insertHeader(header(runId, LocalDate.of(2026, 9, 11), AdviceVariant.LIVE));
    adviceWriter.insertCandidates(adviceId, List.of(candidate("005930", 1, 0.72)));

    CandidateScoreRow provisional = CandidateScoreRow.builder().adviceId(adviceId).ticker("005930").horizonDays(5)
        .stage(ScoreStage.PROVISIONAL).status(ScoreStatus.SCORED).entryDate(LocalDate.of(2026, 9, 12)).entryPrice(70000.0)
        .exitDate(LocalDate.of(2026, 9, 18)).exitPrice(71400.0).ret(0.02).dividendRet(0).benchRet(0.005).excessRet(0.015)
        .costAdjExcess(0.012).build();
    assertThat(scoreWriter.upsertCandidateScores(List.of(provisional))).isEqualTo(1);
    assertThat(scoreWriter.provisionalAdviceIdsExitedBefore(LocalDate.of(2026, 9, 20))).containsExactly(adviceId);

    CandidateScoreRow confirmed = provisional.toBuilder().stage(ScoreStage.CONFIRMED).exitPrice(71500.0).ret(0.0214).excessRet(0.0164).build();
    scoreWriter.upsertCandidateScores(List.of(confirmed));
    List<CandidateScoreRow> read = scoreWriter.candidateScores(adviceId);
    assertThat(read).hasSize(1);
    assertThat(read.getFirst().stage()).isEqualTo(ScoreStage.CONFIRMED);
    assertThat(read.getFirst().excessRet()).isEqualTo(0.0164);
    assertThat(scoreWriter.provisionalAdviceIdsExitedBefore(LocalDate.of(2026, 9, 20))).isEmpty();

    CallScoreRow call = CallScoreRow.builder().adviceId(adviceId).subjectType(CallSubject.INDEX).subjectCode("0001").horizonDays(5)
        .stage(ScoreStage.PROVISIONAL).status(ScoreStatus.SCORED).predicted("UP").pUp(0.7).baseValue(2700.0).exitValue(2730.0)
        .actualRet(0.0111).band(0.01).actualDir("UP").hit(true).brier(0.09).build();
    assertThat(scoreWriter.upsertCallScores(List.of(call))).isEqualTo(1);
    assertThat(scoreWriter.upsertCallScores(List.of(call.toBuilder().stage(ScoreStage.CONFIRMED).build()))).isEqualTo(1);
    assertThat(scoreWriter.callScores(adviceId)).hasSize(1).first().extracting(CallScoreRow::stage).isEqualTo(ScoreStage.CONFIRMED);
  }

  @Test
  @DisplayName("IC 행은 값이 같으면 0건, 창 조회는 시그널별 최근 N일만 돌려준다")
  void signalIcWindow() {
    List<SignalIcRow> rows = List.of(
        new SignalIcRow("MOM_20D", LocalDate.of(2026, 9, 1), 5, 0.03, 1200),
        new SignalIcRow("MOM_20D", LocalDate.of(2026, 9, 2), 5, -0.01, 1210),
        new SignalIcRow("MOM_20D", LocalDate.of(2026, 9, 3), 5, 0.05, 1190),
        new SignalIcRow("TV_SURGE", LocalDate.of(2026, 9, 3), 5, 0.02, 1190));
    assertThat(icWriter.upsert(rows)).isEqualTo(4);
    assertThat(icWriter.upsert(rows)).as("IS DISTINCT FROM 으로 UPDATE 생략").isZero();
    assertThat(icWriter.maxTradeDate()).contains(LocalDate.of(2026, 9, 3));
    List<SignalIcRow> window = icWriter.window(LocalDate.of(2026, 9, 3), 2);
    assertThat(window).hasSize(3);
    assertThat(window.stream().filter(r -> r.signalCode().equals("MOM_20D")).map(SignalIcRow::tradeDate))
        .containsExactly(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3));
  }

  @Test
  @DisplayName("교훈·장중 점검·프롬프트 입력은 JSONB 를 왕복한다")
  void lessonIntradayPromptRoundTrip() {
    long runId = insertRun("ADVISE", "RUNNING");
    long adviceId = adviceWriter.insertHeader(header(runId, LocalDate.of(2026, 9, 11), AdviceVariant.LIVE));

    long lessonId = lessons.insert(LessonRow.builder().status(LessonStatus.CANDIDATE).scope(LessonScope.SIGNAL)
        .condition(Map.of("signal", "TV_SURGE", "op", ">=", "pct", 0.9, "regime", "RISK_OFF"))
        .observation("하락 국면 거래대금 급증 픽 초과수익 -1.1%").evidence(Map.of("n", 32, "t", -2.3))
        .rule("신뢰도 ≤ 0.6").lessonText("[관찰] ... [규칙] ...").appliedCount(0).model("assist").runId(runId).build());
    lessons.updateStatus(lessonId, LessonStatus.ACTIVE, Instant.parse("2026-09-13T00:00:00Z"), null);
    lessons.addApplied(lessonId, 3);
    LessonRow lesson = lessons.findByStatus(LessonStatus.ACTIVE).getFirst();
    assertThat(lesson.lessonId()).isEqualTo(lessonId);
    assertThat(lesson.condition()).containsEntry("signal", "TV_SURGE").containsEntry("pct", 0.9);
    assertThat(lesson.appliedCount()).isEqualTo(3);
    assertThat(lesson.activatedAt()).isEqualTo(Instant.parse("2026-09-13T00:00:00Z"));

    long checkId = intradayWriter.insert(IntradayCheckRow.builder().adviceId(adviceId).runId(runId).checkedAt(Instant.parse("2026-09-12T03:00:00Z"))
        .indexJson(Map.of("0001", Map.of("price", 2740.1, "changeRate", 0.31)))
        .pickJson(List.of(Map.of("ticker", "005930", "changeRate", 1.2, "agree", true)))
        .agreementRatio(1.0).verdict(IntradayVerdict.ON_TRACK).comment("유지").build());
    IntradayCheckRow check = intradayWriter.findByAdvice(adviceId).getFirst();
    assertThat(check.checkId()).isEqualTo(checkId);
    assertThat(check.pickJson()).hasSize(1);
    assertThat(check.indexJson()).containsKey("0001");

    PromptInputRow input = new PromptInputRow(runId, AdviceVariant.LIVE, "advice-v1", "abc", "{\"asOf\":\"2026-09-11\"}", "{\"model\":\"m\"}", null);
    promptInputs.upsert(input);
    promptInputs.upsert(input);
    PromptInputRow read = promptInputs.find(runId, AdviceVariant.LIVE).orElseThrow();
    assertThat(read.userPayload()).contains("2026-09-11");
    assertThat(read.rawOutput()).isNull();
    assertThat(promptInputs.deleteOlderThan(180)).isZero();
  }

  private int count(String pattern) {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name LIKE ?",
        Integer.class, pattern);
    return n == null ? 0 : n;
  }

  private long insertRun(String jobType, String status) {
    return jdbc.queryForObject("INSERT INTO tb_advisor_run (job_type, trigger_type, status, created_at, updated_at) "
        + "VALUES (?, 'API', ?, NOW(), NOW()) RETURNING run_id", Long.class, jobType, status);
  }

  private static AdviceHeader header(long runId, LocalDate baseDate, AdviceVariant variant) {
    return AdviceHeader.builder().runId(runId).baseDate(baseDate).adviceKind(AdviceHeader.KIND_DAILY).variant(variant).horizonDays(5)
        .regimeCode(MarketRegimeCode.RISK_ON).kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.NEUTRAL).pUp(0.65)
        .regimeRationale("반도체 수급").leadingSectors(List.of(new SectorCall("G2510", "반도체", "외인 순매수")))
        .summary("요약").promptVersion("advice-v1").model("judge").systemFingerprint("fp_1").weightSetId(null)
        .activeLessonIds(List.of(3L, 7L)).dataQuality(DataQuality.OK).guard(Map.of("removed", 1)).build();
  }

  private static CandidateRow candidate(String ticker, int rank, double score) {
    return CandidateRow.builder().ticker(ticker).quantRank(rank).quantScore(score).stockName("이름").marketType("KOSPI").benchIndexCode("0001")
        .sectorCode("G2510").sectorName("반도체")
        .signals(Map.of("MOM_20D", new SignalValue(0.94, 0.12, 0.081), "VALUE_RANK", new SignalValue(0.5, 0.05, null)))
        .features(Map.of("ret20", 0.081, "per", 14.2)).appliedLessonIds(List.of()).refRawClose(new BigDecimal("70000.00")).refAdjClose(70000.0).build();
  }

  private static PickRow pick(String ticker, int rank) {
    return PickRow.builder().ticker(ticker).pickRank(rank).direction(PickDirection.LONG).conviction(0.7).thesis("근거").riskNote("리스크")
        .cited(List.of(new CitedFeature("ret20", 0.081))).build();
  }
}
