package kr.hvy.blog.modules.advisor.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.chat.ChatResult;
import kr.hvy.blog.modules.advisor.application.chat.IncomingQuestion;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.model.ChatRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * tb_advisor_chat 전이·유니크·집계를 실제 PostgreSQL 로 검증한다(ON CONFLICT … RETURNING 은 H2 로 검증 불가). Docker 소켓은 colima 사용 시 DOCKER_HOST.
 */
@Testcontainers
class ChatWriterPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  private JdbcTemplate jdbc;
  private ChatWriter writer;

  @BeforeAll
  static void schema() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-derived.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-schema.sql"));
    }
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    writer = new ChatWriter(jdbc);
    jdbc.update("TRUNCATE tb_advisor_chat");
  }

  static IncomingQuestion question(String eventId, String threadTs) {
    return new IncomingQuestion(eventId, "C1", "U1", null, "1700000000.000200", threadTs, "20일 모멘텀 상위 10개");
  }

  @Test
  @DisplayName("RUNNING 으로 넣고 SUCCESS 로 닫으면 사용량·도구 목록·기준일이 남는다")
  void insertThenSuccess() {
    Optional<Long> id = writer.insertRunning(question("Ev1", "1700000000.000100"));
    assertThat(id).isPresent();
    ChatRow running = writer.findById(id.get()).orElseThrow();
    assertThat(running.status()).isEqualTo(AdvisorStatus.RUNNING);
    assertThat(running.threadTs()).isEqualTo("1700000000.000100");
    assertThat(running.messageTs()).isEqualTo("1700000000.000200");

    ChatResult result = ChatResult.builder().answer("답").model("gpt-x").promptVersion("chat-v1").historyMessages(3)
        .toolCalls(List.of("dataFreshness", "metricTopN")).promptTokens(1_000).completionTokens(200).reasoningTokens(50).cachedTokens(400)
        .costUsd(new BigDecimal("0.001234")).dataAsOf(LocalDate.of(2026, 9, 12)).build();
    writer.finishSuccess(id.get(), result, 4_321);

    ChatRow done = writer.findById(id.get()).orElseThrow();
    assertThat(done.status()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(done.answer()).isEqualTo("답");
    assertThat(done.toolCalls()).isEqualTo(2);
    assertThat(done.toolCallNames()).containsExactly("dataFreshness", "metricTopN");
    assertThat(done.promptTokens()).isEqualTo(1_000);
    assertThat(done.cachedTokens()).isEqualTo(400);
    assertThat(done.costUsd()).isEqualByComparingTo("0.001234");
    assertThat(done.dataAsOf()).isEqualTo(LocalDate.of(2026, 9, 12));
    assertThat(done.durationMs()).isEqualTo(4_321L);
    assertThat(done.updatedAt()).isAfterOrEqualTo(done.createdAt());
  }

  @Test
  @DisplayName("새 글이면 thread_ts 에 자기 ts 가 들어간다")
  void newPostThreadsUnderItself() {
    long id = writer.insertRunning(question("Ev2", null)).orElseThrow();
    assertThat(writer.findById(id).orElseThrow().threadTs()).isEqualTo("1700000000.000200");
  }

  @Test
  @DisplayName("같은 event_id 재삽입은 유니크에 막혀 빈 Optional — 예외 없음")
  void duplicateEventIsSilentlyIgnored() {
    assertThat(writer.insertRunning(question("Ev3", null))).isPresent();
    assertThat(writer.insertRunning(question("Ev3", null))).isEmpty();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_advisor_chat", Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("FAILED·SKIPPED 는 사유와 소요만 남긴다")
  void failedAndSkipped() {
    long failed = writer.insertRunning(question("Ev4", null)).orElseThrow();
    writer.finishFailed(failed, "openai 503", 10);
    long skipped = writer.insertRunning(question("Ev5", null)).orElseThrow();
    writer.finishSkipped(skipped, "일일 예산 소진", 1);
    assertThat(writer.findById(failed).orElseThrow().status()).isEqualTo(AdvisorStatus.FAILED);
    assertThat(writer.findById(failed).orElseThrow().errorMessage()).isEqualTo("openai 503");
    assertThat(writer.findById(skipped).orElseThrow().status()).isEqualTo(AdvisorStatus.SKIPPED);
  }

  @Test
  @DisplayName("tokensSince 는 기준 시각 이후 행의 입력+출력 합이고 findRecent 는 최신순이다")
  void tokensSinceAndRecent() {
    long a = writer.insertRunning(question("Ev6", null)).orElseThrow();
    writer.finishSuccess(a, ChatResult.builder().answer("a").model("m").promptVersion("v").promptTokens(100).completionTokens(10).build(), 1);
    long b = writer.insertRunning(question("Ev7", null)).orElseThrow();
    writer.finishSuccess(b, ChatResult.builder().answer("b").model("m").promptVersion("v").promptTokens(200).completionTokens(20).build(), 1);
    jdbc.update("UPDATE tb_advisor_chat SET created_at = NOW() - INTERVAL '2 day' WHERE chat_id = ?", a);

    assertThat(writer.tokensSince(Instant.now().minusSeconds(3_600))).isEqualTo(220L);
    assertThat(writer.tokensSince(Instant.now().minusSeconds(3 * 86_400))).isEqualTo(330L);
    assertThat(writer.findRecent(10)).extracting(ChatRow::chatId).containsExactly(b, a);
  }
}
