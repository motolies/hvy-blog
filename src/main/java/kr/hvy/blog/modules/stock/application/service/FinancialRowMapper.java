package kr.hvy.blog.modules.stock.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
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
 * <p>
 * 연간(Y)은 결산월 행만 남긴다(2026-10-16): KIS 연간 응답은 진행 중 회계연도의 최근 중간기간(12월 결산의 202606)을 끝에 붙여 주는데,
 * 이를 Y 로 저장하면 장기 팩터(period_type Y 최신)가 반기 누적을 연간으로 쓴다. 분기(Q) 손익은 회계연도 누적(YTD)이다.
 * 비율은 KIS 의 0.00(공란 표기)을 NULL 로 읽는다 — 부채·유동·당좌비율·매출총이익률은 구조적으로 0 이 나올 수 없고, 수익성·성장률이
 * 정확히 0.00 인 경우는 드물어 결측으로 봐도 잃는 정보가 0 근처 값뿐이다. 차입금 의존도(bram_depn)만 무차입 기업의 실제 0 이 흔해 0 을 유지한다.
 * 금액은 0 을 그대로 둔다. 금액 단위는 억원(KIS 원천 그대로, 삼성전자 202512 매출 3,336,059 = 333.6조).
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
   * 결산월을 모를 때의 합치기 — 연간 행을 거르지 않는다({@link #merge(String, boolean, String, List)} 의 settleMonth null).
   *
   * @param sources FinancialKind 선언 순서의 응답 목록 (나중 소스 우선)
   */
  public static List<FinancialRow> merge(String ticker, boolean quarterly, List<List<Map<String, String>>> sources) {
    return merge(ticker, quarterly, null, sources);
  }

  /**
   * 결산기별로 소스들을 순서대로 합친다. stac_yymm 이 없는 행은 버리고, 연간이면 결산월이 아닌 중간기간 행을 버린다({@link #keepAnnual}).
   *
   * @param settleMonth 결산월(tb_stock_master.settle_month, "12"·"3" 등). null·형식 밖이면 연간 행을 거르지 않는다
   * @param sources     FinancialKind 선언 순서의 응답 목록 (나중 소스 우선)
   */
  public static List<FinancialRow> merge(String ticker, boolean quarterly, String settleMonth,
      List<List<Map<String, String>>> sources) {
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
    String month = quarterly ? null : normalizeSettleMonth(settleMonth);
    for (Map.Entry<String, Map<String, String>> entry : byPeriod.entrySet()) {
      if (month != null && !keepAnnual(entry.getKey(), month, byPeriod.keySet())) {
        continue;
      }
      Map<String, String> m = entry.getValue();
      rows.add(new FinancialRow(ticker, entry.getKey(), periodType, null,
          availableFrom(entry.getKey(), quarterly), quarterly ? RULE_QUARTER : RULE_ANNUAL,
          // 손익계산서 · 대차대조표
          amount(m.get("sale_account")), amount(m.get("bsop_prti")), amount(m.get("thtr_ntin")),
          amount(m.get("total_aset")), amount(m.get("total_cptl")), amount(m.get("total_lblt")),
          // 재무비율 (grs·lblt_rate 는 성장성·안정성이 있으면 그 값). 비율 0.00 은 KIS 공란 표기 → NULL
          ratio(m.get("roe_val")), ratio(m.get("lblt_rate")), ratio(m.get("grs")), ratio(m.get("ntin_inrt")),
          // 성장성비율
          ratio(m.get("bsop_prfi_inrt")), ratio(m.get("equt_inrt")), ratio(m.get("totl_aset_inrt")),
          // 수익성비율
          ratio(m.get("cptl_ntin_rate")), ratio(m.get("sale_ntin_rate")), ratio(m.get("sale_totl_rate")),
          // 안정성비율 — 차입금 의존도는 무차입 0 이 실제 값이라 0 유지
          ratio(m.get("crnt_rate")), ratio(m.get("quck_rate")), KisValues.decimal(m.get("bram_depn")),
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

  /**
   * 연간 행 보존 판정: 결산월 행은 남기고, 결산월이 아닌 행은 그보다 뒤에 결산월 행이 있을 때(결산월을 바꾸기 전 옛 회계연도)만 남긴다.
   * 그 밖은 진행 중 회계연도의 중간기간(12월 결산 202606, 3월 결산 202512)이라 버린다.
   */
  static boolean keepAnnual(String fiscalPeriod, String settleMonth, Collection<String> periods) {
    if (fiscalPeriod.endsWith(settleMonth)) {
      return true;
    }
    return periods.stream().anyMatch(p -> p.endsWith(settleMonth) && p.compareTo(fiscalPeriod) > 0);
  }

  /**
   * 결산월 정규화: "12"·"3"·"03" → 두 자리. 01~12 밖이거나 비어 있으면 null(거르지 않음).
   */
  static String normalizeSettleMonth(String raw) {
    String trimmed = StringUtils.trimToNull(raw);
    if (trimmed == null || !trimmed.matches("\\d{1,2}")) {
      return null;
    }
    String month = StringUtils.leftPad(trimmed, 2, '0');
    return month.matches("0[1-9]|1[0-2]") ? month : null;
  }

  private static BigDecimal ratio(String raw) {
    return KisValues.decimalNonZero(raw);
  }

  private static Long amount(String raw) {
    BigDecimal value = KisValues.decimal(raw);
    return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).longValue();
  }
}
