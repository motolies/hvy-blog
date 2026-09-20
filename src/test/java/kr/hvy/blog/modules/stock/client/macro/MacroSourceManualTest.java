package kr.hvy.blog.modules.stock.client.macro;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.hvy.blog.modules.stock.client.MacroProperties;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import kr.hvy.blog.modules.stock.domain.model.MacroSeriesSpec;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 거시 지표 공개 CSV 실측 (네트워크 필요, 키 불필요). 무엇을 확인하나: 응답 형식이 파서와 맞는지, 그리고 <b>지금 시각에 T-1(어제 미국 현지일)이 잡히는지</b>.
 * KST 06:35 에 lagDays 가 1 이면 T-1 확보, 2 면 게시 지연(08:35 재실행이 보충). 운영 스케줄 on 전에 한 주 정도 06:35·08:35 에 돌려 본다.
 * <pre>
 * MACRO_PROBE=true ./gradlew test --tests "kr.hvy.blog.modules.stock.client.macro.MacroSourceManualTest" -i
 * </pre>
 */
class MacroSourceManualTest {

  private static final List<String> SERIES = List.of(
      "VIX:CBOE:https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv",
      "UST10Y:TREASURY:https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/{year}/all?type=daily_treasury_yield_curve&field_tdr_date_value={year}&page&_format=csv",
      "UST2Y:TREASURY:https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/{year}/all?type=daily_treasury_yield_curve&field_tdr_date_value={year}&page&_format=csv");

  @Test
  @DisplayName("CBOE·재무부 최근 10일: 행 수·마지막 관측일·오늘과의 지연을 출력한다")
  void probe() {
    Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MACRO_PROBE")), "MACRO_PROBE=true 일 때만 실행");
    MacroProperties properties = new MacroProperties();
    properties.setSeries(SERIES);
    RestClient client = RestClient.create();
    MacroSourceRouter router = new MacroSourceRouter(List.of(new CboeCsvAdapter(client, properties), new TreasuryCsvAdapter(client, properties)));
    LocalDate today = MarketClock.today();
    for (MacroSeriesSpec spec : properties.specs()) {
      SourceFetch<MacroObservation> fetched = router.fetchSeries(spec, today.minusDays(10), today);
      LocalDate latest = fetched.rows().stream().map(MacroObservation::obsDate).max(LocalDate::compareTo).orElse(null);
      System.out.printf("[macro] %s@%s rows=%d calls=%d latest=%s lagDays=%s last=%s%n", spec.series(), spec.source(), fetched.rows().size(),
          fetched.httpCalls(), latest, latest == null ? "-" : ChronoUnit.DAYS.between(latest, today),
          fetched.rows().isEmpty() ? "-" : fetched.rows().getLast().value());
      assertThat(fetched.rows()).as("%s 최근 10일에 관측치가 있어야 한다", spec.series()).isNotEmpty();
    }
  }
}
