package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionSource;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;
import kr.hvy.blog.modules.stock.domain.model.AdjustEventRow;
import kr.hvy.blog.modules.stock.domain.model.CorporateActionRow;
import kr.hvy.blog.modules.stock.repository.jdbc.AdjustEventWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.CorporateActionWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.DerivedViewRefresher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 미래 효력일 이벤트 처리 규칙: 유상증자만 효력 후 산출하고 나머지 유형은 미리 만든다 (2026-09-08 검증에서 드러난 결함의 회귀 테스트).
 * 효력일 검산(2026-10-16): 효력일이 지난 이벤트는 직전 종가 → 효력일 종가 비율이 계수의 1.5배 밴드 밖이면 저장하지 않고 기존 행을 지운다.
 */
class AdjustFactorServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);

  private final CorporateActionWriter actionWriter = mock(CorporateActionWriter.class);
  private final AdjustEventWriter eventWriter = mock(AdjustEventWriter.class);
  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final AdjustFactorService service = new AdjustFactorService(actionWriter, eventWriter,
      mock(DerivedViewRefresher.class), mock(KisMarketDataPort.class), jdbcTemplate, new KisProperties());

  @Test
  @DisplayName("derivable: 유상증자만 효력일이 asOf 이후면 보류, 나머지 유형은 미래여도 산출")
  void derivable() {
    assertThat(AdjustFactorService.derivable(split(TODAY.plusDays(30)), TODAY)).isTrue();
    assertThat(AdjustFactorService.derivable(rights(TODAY.plusDays(1)), TODAY)).isFalse();
    assertThat(AdjustFactorService.derivable(rights(TODAY), TODAY)).isTrue();
    assertThat(AdjustFactorService.derivable(rights(TODAY.minusDays(1)), TODAY)).isTrue();
  }

  @Test
  @DisplayName("deriveEvents: 미래 유상증자는 보류(deferred)되고 미래 분할·과거 유상증자는 upsert 된다")
  @SuppressWarnings("unchecked")
  void deriveEvents_defersFutureRightsIssueOnly() {
    when(actionWriter.find(eq(CorporateActionSource.KSD), any())).thenReturn(List.of(
        split(TODAY.plusDays(30)), rights(TODAY.plusDays(7)), rights(TODAY.minusDays(7))));
    when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<BigDecimal>>any(), any(), any()))
        .thenReturn(List.of(new BigDecimal("10000")));
    when(eventWriter.upsert(any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

    AdjustFactorService.DeriveResult result = service.deriveEvents(TODAY);

    assertThat(result.upserted()).isEqualTo(2);
    assertThat(result.deferred()).isEqualTo(1);
    assertThat(result.skipped()).isZero();
    ArgumentCaptor<List<AdjustEventRow>> captor = ArgumentCaptor.forClass(List.class);
    verify(eventWriter).upsert(captor.capture());
    assertThat(captor.getValue()).extracting(AdjustEventRow::effectiveDate)
        .containsExactlyInAnyOrder(TODAY.plusDays(30), TODAY.minusDays(7));
  }

  @Test
  @DisplayName("deriveEvents 효력일 검산: 38,300 → 20,600(관측 0.538)인데 계수 0.1667(r=5)이면 저장하지 않고 기존 행 삭제·implausible 기록, r=1(0.5)은 통과, 미래 효력일은 검산 생략")
  @SuppressWarnings("unchecked")
  void deriveEvents_skipsImplausibleFactorOnEffectiveDay() {
    LocalDate eff = TODAY.minusDays(30);
    when(actionWriter.find(eq(CorporateActionSource.KSD), any())).thenReturn(List.of(
        bonus("187870", eff, "5"), bonus("000020", eff, "1"), bonus("005930", TODAY.plusDays(10), "5")));
    when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.contains("trade_date < ?")), ArgumentMatchers.<RowMapper<BigDecimal>>any(), any(), any()))
        .thenReturn(List.of(new BigDecimal("38300")));
    when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.contains("trade_date >= ?")), ArgumentMatchers.<RowMapper<BigDecimal>>any(), any(), any()))
        .thenReturn(List.of(new BigDecimal("20600")));
    when(eventWriter.upsert(any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

    AdjustFactorService.DeriveResult result = service.deriveEvents(TODAY);

    assertThat(result.upserted()).isEqualTo(2);
    assertThat(result.implausible()).hasSize(1).containsKey("187870/" + eff);
    assertThat(result.implausible().get("187870/" + eff)).contains("factor=0.1666666667").contains("observed=0.5379");
    verify(eventWriter).delete("187870", eff, CorporateActionType.BONUS_ISSUE);
    verify(eventWriter, never()).delete(eq("000020"), any(), any());
    ArgumentCaptor<List<AdjustEventRow>> captor = ArgumentCaptor.forClass(List.class);
    verify(eventWriter).upsert(captor.capture());
    assertThat(captor.getValue()).extracting(AdjustEventRow::ticker).containsExactlyInAnyOrder("000020", "005930");
  }

  @Test
  @DisplayName("plausible: 가격이 없거나 0 이면 검산 불가로 통과, 비율/계수가 [1/1.5, 1.5] 밖이면 실패")
  void plausibleBand() {
    BigDecimal half = new BigDecimal("0.5");
    assertThat(AdjustFactorService.plausible(half, new BigDecimal("38300"), new BigDecimal("20600"))).isTrue();
    assertThat(AdjustFactorService.plausible(new BigDecimal("0.0099009901"), new BigDecimal("38300"), new BigDecimal("20600"))).isFalse();
    assertThat(AdjustFactorService.plausible(half, new BigDecimal("38300"), new BigDecimal("38300"))).as("효력일이 하루 어긋나면 관측 1.0 vs 0.5 → 실패").isFalse();
    assertThat(AdjustFactorService.plausible(half, null, new BigDecimal("20600"))).isTrue();
    assertThat(AdjustFactorService.plausible(half, new BigDecimal("38300"), BigDecimal.ZERO)).isTrue();
    assertThat(AdjustFactorService.plausible(null, new BigDecimal("38300"), new BigDecimal("20600"))).isTrue();
  }

  private static CorporateActionRow bonus(String ticker, LocalDate effective, String ratio) {
    return new CorporateActionRow(ticker, effective, CorporateActionType.BONUS_ISSUE, null, new BigDecimal(ratio), null, CorporateActionSource.KSD, "{}");
  }

  private static CorporateActionRow split(LocalDate effective) {
    return new CorporateActionRow("005930", effective, CorporateActionType.SPLIT, new BigDecimal("5000"),
        new BigDecimal("100"), null, CorporateActionSource.KSD, "{}");
  }

  private static CorporateActionRow rights(LocalDate effective) {
    return new CorporateActionRow("005930", effective, CorporateActionType.RIGHTS_ISSUE, null,
        new BigDecimal("0.2"), new BigDecimal("8000"), CorporateActionSource.KSD, "{}");
  }
}
