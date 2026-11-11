package kr.hvy.blog.modules.advisor.client.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdhocAdviceRequester;
import kr.hvy.blog.modules.advisor.application.chat.tool.AdviceToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.CalendarToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.MarketToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.StockToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.ToolSupport;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService;
import kr.hvy.blog.modules.advisor.application.service.CandidateScreeningService;
import kr.hvy.blog.modules.advisor.application.service.GlobalLinkService;
import kr.hvy.blog.modules.advisor.application.service.MarketFeatureService;
import kr.hvy.blog.modules.advisor.application.service.MarketRegimeService;
import kr.hvy.blog.modules.advisor.application.service.MarketTrendService;
import kr.hvy.blog.modules.advisor.application.service.TradingCalendar;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.IntradayCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.ScoreWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import kr.hvy.blog.modules.stock.repository.jdbc.DerivedViewRefresher;
import kr.hvy.blog.modules.stock.repository.jdbc.StockNewsWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * 운영 툴킷 4종의 도구 정의가 Claude Messages API 로 나갈 수 있는 모양인지 — {@code AdvisorChatToolSchemaTest}(OpenAI function 스키마)의 Claude 쪽 짝이다.
 * 이름순 고정은 프롬프트 캐시의 회귀 가드다 — tools 는 캐시 접두의 맨 앞이라 직렬화가 한 바이트라도 달라지면 system·messages 까지 전부 miss 다.
 */
@DisplayName("채팅 도구 18종 - Claude tool 스키마 호환")
class ClaudeToolSchemaTest {

  private static final List<String> EXPECTED_IN_NAME_ORDER = List.of(
      "adviceChecks", "compareAdvice", "dataFreshness", "globalLink", "horizonPicks", "latestAdvice", "marketOverview", "marketRegime", "marketTrend",
      "metricTopN", "newsHeadlines", "performanceSummary", "priceSeries", "requestAdvice", "resolveStock", "screeningTop", "stockSnapshot", "tradingDays");

  /**
   * AdvisorChatClient 가 {@code .tools(...)} 에 넘기는 툴킷 4종의 콜백(협력 객체는 목 — 도구 정의만 본다).
   */
  private static List<ToolCallback> chatToolCallbacks() {
    AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
    ToolSupport support = mock(ToolSupport.class);
    MarketToolkit market = new MarketToolkit(support, mock(MarketFeatureService.class), mock(MarketTrendService.class), mock(GlobalLinkService.class), properties,
        mock(MarketRegimeService.class));
    StockToolkit stock = new StockToolkit(support, mock(StockLookupReader.class), mock(DerivedViewRefresher.class), mock(StockNewsWriter.class));
    AdviceToolkit advice = new AdviceToolkit(support, mock(AdviceWriter.class), mock(ScoreWriter.class), mock(MorningCheckWriter.class),
        mock(IntradayCheckWriter.class), mock(CandidateScreeningService.class), mock(WeightSetRepository.class), mock(AdvisorKpiService.class), properties,
        new StaticListableBeanFactory(Map.of("requester", mock(AdhocAdviceRequester.class))).getBeanProvider(AdhocAdviceRequester.class));
    CalendarToolkit calendar = new CalendarToolkit(support, mock(StockLookupReader.class), mock(MarketFeatureService.class), mock(AdviceWriter.class),
        mock(TradingCalendar.class));
    return List.of(ToolCallbacks.from(market, stock, advice, calendar));
  }

  /** SDK 가 전송할 때와 같은 직렬화로 만든 도구 정의(Jackson 3 트리) */
  private static JsonNode wire(Tool tool) {
    try {
      return AdvisorJson.MAPPER.readTree(ObjectMappers.jsonMapper().writeValueAsString(tool));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("툴킷 4종 → Claude 도구 18개, 이름순. 스키마는 object 타입이고 $schema 제거·ToolContext 제외·additionalProperties=false 다")
  void 도구정의_18종() {
    List<ToolCallback> callbacks = chatToolCallbacks();

    List<Tool> tools = ClaudeMessagesChatModel.tools(callbacks);

    assertThat(tools).extracting(Tool::name).containsExactlyElementsOf(EXPECTED_IN_NAME_ORDER);
    for (Tool tool : tools) {
      assertThat(tool.inputSchema()._type()).as(tool.name() + " type 은 SDK 필드로").isEqualTo(JsonValue.from("object"));
      assertThat(tool.inputSchema()._additionalProperties()).as(tool.name() + " type 키가 두 번 나가지 않는다").doesNotContainKey("type");

      JsonNode wire = wire(tool);
      assertThat(wire.path("name").asString()).isEqualTo(tool.name());
      assertThat(wire.path("description").asString()).as(tool.name() + " 설명").isNotBlank();
      JsonNode schema = wire.path("input_schema");
      assertThat(schema.path("type").asString()).as(tool.name() + " type").isEqualTo("object");
      assertThat(schema.path("$schema").isMissingNode()).as(tool.name() + " $schema 제거").isTrue();
      assertThat(schema.path("properties").isObject()).as(tool.name() + " properties").isTrue();
      assertThat(schema.path("properties").path("context").isMissingNode()).as(tool.name() + " ToolContext 제외").isTrue();
      assertThat(schema.path("required").isArray()).as(tool.name() + " required").isTrue();
      assertThat(schema.path("additionalProperties").isBoolean()).as(tool.name() + " additionalProperties").isTrue();
      assertThat(schema.path("additionalProperties").asBoolean()).as(tool.name() + " additionalProperties").isFalse();
      // 원본 스키마(Spring AI 생성)에는 $schema 가 있었다 — 변환이 실제로 제거했는지
      JsonNode original = AdvisorJson.MAPPER.readTree(callbacks.stream()
          .filter(c -> c.getToolDefinition().name().equals(tool.name())).findFirst().orElseThrow().getToolDefinition().inputSchema());
      assertThat(original.path("$schema").isMissingNode()).isFalse();
      assertThat(schema.path("properties")).as(tool.name() + " 속성 정의는 원문 그대로").isEqualTo(original.path("properties"));
      assertThat(schema.path("required")).isEqualTo(original.path("required"));
    }
  }

  @Test
  @DisplayName("콜백 등록 순서가 달라도 도구 정의의 직렬화 결과는 같다 — 프롬프트 캐시는 tools 가 바이트 단위로 같아야 맞는다")
  void 등록순서_무관() {
    List<ToolCallback> callbacks = chatToolCallbacks();
    List<ToolCallback> reversed = new ArrayList<>(callbacks);
    Collections.reverse(reversed);

    List<String> inOrder = ClaudeMessagesChatModel.tools(callbacks).stream().map(tool -> wire(tool).toString()).toList();
    List<String> fromReversed = ClaudeMessagesChatModel.tools(reversed).stream().map(tool -> wire(tool).toString()).toList();

    assertThat(fromReversed).isEqualTo(inOrder);
    assertThat(ClaudeMessagesChatModel.tools(null)).isEmpty();
    assertThat(ClaudeMessagesChatModel.tools(List.of())).isEmpty();
  }
}
