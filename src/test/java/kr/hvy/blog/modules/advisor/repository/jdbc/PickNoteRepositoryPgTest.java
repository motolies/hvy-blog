package kr.hvy.blog.modules.advisor.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteClass;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteStatus;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.IntradayCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.PickNoteRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
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
 * 12:00 픽 노트 저장소를 실제 PostgreSQL 로 고정한다: 일괄 저장·JSONB 왕복, finalize 부호 규칙(부호 일치 CONFIRMED·불일치 REFUTED·12:00 초과 없음은 OPEN 유지 + final_excess 만),
 * 재호출 0건, findFinalized 의 (advice, ticker) 별 가장 이른 행·cutoff 필터, search 필터, UNIQUE(check_id, ticker), 판단 삭제 CASCADE.
 */
@Testcontainers
class PickNoteRepositoryPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  private static final LocalDate BASE = LocalDate.of(2026, 9, 11);

  private JdbcTemplate jdbc;
  private AdviceWriter adviceWriter;
  private IntradayCheckWriter checks;
  private PickNoteRepository notes;
  private long adviceId;
  private long checkId;

  @BeforeAll
  static void schema() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
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
    checks = new IntradayCheckWriter(jdbc);
    notes = new PickNoteRepository(jdbc);
    jdbc.update("TRUNCATE tb_advisor_advice CASCADE");
    jdbc.update("TRUNCATE tb_advisor_run CASCADE");
    long runId = jdbc.queryForObject("INSERT INTO tb_advisor_run (job_type, trigger_type, status, created_at, updated_at) "
        + "VALUES ('ADVISE', 'API', 'SUCCESS', NOW(), NOW()) RETURNING run_id", Long.class);
    adviceId = adviceWriter.insertHeader(AdviceHeader.builder().runId(runId).baseDate(BASE).adviceKind(AdviceHeader.KIND_DAILY).variant(AdviceVariant.LIVE)
        .horizonDays(5).dataQuality(DataQuality.OK).promptVersion("advice-v6").model("m").build());
    adviceWriter.insertCandidates(adviceId, List.of(candidate("T01"), candidate("T02"), candidate("T03")));
    adviceWriter.insertPicks(adviceId, List.of(pick("T01", 1, PickDirection.LONG), pick("T02", 2, PickDirection.LONG), pick("T03", 3, PickDirection.AVOID)));
    checkId = insertCheck(Instant.parse("2026-09-12T03:00:00Z"));
  }

  @Test
  @DisplayName("일괄 저장 후 JSONB·enum 이 왕복하고, finalize 는 부호 규칙으로 OPEN 행만 1회 확정한다")
  void insertAndFinalize() {
    Instant noted = Instant.parse("2026-09-12T03:00:00Z");
    int inserted = notes.insertAll(List.of(
        note("T01", noted, PickNoteClass.ON_TRACK, 0.010, 1.5, "시가 대비 +1.4%", "가정 유지", "모멘텀 상위 후보는 강세 국면에서 지속"),
        note("T02", noted, PickNoteClass.IDIOSYNCRATIC, -0.020, -2.4, "시가 대비 -2.2%", "수급 반전", null),
        note("T03", noted, PickNoteClass.FLAT, null, null, null, null, null)));
    assertThat(inserted).isEqualTo(3);

    List<PickNoteRow> saved = notes.findByAdvice(adviceId);
    assertThat(saved).hasSize(3).allMatch(n -> n.status() == PickNoteStatus.OPEN && n.finalizedAt() == null && n.noteId() != null);
    assertThat(notes.findAdviceIdsWithOpenNotes()).as("확정 전 — 보충 대상").containsExactly(adviceId);
    PickNoteRow t01 = saved.stream().filter(n -> n.ticker().equals("T01")).findFirst().orElseThrow();
    assertThat(t01.noteClass()).isEqualTo(PickNoteClass.ON_TRACK);
    assertThat(t01.direction()).isEqualTo(PickDirection.LONG);
    assertThat(t01.openPrice()).isEqualTo(70000.0);
    assertThat(t01.excessRate()).isEqualTo(0.010);
    assertThat(t01.zScore()).isEqualTo(1.5);
    assertThat(t01.tags()).containsEntry("excessBasis", "OPEN").containsEntry("offHours", false).containsEntry("signals", List.of("MOM_20D"));
    assertThat(t01.hypothesis()).isEqualTo("모멘텀 상위 후보는 강세 국면에서 지속");
    assertThat(t01.notedAt()).isEqualTo(noted);
    assertThat(t01.createdAt()).isNotNull();

    // T+5: T01 +0.03(부호 일치) → CONFIRMED, T02 +0.01(불일치) → REFUTED, T03 은 12:00 초과가 없어 OPEN 유지 + final_excess 만
    Instant finalizedAt = Instant.parse("2026-09-18T11:00:00Z");
    int finalized = notes.finalize(adviceId, Map.of("T01", 0.03, "T02", 0.01, "T03", -0.01, "T99", 0.5), finalizedAt);
    assertThat(finalized).isEqualTo(3);
    Map<String, PickNoteRow> byTicker = new LinkedHashMap<>();
    notes.findByAdvice(adviceId).forEach(n -> byTicker.put(n.ticker(), n));
    assertThat(byTicker.get("T01").status()).isEqualTo(PickNoteStatus.CONFIRMED);
    assertThat(byTicker.get("T01").finalExcess()).isEqualTo(0.03);
    assertThat(byTicker.get("T01").finalizedAt()).isEqualTo(finalizedAt);
    assertThat(byTicker.get("T02").status()).isEqualTo(PickNoteStatus.REFUTED);
    assertThat(byTicker.get("T02").finalExcess()).isEqualTo(0.01);
    assertThat(byTicker.get("T03").status()).isEqualTo(PickNoteStatus.OPEN);
    assertThat(byTicker.get("T03").finalExcess()).isEqualTo(-0.01);
    assertThat(byTicker.get("T03").finalizedAt()).isEqualTo(finalizedAt);
    assertThat(notes.findAdviceIdsWithOpenNotes()).as("OPEN 이지만 finalized_at 이 있는 T03 은 보충 대상이 아니다").isEmpty();

    // 재호출은 이미 확정된 행을 건드리지 않는다 (append-only)
    assertThat(notes.finalize(adviceId, Map.of("T01", -0.9, "T02", -0.9, "T03", 0.9), finalizedAt.plusSeconds(60))).isZero();
    assertThat(notes.findByAdvice(adviceId).stream().filter(n -> n.ticker().equals("T01")).findFirst().orElseThrow().finalExcess()).isEqualTo(0.03);
    assertThat(notes.finalize(adviceId, Map.of(), finalizedAt)).isZero();
  }

  @Test
  @DisplayName("findFinalized 는 (advice, ticker) 별 가장 이른 확정 행만(장외 점검 제외), finalized_at·noted_at ≤ cutoff 만, 기준일 창 안만 돌려준다. search 는 기간·상태·limit")
  void finalizedAndSearch() {
    Instant first = Instant.parse("2026-09-12T03:00:00Z");
    Instant rerun = Instant.parse("2026-09-12T07:10:00Z"); // 장외 수동 재실행
    notes.insertAll(List.of(
        note("T01", first, PickNoteClass.ON_TRACK, 0.010, 1.5, "a", "b", null),
        note("T02", first, PickNoteClass.IDIOSYNCRATIC, -0.020, -2.4, "a", "b", null),
        note("T03", first, PickNoteClass.FLAT, null, null, null, null, null)));
    long secondCheck = insertCheck(rerun);
    notes.insertAll(List.of(offHours(note("T01", rerun, PickNoteClass.OVERSHOOT, 0.030, 2.5, "a", "b", null).toBuilder().checkId(secondCheck).build())));
    // 09:30 KST 장외 수동 실행 — 정규 점검보다 이르지만 offHours=true 라 "가장 이른 행" 으로 집계에 들어오면 안 된다
    Instant early = Instant.parse("2026-09-12T00:30:00Z");
    long earlyCheck = insertCheck(early);
    notes.insertAll(List.of(offHours(note("T01", early, PickNoteClass.OVERSHOOT, 0.030, 2.5, "a", "b", null).toBuilder().checkId(earlyCheck).build())));
    Instant finalizedAt = Instant.parse("2026-09-18T11:00:00Z");
    assertThat(notes.finalize(adviceId, Map.of("T01", 0.02, "T02", 0.01, "T03", 0.01), finalizedAt)).isEqualTo(5);

    List<PickNoteRow> finalized = notes.findFinalized(BASE.minusDays(20), BASE, finalizedAt);
    assertThat(finalized).extracting(PickNoteRow::ticker).containsExactly("T01", "T02");
    assertThat(finalized).extracting(PickNoteRow::checkId).as("장외 점검 노트는 제외").doesNotContain(earlyCheck, secondCheck);
    PickNoteRow t01 = finalized.getFirst();
    assertThat(t01.notedAt()).as("장외 09:30 재실행을 빼고 정규 12:00 점검이 가장 이른 행").isEqualTo(first);
    assertThat(t01.noteClass()).isEqualTo(PickNoteClass.ON_TRACK);
    assertThat(t01.status()).isEqualTo(PickNoteStatus.CONFIRMED);
    assertThat(finalized.get(1).status()).isEqualTo(PickNoteStatus.REFUTED);

    assertThat(notes.findFinalized(BASE.minusDays(20), BASE, finalizedAt.minus(1, ChronoUnit.HOURS))).as("확정 전 cutoff — 미래 확정이 새지 않는다").isEmpty();
    assertThat(notes.findFinalized(BASE.plusDays(1), BASE.plusDays(5), finalizedAt)).as("기준일 창 밖").isEmpty();

    // 확정 지연 경보: finalize 뒤엔 finalized_at 이 전부 채워져 0, 재실행 노트(T01 두 번째·세 번째)까지 5행 모두 확정됐다
    assertThat(notes.countStaleOpen(BASE.plusDays(1))).isZero();
    notes.insertAll(List.of(note("T02", rerun, PickNoteClass.FLAT, null, null, null, null, null).toBuilder().checkId(secondCheck).build()));
    assertThat(notes.countStaleOpen(BASE.plusDays(1))).as("finalize 를 안 거친 행은 status 와 무관하게 센다").isEqualTo(1);
    assertThat(notes.countStaleOpen(BASE)).as("base_date 가 기준보다 이른 행만").isZero();

    assertThat(notes.search(null, null, null, 10)).hasSize(6).extracting(PickNoteRow::notedAt).containsExactly(rerun, rerun, first, first, first, early);
    assertThat(notes.search(null, null, PickNoteStatus.REFUTED, 10)).hasSize(1).first().extracting(PickNoteRow::ticker).isEqualTo("T02");
    assertThat(notes.search(BASE, BASE, PickNoteStatus.OPEN, 10)).hasSize(2).extracting(PickNoteRow::ticker).containsExactly("T02", "T03");
    assertThat(notes.search(BASE.plusDays(1), null, null, 10)).isEmpty();
    assertThat(notes.search(null, null, null, 2)).hasSize(2);
  }

  @Test
  @DisplayName("같은 점검에 같은 티커는 UNIQUE 가 막고, 판단 삭제는 점검·노트를 CASCADE 로 지운다")
  void uniqueAndCascade() {
    Instant noted = Instant.parse("2026-09-12T03:00:00Z");
    notes.insertAll(List.of(note("T01", noted, PickNoteClass.FLAT, null, null, null, null, null)));
    assertThatThrownBy(() -> notes.insertAll(List.of(note("T01", noted.plusSeconds(1), PickNoteClass.FLAT, null, null, null, null, null))))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(notes.findByAdvice(adviceId)).hasSize(1);
    assertThat(adviceWriter.delete(adviceId)).isEqualTo(1);
    assertThat(notes.findByAdvice(adviceId)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_advisor_pick_note", Integer.class)).isZero();
  }

  /** tags.offHours=true 사본 (장외 점검 노트) */
  private static PickNoteRow offHours(PickNoteRow row) {
    Map<String, Object> tags = new LinkedHashMap<>(row.tags());
    tags.put("offHours", true);
    return row.toBuilder().tags(tags).build();
  }

  private long insertCheck(Instant checkedAt) {
    return checks.insert(IntradayCheckRow.builder().adviceId(adviceId).checkedAt(checkedAt).indexJson(Map.of()).pickJson(List.of()).agreementRatio(0.5)
        .verdict(IntradayVerdict.MIXED).comment("c").build());
  }

  private PickNoteRow note(String ticker, Instant notedAt, PickNoteClass cls, Double excess, Double z, String deviation, String why, String hypothesis) {
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put("signals", deviation == null ? List.of() : List.of("MOM_20D"));
    tags.put("sector", "S1");
    tags.put("regime", "RISK_ON");
    tags.put("excessBasis", excess == null ? null : "OPEN");
    tags.put("offHours", false);
    return PickNoteRow.builder().adviceId(adviceId).checkId(checkId).ticker(ticker).baseDate(BASE).notedAt(notedAt)
        .direction(ticker.equals("T03") ? PickDirection.AVOID : PickDirection.LONG).conviction(0.7)
        .openPrice(70000.0).currentPrice(71000.0).changeRate(1.2).gapRate(-0.003).sinceOpenRate(0.0143).benchRate(0.3)
        .excessRate(excess).zScore(z).noteClass(cls).deviation(deviation).why(why).hypothesis(hypothesis).tags(tags)
        .status(PickNoteStatus.OPEN).model(deviation == null ? null : "assist").runId(7L).build();
  }

  private static CandidateRow candidate(String ticker) {
    return CandidateRow.builder().ticker(ticker).quantRank(1).quantScore(0.5).stockName("종목" + ticker).marketType("KOSPI").benchIndexCode("0001")
        .sectorCode("S1").signals(Map.of()).features(Map.of("vol20", 0.02)).appliedLessonIds(List.of()).build();
  }

  private static PickRow pick(String ticker, int rank, PickDirection direction) {
    return PickRow.builder().ticker(ticker).pickRank(rank).direction(direction).conviction(0.7).thesis("근거").riskNote("리스크").build();
  }
}
