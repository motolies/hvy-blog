package kr.hvy.blog.modules.advisor.application.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdhocAdviceRequester;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatProperties;
import kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService;
import kr.hvy.blog.modules.advisor.application.service.CandidateScreeningService;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.IntradayCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.ScoreWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * requestAdvice 도구 — 질문자 ID 가 모델이 아니라 요청 범위(ToolContext)에서 요청자로 전달되고, 결과 종류가 오류 코드·status 로 바뀐다.
 * 쓰기 도구라 읽기 전용 트랜잭션을 열지 않는다(트랜잭션 매니저 목을 건드리지 않는다).
 */
class AdviceToolkitRequestTest {

  private final AdhocAdviceRequester requester = mock(AdhocAdviceRequester.class);
  private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
  private AdviceToolkit toolkit;
  private ChatRequestScope scope;

  @BeforeEach
  void setUp() {
    AdvisorProperties advisor = new AdvisorProperties(new MockEnvironment());
    ToolSupport support = new ToolSupport(tx, mock(JdbcTemplate.class), new AdvisorChatProperties(new MockEnvironment(), advisor),
        mock(StockLookupReader.class));
    toolkit = new AdviceToolkit(support, mock(AdviceWriter.class), mock(ScoreWriter.class), mock(MorningCheckWriter.class), mock(IntradayCheckWriter.class),
        mock(CandidateScreeningService.class), mock(WeightSetRepository.class), mock(AdvisorKpiService.class), advisor, requester);
    scope = new ChatRequestScope(Instant.now().plusSeconds(60), "U1");
  }

  @Test
  @DisplayName("질문자 ID 를 요청 범위에서 꺼내 넘기고, STARTED 는 runId·kind·비동기 안내를 돌려준다")
  void startedCarriesRunId() {
    when(requester.request("U1")).thenReturn(new AdhocAdviceRequester.Outcome(AdhocAdviceRequester.Status.STARTED, 55L, null,
        LocalDate.of(2026, 9, 24), "시작"));
    Map<String, Object> result = toolkit.requestAdvice(new ToolContext(scope.toToolContext()));
    verify(requester).request("U1");
    assertThat(result).containsEntry("status", "STARTED").containsEntry("runId", 55L).containsEntry("kind", "ADHOC")
        .containsEntry("baseDate", "2026-09-24").doesNotContainKey("error");
    assertThat((String) result.get("note")).contains("지어내지 말 것");
    assertThat(scope.calls()).containsExactly("requestAdvice");
    org.mockito.Mockito.verifyNoInteractions(tx);
  }

  @Test
  @DisplayName("요청 범위가 없으면 사용자 null 로 넘겨 거부되고, 거부·한도는 forbidden·limit 오류 코드")
  void refusalsBecomeErrors() {
    when(requester.request(any())).thenReturn(new AdhocAdviceRequester.Outcome(AdhocAdviceRequester.Status.FORBIDDEN, null, null, null, "허용 안 됨"));
    assertThat(toolkit.requestAdvice(null)).containsEntry("error", ToolJson.ERROR_FORBIDDEN);
    verify(requester).request(null);

    when(requester.request("U1")).thenReturn(new AdhocAdviceRequester.Outcome(AdhocAdviceRequester.Status.LIMIT, null, null, LocalDate.of(2026, 9, 24), "한도"));
    assertThat(toolkit.requestAdvice(new ToolContext(scope.toToolContext()))).containsEntry("error", ToolJson.ERROR_LIMIT).containsEntry("message", "한도");
  }

  @Test
  @DisplayName("kind·호라이즌 파라미터 해석")
  void parameterParsing() {
    assertThat(AdviceToolkit.parseKind(null)).contains(AdviceKind.DAILY);
    assertThat(AdviceToolkit.parseKind(" morning ")).contains(AdviceKind.MORNING);
    assertThat(AdviceToolkit.parseKind("X")).isEmpty();
    assertThat(AdviceToolkit.kindOfHorizon(5)).contains(AdviceKind.DAILY);
    assertThat(AdviceToolkit.kindOfHorizon(180)).contains(AdviceKind.H180);
    assertThat(AdviceToolkit.kindOfHorizon(10)).isEmpty();
    assertThat(AdviceToolkit.kindOfHorizon(null)).isEmpty();
  }
}
