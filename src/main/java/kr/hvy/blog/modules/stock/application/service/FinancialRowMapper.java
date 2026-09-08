package kr.hvy.blog.modules.stock.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kr.hvy.blog.modules.stock.client.KisJson;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.domain.model.FinancialRow;
import org.apache.commons.lang3.StringUtils;

/**
 * 재무 6종(손익·대차·재무비율·성장성·수익성·안정성) 응답을 결산기(stac_yymm) 기준으로 합쳐 행을 만든다.
 * <p>
 * 소스는 {@code FinancialKind} 선언 순서로 넘어오고 나중 소스가 동명 필드를 덮는다.
 * 재무비율·성장성 양쪽의 {@code grs}, 재무비율·안정성 양쪽의 {@code lblt_rate} 는 전문 API 값이 남는다(의미 동일).
 * <p>
 * KIS 재무는 발표일이 없다. available_from 규칙: 분기 = 결산기말 + 45일(LAG_45D), 연간 = 결산기말 + 90일(LAG_90D).
 * 공시일을 확보하면 DISCLOSED 로 바꾼다. 백테스트는 max(available_from, first_seen_at) 을 써야 한다.
 */
public final class FinancialRowMapper {

  static final int QUARTER_LAG_DAYS = 45;
  static final int ANNUAL_LAG_DAYS = 90;
  static final String RULE_QUARTER = "LAG_45D";
  static final String RULE_ANNUAL = "LAG_90D";
  private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

  private FinancialRowMapper() {
  }

  /**
   * 결산기별로 소스들을 순서대로 합친다. stac_yymm 이 없는 행은 버린다.
   *
   * @param sources FinancialKind 선언 순서의 응답 목록 (나중 소스 우선)
   */
  public static List<FinancialRow> merge(String ticker, boolean quarterly, List<List<Map<String, String>>> sources) {
    Map<String, Map<String, String>> byPeriod = new TreeMap<>();
    for (List<Map<String, String>> source : sources) {
      for (Map<String, String> row : source) {
        String period = normalizePeriod(row.get("stac_yymm"));
        if (period == null) {
          continue;
        }
        byPeriod.computeIfAbsent(period, k -> new LinkedHashMap<>()).putAll(row);
      }
    }
    List<FinancialRow> rows = new ArrayList<>(byPeriod.size());
    String periodType = quarterly ? FinancialRow.PERIOD_QUARTER : FinancialRow.PERIOD_ANNUAL;
    for (Map.Entry<String, Map<String, String>> entry : byPeriod.entrySet()) {
      Map<String, String> m = entry.getValue();
      rows.add(new FinancialRow(ticker, entry.getKey(), periodType, null,
          availableFrom(entry.getKey(), quarterly), quarterly ? RULE_QUARTER : RULE_ANNUAL,
          // 손익계산서 · 대차대조표
          amount(m.get("sale_account")), amount(m.get("bsop_prti")), amount(m.get("thtr_ntin")),
          amount(m.get("total_aset")), amount(m.get("total_cptl")), amount(m.get("total_lblt")),
          // 재무비율 (grs·lblt_rate 는 성장성·안정성이 있으면 그 값)
          KisValues.decimal(m.get("roe_val")), KisValues.decimal(m.get("lblt_rate")),
          KisValues.decimal(m.get("grs")), KisValues.decimal(m.get("ntin_inrt")),
          // 성장성비율
          KisValues.decimal(m.get("bsop_prfi_inrt")), KisValues.decimal(m.get("equt_inrt")), KisValues.decimal(m.get("totl_aset_inrt")),
          // 수익성비율
          KisValues.decimal(m.get("cptl_ntin_rate")), KisValues.decimal(m.get("sale_ntin_rate")), KisValues.decimal(m.get("sale_totl_rate")),
          // 안정성비율
          KisValues.decimal(m.get("crnt_rate")), KisValues.decimal(m.get("quck_rate")), KisValues.decimal(m.get("bram_depn")),
          KisJson.write(m)));
    }
    return rows;
  }

  /**
   * 결산기말 + 지연일. 결산기(yyyyMM)의 마지막 날을 기말로 본다.
   */
  static LocalDate availableFrom(String fiscalPeriod, boolean quarterly) {
    LocalDate periodEnd = YearMonth.parse(fiscalPeriod, YYYYMM).atEndOfMonth();
    return periodEnd.plusDays(quarterly ? QUARTER_LAG_DAYS : ANNUAL_LAG_DAYS);
  }

  /**
   * '2024.03' / '202403' / '2024-03' → '202403'.
   */
  static String normalizePeriod(String raw) {
    if (StringUtils.isBlank(raw)) {
      return null;
    }
    String digits = raw.replaceAll("[^0-9]", "");
    return digits.length() >= 6 ? digits.substring(0, 6) : null;
  }

  private static Long amount(String raw) {
    BigDecimal value = KisValues.decimal(raw);
    return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).longValue();
  }
}
