package kr.hvy.blog.modules.stock.client.masterfile;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 마스터 파일 고정폭 파서. 인코딩은 cp949(MS949)이며 꼬리 부분은 전부 ASCII 라 문자 수와 바이트 수가 같다.
 * 한글명은 가변 길이라 줄 끝에서 꼬리를 먼저 떼어 낸다.
 */
@Slf4j
@Component
public class MasterFileParser {

  /** cp949 의 자바 표준 이름. JDK 의 jdk.charsets 모듈에 들어 있다 */
  public static final Charset CP949 = Charset.forName("x-windows-949");

  private static final int INDEX_DIV_WIDTH = 1;
  private static final int INDEX_CODE_WIDTH = 4;
  private static final int INDEX_NAME_WIDTH = 40;
  /** 테마 마스터: 앞 3자 테마코드, 줄 끝 10자 종목코드(공식 파서 [-10:]), 가운데 가변 테마명. 실측 후 조정 */
  public static final int THEME_CODE_WIDTH = 3;
  public static final int THEME_TICKER_WIDTH = 10;

  /**
   * 종목 마스터 파일을 파싱한다. 길이가 맞지 않는 줄은 경고 후 건너뛴다(한 줄 때문에 전체를 버리지 않는다).
   */
  public List<MasterRecord> parse(byte[] content, MasterFileLayout layout) {
    List<MasterRecord> records = new ArrayList<>();
    int skipped = 0;
    for (String line : lines(content)) {
      int tailWidth = layout.getTailWidth();
      if (line.length() < tailWidth + MasterFileLayout.HEAD_FIXED_WIDTH) {
        skipped++;
        log.warn("마스터 줄 길이 부족(건너뜀): layout={}, length={}, head={}", layout, line.length(),
            line.substring(0, Math.min(20, line.length())));
        continue;
      }
      String tail = line.substring(line.length() - tailWidth);
      String head = line.substring(0, line.length() - tailWidth);
      String ticker = head.substring(0, MasterFileLayout.TICKER_WIDTH).trim();
      String standardCode = head.substring(MasterFileLayout.TICKER_WIDTH, MasterFileLayout.HEAD_FIXED_WIDTH).trim();
      String name = head.substring(MasterFileLayout.HEAD_FIXED_WIDTH).trim();
      if (ticker.isEmpty()) {
        skipped++;
        continue;
      }
      records.add(new MasterRecord(layout, ticker, standardCode, name, tail));
    }
    log.info("마스터 파일 파싱: layout={}, records={}, skipped={}", layout, records.size(), skipped);
    return records;
  }

  /**
   * 업종코드 마스터(idxcode.mst)를 파싱한다. 구조체 정의(업종코드정보.h)를 따른다.
   */
  public List<IndexCodeRecord> parseIndexCodes(byte[] content) {
    List<IndexCodeRecord> records = new ArrayList<>();
    for (String line : lines(content)) {
      if (line.length() < INDEX_DIV_WIDTH + INDEX_CODE_WIDTH) {
        continue;
      }
      String div = line.substring(0, INDEX_DIV_WIDTH);
      String code = line.substring(INDEX_DIV_WIDTH, INDEX_DIV_WIDTH + INDEX_CODE_WIDTH).trim();
      int nameEnd = Math.min(line.length(), INDEX_DIV_WIDTH + INDEX_CODE_WIDTH + INDEX_NAME_WIDTH);
      String name = line.substring(INDEX_DIV_WIDTH + INDEX_CODE_WIDTH, nameEnd).trim();
      if (code.isEmpty()) {
        continue;
      }
      records.add(new IndexCodeRecord(div, code, name));
    }
    log.info("업종코드 마스터 파싱: records={}", records.size());
    return records;
  }

  /**
   * 테마코드 마스터(theme_code.mst)를 파싱한다. 한글 테마명이 가변이라 줄 끝 10자를 먼저 떼고 앞 3자를 코드로 본다.
   */
  public List<ThemeCodeRecord> parseThemeCodes(byte[] content) {
    List<ThemeCodeRecord> records = new ArrayList<>();
    int skipped = 0;
    for (String line : lines(content)) {
      if (line.length() < THEME_CODE_WIDTH + THEME_TICKER_WIDTH) {
        skipped++;
        continue;
      }
      String code = line.substring(0, THEME_CODE_WIDTH).trim();
      String rawCode = line.substring(line.length() - THEME_TICKER_WIDTH).trim();
      String name = line.substring(THEME_CODE_WIDTH, line.length() - THEME_TICKER_WIDTH).trim();
      if (code.isEmpty() || rawCode.isEmpty()) {
        skipped++;
        continue;
      }
      records.add(new ThemeCodeRecord(code, name, rawCode));
    }
    log.info("테마코드 마스터 파싱: records={}, skipped={}", records.size(), skipped);
    return records;
  }

  private static List<String> lines(byte[] content) {
    String text = new String(content, CP949);
    List<String> result = new ArrayList<>();
    for (String line : text.split("\r?\n")) {
      if (!line.isBlank()) {
        result.add(line);
      }
    }
    return result;
  }
}
