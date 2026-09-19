package kr.hvy.blog.modules.advisor.application.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService;
import kr.hvy.blog.modules.advisor.application.service.CandidateScreeningService;
import kr.hvy.blog.modules.advisor.application.service.GlobalLinkService;
import kr.hvy.blog.modules.advisor.application.service.MarketFeatureService;
import kr.hvy.blog.modules.advisor.application.service.MarketTrendService;
import kr.hvy.blog.modules.advisor.application.service.TradingCalendar;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.dto.FunctionTool;
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
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * 운영 툴킷 4종의 도구 정의가 Responses API 로 나갈 수 있는 모양인지 — 이름 14개, 스키마는 object·additionalProperties=false·ToolContext 제외·$schema 제거.
 * 인자 이름 바인딩은 컴파일 {@code -parameters}(Boot Gradle 플러그인이 부여)에 기대므로 이 테스트가 그 회귀 가드다.
 */
@DisplayName("채팅 도구 14종 - Responses function 스키마 호환")
class AdvisorChatToolSchemaTest {

  private static final List<String> EXPECTED = List.of(
      "marketOverview", "marketTrend", "globalLink",
      "resolveStock", "stockSnapshot", "priceSeries", "metricTopN", "newsHeadlines",
      "latestAdvice", "adviceChecks", "screeningTop", "performanceSummary",
      "dataFreshness", "tradingDays");

  @Test
  @DisplayName("툴킷 4종 → 도구 14개, 이름 중복 없음, 스키마는 strict 아닌 function 정의로 변환된다")
  void 도구정의_14종() {
    AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
    ToolSupport support = mock(ToolSupport.class);
    MarketToolkit market = new MarketToolkit(support, mock(MarketFeatureService.class), mock(MarketTrendService.class), mock(GlobalLinkService.class), properties);
    StockToolkit stock = new StockToolkit(support, mock(StockLookupReader.class), mock(DerivedViewRefresher.class), mock(StockNewsWriter.class));
    AdviceToolkit advice = new AdviceToolkit(support, mock(AdviceWriter.class), mock(ScoreWriter.class), mock(MorningCheckWriter.class),
        mock(IntradayCheckWriter.class), mock(CandidateScreeningService.class), mock(WeightSetRepository.class), mock(AdvisorKpiService.class), properties);
    CalendarToolkit calendar = new CalendarToolkit(support, mock(StockLookupReader.class), mock(MarketFeatureService.class), mock(AdviceWriter.class),
        mock(TradingCalendar.class));

    ToolCallback[] callbacks = ToolCallbacks.from(market, stock, advice, calendar);

    assertThat(callbacks).hasSize(14);
    List<String> names = Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()).toList();
    assertThat(names).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(EXPECTED);

    List<FunctionTool> tools = OpenAiResponsesChatModel.functionTools(List.of(callbacks));
    assertThat(tools).hasSize(14);
    for (FunctionTool tool : tools) {
      assertThat(tool.type()).isEqualTo("function");
      assertThat(tool.description()).as(tool.name() + " 설명").isNotBlank();
      assertThat(tool.strict()).isFalse();
      JsonNode parameters = tool.parameters();
      assertThat(parameters.path("$schema").isMissingNode()).as(tool.name() + " $schema 제거").isTrue();
      assertThat(parameters.path("type").asString()).isEqualTo("object");
      assertThat(parameters.path("additionalProperties").asString()).as(tool.name() + " additionalProperties").isEqualTo("false");
      assertThat(parameters.path("properties").path("context").isMissingNode()).as(tool.name() + " ToolContext 제외").isTrue();
      assertThat(parameters.path("required").isArray()).isTrue();
      // 원본 스키마(Spring AI 생성)에는 $schema 가 있었다 — 변환이 실제로 제거했는지
      JsonNode original = AdvisorJson.MAPPER.readTree(Arrays.stream(callbacks)
          .filter(c -> c.getToolDefinition().name().equals(tool.name())).findFirst().orElseThrow().getToolDefinition().inputSchema());
      assertThat(original.path("$schema").isMissingNode()).isFalse();
    }
  }
}
