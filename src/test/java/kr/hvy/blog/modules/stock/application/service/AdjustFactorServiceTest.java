package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

  private static CorporateActionRow split(LocalDate effective) {
    return new CorporateActionRow("005930", effective, CorporateActionType.SPLIT, new BigDecimal("5000"),
        new BigDecimal("100"), null, CorporateActionSource.KSD, "{}");
  }

  private static CorporateActionRow rights(LocalDate effective) {
    return new CorporateActionRow("005930", effective, CorporateActionType.RIGHTS_ISSUE, null,
        new BigDecimal("0.2"), new BigDecimal("8000"), CorporateActionSource.KSD, "{}");
  }
}
