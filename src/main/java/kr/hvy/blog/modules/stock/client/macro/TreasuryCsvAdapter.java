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
 * 미국 재무부 일별 국채 수익률 CSV — 연도별 파일, 헤더 {@code Date,"1 Mo",…,"2 Yr",…,"10 Yr",…}, MM/dd/yyyy, 최신순 (2026-09-20 실측).
 * 미국 현지 15:30~17:00 ET 게시라 KST 06:35 수집에서 T-1 이 잡힌다. URL 의 {@code {year}} 를 연도로 치환해 [from, to] 가 걸친 해를 전부 받는다.
 * 만기 열은 시리즈가 안다({@link MacroSeries#getColumn()}: "10 Yr", "2 Yr").
 */
@Slf4j
@Component
public class TreasuryCsvAdapter implements MacroCsvSource {

  static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");
  static final String YEAR_PLACEHOLDER = "{year}";

  private final RestClient restClient;
  private final MacroProperties properties;

  public TreasuryCsvAdapter(@Qualifier("macroRestClient") RestClient restClient, MacroProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  @Override
  public MacroSource source() {
    return MacroSource.TREASURY;
  }

  @Override
  public SourceFetch<MacroObservation> fetch(MacroSeriesSpec spec, LocalDate from, LocalDate to) {
    List<MacroObservation> rows = new ArrayList<>();
    int calls = 0;
    for (int year = from.getYear(); year <= to.getYear(); year++) {
      String url = spec.url().replace(YEAR_PLACEHOLDER, String.valueOf(year));
      String csv = restClient.get().uri(URI.create(url)).header("User-Agent", properties.getUserAgent()).retrieve().body(String.class);
      calls++;
      rows.addAll(parse(csv, spec.series(), from, to, properties.getAvailableLagDays()));
    }
    return new SourceFetch<>(rows, calls);
  }

  /**
   * CSV 본문 → 관측치. Date 열과 만기 열을 헤더 이름으로 찾는다. 공휴일·결측(빈 값·N/A)은 행을 만들지 않는다.
   */
  static List<MacroObservation> parse(String csv, MacroSeries series, LocalDate from, LocalDate to, int availableLagDays) {
    SimpleCsv.Table table = SimpleCsv.parse(csv);
    int dateCol = table.column("Date");
    int valueCol = table.column(series.getColumn());
    if (dateCol < 0 || valueCol < 0) {
      throw new IllegalStateException("재무부 CSV 헤더에 Date 또는 " + series.getColumn() + " 열이 없습니다: " + table.header());
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
      rows.add(new MacroObservation(series, date, value, MacroSource.TREASURY, date.plusDays(availableLagDays)));
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
