package kr.hvy.blog.modules.stock.client.macro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 공개 CSV(CBOE·재무부) 최소 파서. 값에 쉼표가 없고 헤더만 큰따옴표로 감싸는 형식이라 쉼표 분리 + 따옴표 제거로 충분하다.
 * 헤더 이름 비교는 공백을 정리한 뒤 대소문자 무시로 한다.
 */
final class SimpleCsv {

  private SimpleCsv() {
  }

  /** 헤더 + 행 */
  record Table(List<String> header, List<List<String>> rows) {

    /**
     * 열 이름 위치. 없으면 -1.
     */
    int column(String name) {
      String wanted = normalize(name);
      for (int i = 0; i < header.size(); i++) {
        if (normalize(header.get(i)).equals(wanted)) {
          return i;
        }
      }
      return -1;
    }
  }

  /**
   * 텍스트 전체를 표로 읽는다. 빈 줄은 건너뛰고, CRLF 는 LF 로 본다. 헤더가 없으면 빈 표.
   */
  static Table parse(String text) {
    List<String> header = new ArrayList<>();
    List<List<String>> rows = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return new Table(header, rows);
    }
    String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
    boolean first = true;
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      List<String> cells = split(line);
      if (first) {
        header.addAll(cells);
        first = false;
      } else {
        rows.add(cells);
      }
    }
    return new Table(header, rows);
  }

  /**
   * 셀 값을 숫자로. 비었거나 N/A·'.' 같은 결측 표기면 null (행을 만들지 않는다).
   */
  static BigDecimal decimal(String cell) {
    if (cell == null) {
      return null;
    }
    String v = cell.trim();
    if (v.isEmpty() || v.equals(".") || v.equalsIgnoreCase("N/A") || v.equalsIgnoreCase("NA") || v.equalsIgnoreCase("null")) {
      return null;
    }
    try {
      return new BigDecimal(v);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static List<String> split(String line) {
    List<String> cells = new ArrayList<>();
    for (String raw : line.split(",", -1)) {
      String v = raw.trim();
      if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
        v = v.substring(1, v.length() - 1).trim();
      }
      cells.add(v);
    }
    return cells;
  }

  private static String normalize(String s) {
    return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase();
  }
}
