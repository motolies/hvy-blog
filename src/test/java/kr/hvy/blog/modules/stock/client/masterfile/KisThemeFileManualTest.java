package kr.hvy.blog.modules.stock.client.masterfile;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kr.hvy.blog.modules.stock.client.KisProperties;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 테마코드 마스터(theme_code.mst.zip) 레이아웃 실측. 앱키가 필요 없는 정적 파일이라 네트워크만 되면 돌아간다.
 * 앞 5줄 원문, 줄 길이 분포, 줄 끝 10자(종목코드 원문) 길이 분포를 찍어 THEME_TICKER_WIDTH 와 ThemeCodeRecord.ticker() 규칙을 확정한다.
 * <pre>
 * ./gradlew test --tests "kr.hvy.blog.modules.stock.client.masterfile.KisThemeFileManualTest"   (@Disabled 를 잠시 풀고)
 * </pre>
 */
@Slf4j
@Disabled("수동 실측용 — 네트워크 다운로드")
class KisThemeFileManualTest {

  @Test
  void dumpThemeFileLayout() {
    byte[] content = new MasterFileDownloader(new KisProperties()).download(kr.hvy.blog.modules.stock.application.service.StockMasterService.THEME_FILE);
    String text = new String(content, MasterFileParser.CP949);
    String[] lines = text.split("\r?\n");
    for (int i = 0; i < Math.min(5, lines.length); i++) {
      log.info("[THEME raw {}] len={} |{}|", i, lines[i].length(), lines[i]);
    }
    Map<Integer, Integer> lineLengths = new TreeMap<>();
    Map<Integer, Integer> codeLengths = new TreeMap<>();
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      lineLengths.merge(line.length(), 1, Integer::sum);
      if (line.length() >= MasterFileParser.THEME_TICKER_WIDTH) {
        codeLengths.merge(line.substring(line.length() - MasterFileParser.THEME_TICKER_WIDTH).trim().length(), 1, Integer::sum);
      }
    }
    log.info("[THEME] lines={}, lineLength histogram={}", lines.length, lineLengths);
    log.info("[THEME] tail-code(trim) length histogram={} (6 이면 단축코드, 7 이면 A 접두, 12 면 표준코드)", codeLengths);

    List<ThemeCodeRecord> parsed = new MasterFileParser().parseThemeCodes(content);
    long joinable = parsed.stream().filter(r -> r.ticker() != null).count();
    log.info("[THEME] parsed={}, themes={}, joinable={}, sample={}", parsed.size(),
        parsed.stream().map(ThemeCodeRecord::themeCode).distinct().count(), joinable, parsed.subList(0, Math.min(3, parsed.size())));
  }
}
