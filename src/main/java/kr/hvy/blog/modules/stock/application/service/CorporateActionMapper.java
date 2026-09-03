package kr.hvy.blog.modules.stock.application.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.KisJson;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.client.KsdInfoKind;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionSource;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;
import kr.hvy.blog.modules.stock.domain.model.CorporateActionRow;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 예탁원정보 응답(맵) → 기업행사 행. 유형마다 "효력일"을 다르게 잡는다:
 * 액면교체·감자·합병은 변경상장일(list_dt, 없으면 기준일), 무상·유상증자는 권리락일(right_dt), 배당은 기준일.
 * 수정주가 계수는 효력일 "이전" 거래일에 적용되므로 효력일 선택이 틀리면 하루가 어긋난다 — 검증 단계에서 잡는다.
 */
@Slf4j
public final class CorporateActionMapper {

  private CorporateActionMapper() {
  }

  /**
   * ksdinfo 응답 행들을 기업행사 행으로 바꾼다. 종목코드·효력일이 없는 행은 버린다.
   */
  public static List<CorporateActionRow> fromKsd(KsdInfoKind kind, List<Map<String, String>> rows) {
    List<CorporateActionRow> result = new ArrayList<>(rows.size());
    for (Map<String, String> row : rows) {
      CorporateActionRow mapped = mapOne(kind, row);
      if (mapped != null) {
        result.add(mapped);
      }
    }
    return result;
  }

  private static CorporateActionRow mapOne(KsdInfoKind kind, Map<String, String> row) {
    String ticker = normalizeTicker(row.get("sht_cd"));
    if (ticker == null) {
      return null;
    }
    LocalDate recordDate = date(row.get("record_date"));
    LocalDate listDate = date(firstNonBlank(row.get("list_dt"), row.get("list_date")));
    LocalDate rightDate = date(row.get("right_dt"));
    String rawJson = KisJson.write(row);

    switch (kind) {
      case REV_SPLIT -> {
        BigDecimal before = KisValues.decimal(row.get("inter_bf_face_amt"));
        BigDecimal after = KisValues.decimal(row.get("inter_af_face_amt"));
        LocalDate effective = firstNonNull(listDate, recordDate);
        if (effective == null || before == null || after == null || before.signum() <= 0 || after.signum() <= 0) {
          return null;
        }
        CorporateActionType type = after.compareTo(before) < 0 ? CorporateActionType.SPLIT : CorporateActionType.REVERSE_SPLIT;
        return new CorporateActionRow(ticker, effective, type, before, after, null, CorporateActionSource.KSD, rawJson);
      }
      case BONUS_ISSUE -> {
        LocalDate effective = firstNonNull(rightDate, recordDate);
        BigDecimal rate = KisValues.decimal(row.get("fix_rate"));
        return effective == null ? null
            : new CorporateActionRow(ticker, effective, CorporateActionType.BONUS_ISSUE, null, rate, null,
                CorporateActionSource.KSD, rawJson);
      }
      case PAIDIN_CAPIN -> {
        LocalDate effective = firstNonNull(rightDate, recordDate);
        BigDecimal rate = KisValues.decimal(row.get("fix_rate"));
        BigDecimal price = KisValues.decimal(row.get("fix_price"));
        return effective == null ? null
            : new CorporateActionRow(ticker, effective, CorporateActionType.RIGHTS_ISSUE, null, rate, price,
                CorporateActionSource.KSD, rawJson);
      }
      case CAP_DCRS -> {
        LocalDate effective = firstNonNull(listDate, recordDate);
        BigDecimal rate = KisValues.decimal(row.get("reduce_cap_rate"));
        return effective == null ? null
            : new CorporateActionRow(ticker, effective, CorporateActionType.CAPITAL_REDUCTION, null, rate, null,
                CorporateActionSource.KSD, rawJson);
      }
      case MERGER_SPLIT -> {
        LocalDate effective = firstNonNull(listDate, recordDate);
        BigDecimal rate = KisValues.decimal(row.get("merge_rate"));
        return effective == null ? null
            : new CorporateActionRow(ticker, effective, CorporateActionType.MERGER_SPLIT, null, rate, null,
                CorporateActionSource.KSD, rawJson);
      }
      case LIST_INFO -> {
        BigDecimal qty = KisValues.decimal(row.get("issue_stk_qty"));
        BigDecimal price = KisValues.decimal(row.get("issue_price"));
        return listDate == null ? null
            : new CorporateActionRow(ticker, listDate, CorporateActionType.LISTING, null, qty, price,
                CorporateActionSource.KSD, rawJson);
      }
      case DIVIDEND -> {
        BigDecimal cash = KisValues.decimal(row.get("per_sto_divi_amt"));
        BigDecimal stockRate = KisValues.decimal(row.get("stk_divi_rate"));
        return recordDate == null ? null
            : new CorporateActionRow(ticker, recordDate, CorporateActionType.DIVIDEND, null, stockRate, cash,
                CorporateActionSource.KSD, rawJson);
      }
    }
    return null;
  }

  /**
   * 일봉 output2 힌트(락 구분·수정주가 플래그·분할비율)를 기업행사 후보로 기록한다. 주간 잡이 예탁원과 대조한다.
   */
  public static List<CorporateActionRow> fromChartHints(List<DailyPriceRow> hints) {
    List<CorporateActionRow> result = new ArrayList<>(hints.size());
    for (DailyPriceRow row : hints) {
      Map<String, Object> raw = new java.util.LinkedHashMap<>();
      raw.put("flng_cls_code", row.flngClsCode());
      raw.put("prtt_rate", row.splitRate());
      raw.put("mod_yn", row.modYn());
      raw.put("revl_issu_reas", row.revalReason());
      result.add(new CorporateActionRow(row.ticker(), row.tradeDate(), CorporateActionType.CHART_HINT, null,
          row.splitRate(), null, CorporateActionSource.CHART_HINT, KisJson.write(raw)));
    }
    return result;
  }

  /**
   * 예탁원 종목코드 정규화: 'A005930' → '005930'. 6자리 영숫자가 아니면 null.
   */
  static String normalizeTicker(String raw) {
    String value = StringUtils.trimToNull(raw);
    if (value == null) {
      return null;
    }
    if (value.length() == 7 && (value.charAt(0) == 'A' || value.charAt(0) == 'a')) {
      value = value.substring(1);
    }
    return value.matches("^[A-Za-z0-9]{6}$") ? value.toUpperCase() : null;
  }

  /**
   * 'YYYYMMDD' / 'YYYY/MM/DD' / 'YYYY-MM-DD' / 'YYYY.MM.DD' 를 관대하게 파싱한다.
   */
  static LocalDate date(String raw) {
    if (raw == null) {
      return null;
    }
    String digits = raw.replaceAll("[^0-9]", "");
    if (digits.length() < 8) {
      return null;
    }
    return KisValues.date(digits.substring(0, 8));
  }

  private static String firstNonBlank(String a, String b) {
    return StringUtils.isNotBlank(a) ? a : b;
  }

  private static LocalDate firstNonNull(LocalDate a, LocalDate b) {
    return a != null ? a : b;
  }
}
