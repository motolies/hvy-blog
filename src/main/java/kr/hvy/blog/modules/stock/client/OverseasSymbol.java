package kr.hvy.blog.modules.stock.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 해외 참조 지표 심볼. 설정 문자열 "구분:거래소:심볼" 에서 만든다.
 * 구분 N(지수)·X(환율)는 FHKST03030100(거래소 없음), EQ(개별주·ETF)는 HHDFS76240000(거래소 NAS/NYS/AMS).
 */
public record OverseasSymbol(String marketDiv, String exchange, String symbol) {

  public static final String DIV_INDEX = "N";
  public static final String DIV_FX = "X";
  public static final String DIV_EQUITY = "EQ";

  /**
   * "N::.DJI", "X::FX@KRW", "EQ:NAS:NVDA" 를 파싱한다. 심볼에 ':' 는 올 수 없다.
   */
  public static OverseasSymbol parse(String spec) {
    if (spec == null) {
      throw new IllegalArgumentException("해외 심볼 설정이 비어 있습니다");
    }
    String[] parts = spec.trim().split(":", -1);
    if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) {
      throw new IllegalArgumentException("해외 심볼 형식 오류(구분:거래소:심볼): " + spec);
    }
    String div = parts[0].trim().toUpperCase();
    if (!DIV_INDEX.equals(div) && !DIV_FX.equals(div) && !DIV_EQUITY.equals(div)) {
      throw new IllegalArgumentException("해외 심볼 구분은 N/X/EQ 만 허용: " + spec);
    }
    String exchange = parts[1].trim().isEmpty() ? null : parts[1].trim().toUpperCase();
    if (DIV_EQUITY.equals(div) && exchange == null) {
      throw new IllegalArgumentException("EQ 심볼은 거래소가 필요합니다: " + spec);
    }
    return new OverseasSymbol(div, exchange, parts[2].trim());
  }

  /**
   * 설정 목록 전체를 파싱한다 (형식 오류는 기동 시 바로 드러나게 예외).
   */
  public static List<OverseasSymbol> parseAll(List<String> specs) {
    List<OverseasSymbol> result = new ArrayList<>();
    for (String spec : specs) {
      result.add(parse(spec));
    }
    return result;
  }

  public boolean isEquity() {
    return DIV_EQUITY.equals(marketDiv);
  }

  /** 체크포인트·저장 키 */
  public String key() {
    return symbol;
  }
}
