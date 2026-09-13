package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.AdvisorSyntheticData;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.model.GlobalLink;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 미국 연동의 정렬·룩어헤드 회귀: 국내 d일 수익률은 미국 <b>직전 세션</b>(현지일 < d) 수익률의 0.5배로 합성해 β=0.5·corr=1 이 나와야 한다.
 * 기준일 당일 현지일의 미국 행(밤사이 결과)은 19:30 판단(links·market.global)에 절대 들어가지 않고, 다음 날 아침 점검(overnight)에는 들어간다.
 */
@Testcontainers
class GlobalLinkPgTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static final List<LocalDate> D = AdvisorSyntheticData.businessDays(LocalDate.of(2026, 1, 5), 90);
  static final LocalDate AS_OF = D.getLast();

  private NamedParameterJdbcTemplate named;
  private JdbcTemplate jdbc;
  private AdvisorProperties properties;
  private GlobalLinkService links;

  @BeforeAll
  static void schemaAndData() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-derived.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-seed.sql"));
    }
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.update("INSERT INTO tb_stock_index_master (index_code, index_name) VALUES ('0001', 'KOSPI'), ('1001', 'KOSDAQ')");
    // 미국 SPX: 짝수일 +1%, 홀수일 -1%. 국내 KOSPI: d일 수익률 = 0.5 × 미국 (d 직전 세션) 수익률
    double us = 100;
    double kr = 1000;
    double prevUs = 0;
    for (int k = 0; k < D.size(); k++) {
      double usRet = k % 2 == 0 ? 0.01 : -0.01;
      kr = kr * (1 + 0.5 * prevUs);
      us = us * (1 + usRet);
      jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) VALUES ('0001', ?, ?, ?, ?, ?), ('1001', ?, 500, 500, 500, 500)",
          D.get(k), bd(kr), bd(kr), bd(kr), bd(kr), D.get(k));
      jdbc.update("INSERT INTO tb_stock_global_market_daily (symbol, trade_date, market_div, close_price, change_rate) VALUES ('SPX', ?, 'N', ?, ?)",
          D.get(k), bd(us), bd(usRet * 100));
      prevUs = usRet;
    }
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_market_breadth_daily");
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    named = new NamedParameterJdbcTemplate(ds);
    properties = new AdvisorProperties(new MockEnvironment());
    links = new GlobalLinkService(named, properties);
  }

  @Test
  @DisplayName("직전 세션 정렬로 β=0.5·corr=1·n=60 이 나오고, 설정된 쌍 목록 순서대로 돌려준다")
  void betaFromPreviousSession() {
    GlobalLink link = links.link("0001", "SPX", AS_OF);
    assertThat(link.n()).isEqualTo(60);
    // 종가가 NUMERIC(18,4) 로 반올림돼 저장되므로 1e-6 수준 오차는 정상
    assertThat(link.beta()).isCloseTo(0.5, within(1e-4));
    assertThat(link.corr()).isCloseTo(1.0, within(1e-6));

    List<GlobalLink> all = links.links(AS_OF);
    assertThat(all).extracting(GlobalLink::krIndex, GlobalLink::usSymbol)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("0001", "SPX"), org.assertj.core.groups.Tuple.tuple("0001", "SOX"),
            org.assertj.core.groups.Tuple.tuple("1001", "COMP"), org.assertj.core.groups.Tuple.tuple("1001", "SOX"));
    assertThat(all.get(1).n()).as("데이터 없는 심볼은 n=0").isZero();
    assertThat(links.sigma1d("0001", AS_OF)).isCloseTo(0.005, within(1e-4));
  }

  @Test
  @DisplayName("룩어헤드: 기준일 현지일의 미국 행(밤사이 결과)은 19:30 판단(β·market.global)에 들어가지 않고, 다음 날 아침 overnight 에만 들어간다")
  void noLookaheadOnBaseDate() {
    GlobalLink before = links.link("0001", "SPX", AS_OF);
    // 기준일 현지일 SPX 를 +50% 로 바꿔도(밤사이 결과) 19:30 판단은 흔들리지 않아야 한다
    jdbc.update("UPDATE tb_stock_global_market_daily SET close_price = close_price * 1.5 WHERE symbol = 'SPX' AND trade_date = ?", AS_OF);
    try {
      GlobalLink after = links.link("0001", "SPX", AS_OF);
      assertThat(after.beta()).isCloseTo(before.beta(), within(1e-12));
      assertThat(after.n()).isEqualTo(before.n());

      MarketCalendarService calendar = mock(MarketCalendarService.class);
      when(calendar.isTradingDay(any())).thenAnswer(inv -> {
        LocalDate d = inv.getArgument(0);
        return d != null && d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
      });
      MarketFeatureService features = new MarketFeatureService(named, properties, new MarketTrendService(named, properties), new TradingCalendar(calendar), links);
      MarketFeatures f = features.features(AS_OF);
      assertThat(f.globalAsOf()).as("미국 T-1 = 기준일 직전 영업일").isEqualTo(D.get(D.size() - 2));
      assertThat(f.globalAgeTradingDays()).isEqualTo(1);
      MarketFeatures.GlobalFeature spx = f.global().stream().filter(g -> g.symbol().equals("SPX")).findFirst().orElseThrow();
      assertThat(spx.date()).isEqualTo(D.get(D.size() - 2));
      assertThat(spx.r1()).isCloseTo(D.size() % 2 == 0 ? 0.01 : -0.01, within(1e-5)); // D[N-2] 의 수익률 (짝수 인덱스 +1%)
      assertThat(spx.r20()).isNotNull();
      assertThat(spx.r60()).isNotNull();
      assertThat(f.links()).hasSize(4);

      // 아침 점검: 다음 영업일 07:30 에는 기준일 현지일 세션이 "밤사이 마감" 으로 들어온다
      LocalDate nextMorning = AS_OF.plusDays(AS_OF.getDayOfWeek() == DayOfWeek.FRIDAY ? 3 : 1);
      Map<String, GlobalLinkService.Overnight> overnight = links.overnight(List.of("SPX", "COMP"), nextMorning);
      assertThat(overnight).containsOnlyKeys("SPX");
      assertThat(overnight.get("SPX").date()).isEqualTo(AS_OF);
      assertThat(overnight.get("SPX").r1()).as("+50% 조작이 반영된 밤사이 수익률").isGreaterThan(0.4);
    } finally {
      jdbc.update("UPDATE tb_stock_global_market_daily SET close_price = close_price / 1.5 WHERE symbol = 'SPX' AND trade_date = ?", AS_OF);
    }
  }

  @Test
  @DisplayName("미국 휴장(국내 직전 거래일~전날 사이 세션 없음)인 국내일은 짝을 짓지 않는다 — 이미 쓴 미국 수익률을 재사용하지 않는다")
  void skipsPairsAcrossGaps() {
    // 최근 창 안의 미국 5거래일을 지워 휴장을 흉내 낸다 → 그 다음 국내 5일이 짝을 못 짓고, 나머지 짝은 그대로 정확하다
    List<LocalDate> removed = D.subList(D.size() - 30, D.size() - 25);
    for (LocalDate d : removed) {
      jdbc.update("DELETE FROM tb_stock_global_market_daily WHERE symbol = 'SPX' AND trade_date = ?", d);
    }
    try {
      GlobalLink link = links.link("0001", "SPX", AS_OF);
      assertThat(link.n()).isEqualTo(55);
      assertThat(link.beta()).as("남은 짝은 여전히 정확히 정렬돼 있다 (재사용이 있었다면 0.5 에서 크게 벗어난다; NUMERIC(18,4) 반올림 오차만 남는다)").isCloseTo(0.5, within(1e-3));
    } finally {
      // 복원: 재계산 대신 스키마 재시드는 무거우니 이 클래스의 다른 테스트가 먼저 돌아도 무방하게 값만 되돌린다
      double us = 100;
      for (int k = 0; k < D.size(); k++) {
        us = us * (1 + (k % 2 == 0 ? 0.01 : -0.01));
        if (removed.contains(D.get(k))) {
          jdbc.update("INSERT INTO tb_stock_global_market_daily (symbol, trade_date, market_div, close_price) VALUES ('SPX', ?, 'N', ?)", D.get(k), bd(us));
        }
      }
    }
  }

  private static BigDecimal bd(double v) {
    return BigDecimal.valueOf(v);
  }
}
