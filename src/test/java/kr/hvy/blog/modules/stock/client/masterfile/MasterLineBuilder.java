package kr.hvy.blog.modules.stock.client.masterfile;

import java.util.Map;

/**
 * 테스트용 마스터 파일 줄 생성기. 지정하지 않은 필드는 공백(숫자 필드는 0)으로 채운다.
 */
final class MasterLineBuilder {

  private MasterLineBuilder() {
  }

  static String line(MasterFileLayout layout, String ticker, String standardCode, String name, Map<String, String> fields) {
    StringBuilder sb = new StringBuilder();
    sb.append(pad(ticker, MasterFileLayout.TICKER_WIDTH));
    sb.append(pad(standardCode, MasterFileLayout.STANDARD_CODE_WIDTH));
    sb.append(name);
    for (MasterFileLayout.FieldSpec spec : layout.getSpecs()) {
      String value = fields.getOrDefault(spec.name(), "");
      if (value.length() > spec.width()) {
        throw new IllegalArgumentException(spec.name() + " 값이 폭을 넘습니다: " + value);
      }
      sb.append(pad(value, spec.width()));
    }
    return sb.toString();
  }

  private static String pad(String value, int width) {
    StringBuilder sb = new StringBuilder(value);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }
}
