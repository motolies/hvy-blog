package kr.hvy.blog.modules.stock.client.macro;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.client.MacroProperties;
import kr.hvy.blog.modules.stock.domain.code.MacroSeries;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import kr.hvy.blog.modules.stock.domain.model.MacroSeriesSpec;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * CBOE 일별 지수 CSV ({@code DATE,OPEN,HIGH,LOW,CLOSE}, MM/dd/yyyy, 1990~ 전체 이력 한 파일, 마감 후 당일 갱신 — 2026-09-20 실측).
 * 파일이 전체 이력이라 증분도 통째로 받되 [from, to] 만 남긴다(≈500KB, 하루 2회면 무시할 양).
 */
@Slf4j
@Component
public class CboeCsvAdapter implements MacroCsvSource {

  static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

  private final RestClient restClient;
  private final MacroProperties properties;

  public CboeCsvAdapter(@Qualifier("macroRestClient") RestClient restClient, MacroProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  @Override
  public MacroSource source() {
    return MacroSource.CBOE;
  }

  @Override
  public SourceFetch<MacroObservation> fetch(MacroSeriesSpec spec, LocalDate from, LocalDate to) {
    String csv = restClient.get().uri(URI.create(spec.url())).header("User-Agent", properties.getUserAgent()).retrieve().body(String.class);
    return new SourceFetch<>(parse(csv, spec.series(), from, to, properties.getAvailableLagDays()), 1);
  }

  /**
   * CSV 본문 → 관측치. DATE 열과 시리즈 열(CLOSE)을 헤더 이름으로 찾고, 날짜·값이 안 읽히는 행은 버린다.
   */
  static List<MacroObservation> parse(String csv, MacroSeries series, LocalDate from, LocalDate to, int availableLagDays) {
    SimpleCsv.Table table = SimpleCsv.parse(csv);
    int dateCol = table.column("DATE");
    int valueCol = table.column(series.getColumn());
    if (dateCol < 0 || valueCol < 0) {
      throw new IllegalStateException("CBOE CSV 헤더에 DATE 또는 " + series.getColumn() + " 열이 없습니다: " + table.header());
    }
    List<MacroObservation> rows = new ArrayList<>();
    for (List<String> cells : table.rows()) {
      if (cells.size() <= Math.max(dateCol, valueCol)) {
        continue;
      }
      LocalDate date = date(cells.get(dateCol));
      BigDecimal value = SimpleCsv.decimal(cells.get(valueCol));
      if (date == null || value == null || date.isBefore(from) || date.isAfter(to)) {
        continue;
      }
      rows.add(new MacroObservation(series, date, value, MacroSource.CBOE, date.plusDays(availableLagDays)));
    }
    return rows;
  }

  private static LocalDate date(String cell) {
    try {
      return LocalDate.parse(cell.trim(), DATE);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
