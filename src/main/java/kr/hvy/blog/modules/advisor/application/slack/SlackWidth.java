package kr.hvy.blog.modules.advisor.application.slack;

/**
 * Slack 고정폭(코드 블록) 표의 셀 폭 계산. 한글·한자·가나·전각은 2칸, 나머지는 1칸으로 세어 패딩·절단한다.
 * String.format("%-10s") 는 문자 수 기준이라 한글이 섞이면 열이 어긋난다(2026-09-13 이전 종목 표의 결함).
 */
public final class SlackWidth {

  private SlackWidth() {
  }

  /**
   * 표시 폭(칸).
   */
  public static int width(String text) {
    if (text == null) {
      return 0;
    }
    int w = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      w += cellWidth(cp);
      i += Character.charCount(cp);
    }
    return w;
  }

  /**
   * 오른쪽에 공백을 채워 cells 폭으로 맞춘다. 넘치면 절단(… 포함).
   */
  public static String padRight(String text, int cells) {
    String cut = abbreviate(text, cells);
    StringBuilder sb = new StringBuilder(cut);
    for (int w = width(cut); w < cells; w++) {
      sb.append(' ');
    }
    return sb.toString();
  }

  /**
   * 폭이 cells 를 넘으면 마지막 1칸을 … 으로 바꿔 자른다. 전각 문자 경계에서 1칸이 남으면 공백은 넣지 않는다(패딩이 채운다).
   */
  public static String abbreviate(String text, int cells) {
    if (text == null) {
      return "";
    }
    if (width(text) <= cells) {
      return text;
    }
    int budget = Math.max(cells - 1, 0);
    StringBuilder sb = new StringBuilder();
    int w = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      int cw = cellWidth(cp);
      if (w + cw > budget) {
        break;
      }
      sb.appendCodePoint(cp);
      w += cw;
      i += Character.charCount(cp);
    }
    return sb.append('…').toString();
  }

  /**
   * 코드포인트 1개의 폭: 동아시아 전각(한글·한자·가나·전각 기호·전각 영숫자) 2, 그 밖에 1. 결합 문자는 0.
   */
  static int cellWidth(int cp) {
    if (Character.getType(cp) == Character.NON_SPACING_MARK || Character.getType(cp) == Character.ENCLOSING_MARK) {
      return 0;
    }
    if (cp >= 0xFF61 && cp <= 0xFFDC) { // 반각 가나·한글 (스크립트는 KATAKANA/HANGUL 이지만 1칸)
      return 1;
    }
    Character.UnicodeScript script = Character.UnicodeScript.of(cp);
    if (script == Character.UnicodeScript.HANGUL || script == Character.UnicodeScript.HAN
        || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
      return 2;
    }
    if ((cp >= 0x3000 && cp <= 0x303F)      // CJK 기호·구두점 (전각 공백 포함)
        || (cp >= 0xFF01 && cp <= 0xFF60)   // 전각 영숫자·기호
        || (cp >= 0xFFE0 && cp <= 0xFFE6)   // 전각 통화 기호
        || (cp >= 0x2600 && cp <= 0x27BF)   // 기호·딩벳 (▲■▼⚠ 등은 Slack 에서 대체로 2칸)
        || (cp >= 0x1F300 && cp <= 0x1FAFF)) { // 이모지
      return 2;
    }
    return 1;
  }
}
