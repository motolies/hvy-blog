package kr.hvy.blog.modules.stock.client.masterfile;

/**
 * 테마코드 마스터(theme_code.mst) 한 줄: [테마코드 3][테마명 가변][종목코드 10]. 한 종목이 여러 테마에 속한다(N:M).
 *
 * @param themeCode 테마코드 3자
 * @param themeName 테마명
 * @param rawCode   줄 끝 10자 종목코드 원문(trim). 6자 단축코드인지 다른 체계인지는 실측 항목이라 원문을 보관한다
 */
public record ThemeCodeRecord(String themeCode, String themeName, String rawCode) {

  /**
   * 마스터 조인용 6자 단축코드. 6자면 그대로, 'A'+6자(KRX 표기)면 A 를 뗀다. 그 외 형식은 null(집계 후 규칙 확정).
   */
  public String ticker() {
    if (rawCode == null) {
      return null;
    }
    String code = rawCode.trim();
    if (code.length() == 6) {
      return code;
    }
    if (code.length() == 7 && code.charAt(0) == 'A') {
      return code.substring(1);
    }
    return null;
  }
}
