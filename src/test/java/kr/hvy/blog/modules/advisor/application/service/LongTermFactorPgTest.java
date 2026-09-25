package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.AdvisorSyntheticData;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.LongTermFactorRow;
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
 * 장기 팩터 SQL(M8)의 PG 검증: 재무 팩터의 bitemporal PIT(available_from·first_seen_at KST 날짜 ≤ 기준일, 결산 구분, 최신 결산기·정정 회차),
 * 가격 팩터(12-1 모멘텀·저변동)의 계산, KOSPI200 PIT 후보 필터와 순위 결정론.
 * <p>
 * 합성 데이터는 30영업일이라 창을 줄여 쓴다(lookback 25·skip 5·변동성 창 20·최소 15). adj_close(t) = 100 + i·t.
 */
@Testcontainers
class LongTermFactorPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static final LocalDate BASE = AdvisorSyntheticData.BASE;
  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private LongTermScreeningService service;
  private AdvisorProperties properties;

  @BeforeAll
  static void schemaAndData() throws Exception {
    AdvisorSyntheticData.install(POSTGRES);
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    // T00: 보이는 최신 연간(202512 rev0) 이 정답. 나머지는 각각 한 가지 이유로 보이지 않아야 한다
    financial(jdbc, "T00", "202412", "Y", 0, BASE.minusDays(60), seen(BASE.minusDays(60)), 5, 100, 1);
    financial(jdbc, "T00", "202512", "Y", 0, BASE.minusDays(5), seen(BASE.minusDays(5)), 10, 80, 20);
    financial(jdbc, "T00", "202512", "Y", 1, BASE.minusDays(2), seen(BASE.plusDays(3)), 50, 50, 50);    // 정정 회차지만 우리가 본 날이 미래
    financial(jdbc, "T00", "202612", "Y", 0, BASE.plusDays(10), seen(BASE.minusDays(1)), 99, 1, 99);    // 공시 추정일이 미래
    financial(jdbc, "T00", "202606", "Q", 0, BASE.minusDays(1), seen(BASE.minusDays(1)), 77, 7, 77);    // 분기(설정은 연간)
    // T02: 공시 추정일이 기준일 다음 날 → 재무 결측
    financial(jdbc, "T02", "202512", "Y", 0, BASE.plusDays(1), seen(BASE.minusDays(10)), 30, 30, 30);
    // T06: 기준일 다음 날 00:30 KST 에 처음 봤다(UTC 로는 기준일 15:30) → KST 날짜 기준으로 결측
    financial(jdbc, "T06", "202512", "Y", 0, BASE.minusDays(3), BASE.plusDays(1).atTime(LocalTime.of(0, 30)).atZone(KST).toOffsetDateTime(), 40, 40, 40);
  }

  private static OffsetDateTime seen(LocalDate date) {
    return date.atTime(LocalTime.NOON).atZone(KST).toOffsetDateTime();
  }

  private static void financial(JdbcTemplate jdbc, String ticker, String period, String type, int revision, LocalDate availableFrom,
      OffsetDateTime firstSeen, double roe, double debt, double opGrowth) {
    jdbc.update("INSERT INTO tb_stock_financial (ticker, fiscal_period, period_type, revision_seq, available_from, available_rule, first_seen_at, "
        + "roe, debt_ratio, operating_profit_growth) VALUES (?, ?, ?, ?, ?, 'LAG_45D', ?, ?, ?, ?)",
        ticker, period, type, revision, availableFrom, firstSeen, roe, debt, opGrowth);
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    properties = new AdvisorProperties(new MockEnvironment());
    AdvisorProperties.LongTerm lt = properties.getLongTerm();
    lt.setMomLookbackDays(25);
    lt.setMomSkipDays(5);
    lt.setVolWindowDays(20);
    lt.setVolMinDays(15);
    service = new LongTermScreeningService(new NamedParameterJdbcTemplate(ds), properties);
  }

  private Map<String, LongTermFactorRow> byTicker() {
    return service.factors(BASE).stream().collect(Collectors.toMap(LongTermFactorRow::ticker, Function.identity()));
  }

  @Test
  @DisplayName("재무 PIT: available_from ≤ 기준일 그리고 first_seen_at(KST 날짜) ≤ 기준일인 연간 최신 결산기·정정 회차만 — 미래 공시·미래 관측·분기 행은 안 보인다")
  void financialFactorsArePointInTime() {
    Map<String, LongTermFactorRow> rows = byTicker();
    LongTermFactorRow t00 = rows.get("T00");
    assertThat(t00.factors().get(SignalCode.QUALITY_ROE)).isEqualTo(10.0);
    assertThat(t00.factors().get(SignalCode.QUALITY_DEBT)).isEqualTo(80.0);
    assertThat(t00.factors().get(SignalCode.OP_GROWTH)).isEqualTo(20.0);
    assertThat(t00.fiscalPeriod()).isEqualTo("202512");
    assertThat(t00.financialAsOf()).isEqualTo(BASE.minusDays(5));
    assertThat(rows.get("T02").factors().get(SignalCode.QUALITY_ROE)).as("공시 추정일이 미래").isNull();
    assertThat(rows.get("T06").factors().get(SignalCode.QUALITY_ROE)).as("KST 로 다음 날 처음 관측").isNull();
    assertThat(rows.get("T08").factors().get(SignalCode.QUALITY_ROE)).as("재무 행 없음").isNull();
  }

  @Test
  @DisplayName("가격 팩터: MOM_12_1 = ret_look − ret_skip (기준일 이하 행만), LOW_VOL_60 은 최소 표본 이상일 때만 — KOSPI200 은 PIT 이력 값")
  void priceFactors() {
    Map<String, LongTermFactorRow> rows = byTicker();
    // T02: c0 = 100 + 2·29 = 158, c_skip(rn 6 → k 24) = 148, c_look(rn 26 → k 4) = 108
    assertThat(rows.get("T02").factors().get(SignalCode.MOM_12_1)).isCloseTo(158.0 / 108 - 158.0 / 148, within(1e-9));
    assertThat(rows.get("T02").factors().get(SignalCode.LOW_VOL_60)).isNotNull();
    assertThat(rows.get("T02").adjClose()).isEqualTo(158.0);
    assertThat(rows.keySet()).as("advisor.markets=KOSPI — 짝수만").allMatch(t -> Integer.parseInt(t.substring(1)) % 2 == 0);
    assertThat(rows.get("T00").kospi200()).isTrue();
    assertThat(rows.get("T04").kospi200()).as("T04 는 KOSPI 비구성").isFalse();

    properties.getLongTerm().setVolMinDays(21);
    assertThat(byTicker().get("T02").factors().get(SignalCode.LOW_VOL_60)).as("창 20 < 최소 21 → 결측").isNull();
  }

  @Test
  @DisplayName("순위: 후보는 전부 KOSPI200 구성(PIT)이고 같은 입력이면 두 번 돌려도 같은 순위")
  void rankIsKospi200AndDeterministic() {
    properties.getLongTerm().setMinCoverage(0.5);
    LongTermScorer.Ranking first = service.rank(BASE);
    LongTermScorer.Ranking second = service.rank(BASE);
    assertThat(first.candidates()).isNotEmpty();
    assertThat(first.candidates()).extracting(CandidateRow::ticker)
        .allMatch(t -> AdvisorSyntheticData.kospi200(Integer.parseInt(t.substring(1))));
    assertThat(first.pickTickers()).isEqualTo(second.pickTickers());
    assertThat(first.candidates()).extracting(CandidateRow::quantScore).isEqualTo(second.candidates().stream().map(CandidateRow::quantScore).toList());
    List<String> tickers = first.candidates().stream().map(CandidateRow::ticker).toList();
    assertThat(tickers).doesNotContain("T04", "T10");
    assertThat(first.universeSize()).isEqualTo(byTicker().size());
  }
}
