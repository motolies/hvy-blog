package kr.hvy.blog.modules.stock.client.masterfile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MasterFileParserTest {

  private final MasterFileParser parser = new MasterFileParser();

  @Test
  @DisplayName("레이아웃 폭 합계는 공식 파서와 같다 (KOSPI 227, KOSDAQ 221)")
  void layoutWidths() {
    assertThat(MasterFileLayout.KOSPI.getTailWidth()).isEqualTo(227);
    assertThat(MasterFileLayout.KOSPI.getSpecs()).hasSize(70);
    assertThat(MasterFileLayout.KOSDAQ.getTailWidth()).isEqualTo(221);
    assertThat(MasterFileLayout.KOSDAQ.getSpecs()).hasSize(64);
    assertThat(MasterFileLayout.KOSPI.has(MasterFileLayout.KOSPI200_SECTOR)).isTrue();
    assertThat(MasterFileLayout.KOSDAQ.has(MasterFileLayout.KOSPI200_SECTOR)).isFalse();
    assertThat(MasterFileLayout.KOSDAQ.has(MasterFileLayout.KOSDAQ150)).isTrue();
  }

  @Test
  @DisplayName("코스피 줄을 파싱해 코드·한글명·꼬리 필드를 꺼낸다")
  void parseKospiLine() {
    String line = MasterLineBuilder.line(MasterFileLayout.KOSPI, "005930", "KR7005930003", "삼성전자", Map.of(
        MasterFileLayout.GROUP_CODE, "ST",
        MasterFileLayout.SECTOR_LARGE, "0001",
        MasterFileLayout.SECTOR_MID, "0021",
        MasterFileLayout.SECTOR_SMALL, "0021",
        MasterFileLayout.KOSPI200_SECTOR, "5",
        MasterFileLayout.LISTING_DATE, "19750611",
        MasterFileLayout.LISTED_SHARES, "5969782550",
        MasterFileLayout.CAPITAL, "897514",
        MasterFileLayout.PAR_VALUE, "100",
        MasterFileLayout.KRX300, "Y"));
    byte[] bytes = (line + "\r\n").getBytes(MasterFileParser.CP949);

    List<MasterRecord> records = parser.parse(bytes, MasterFileLayout.KOSPI);

    assertThat(records).hasSize(1);
    MasterRecord r = records.get(0);
    assertThat(r.ticker()).isEqualTo("005930");
    assertThat(r.standardCode()).isEqualTo("KR7005930003");
    assertThat(r.name()).isEqualTo("삼성전자");
    assertThat(r.marketType()).isEqualTo(MarketType.KOSPI);
    assertThat(r.isStock()).isTrue();
    assertThat(r.sectorMid()).isEqualTo("0021");
    assertThat(r.kospi200Sector()).isEqualTo("5");
    assertThat(r.isKospi200()).isTrue();
    assertThat(r.isKrx300()).isTrue();
    assertThat(r.listingDate()).isEqualTo(LocalDate.of(1975, 6, 11));
    assertThat(r.listedShares()).isEqualTo(5_969_782_550L);
    assertThat(r.capital()).isEqualTo(897_514L);
    assertThat(r.parValue()).isEqualByComparingTo(BigDecimal.valueOf(100));
    assertThat(r.isSuspended()).isFalse();
  }

  @Test
  @DisplayName("코스닥 줄은 KOSPI200 필드가 없어 null 이고 KOSDAQ150 플래그를 읽는다")
  void parseKosdaqLine() {
    String line = MasterLineBuilder.line(MasterFileLayout.KOSDAQ, "247540", "KR7247540008", "에코프로비엠", Map.of(
        MasterFileLayout.GROUP_CODE, "ST",
        MasterFileLayout.SECTOR_MID, "1015",
        MasterFileLayout.KOSDAQ150, "Y",
        MasterFileLayout.SUSPENDED, "Y",
        MasterFileLayout.ADMINISTRATIVE, "N"));

    List<MasterRecord> records = parser.parse(line.getBytes(MasterFileParser.CP949), MasterFileLayout.KOSDAQ);

    MasterRecord r = records.get(0);
    assertThat(r.name()).isEqualTo("에코프로비엠");
    assertThat(r.marketType()).isEqualTo(MarketType.KOSDAQ);
    assertThat(r.kospi200Sector()).isNull();
    assertThat(r.isKospi200()).isFalse();
    assertThat(r.flag(MasterFileLayout.KOSDAQ150)).isTrue();
    assertThat(r.isSuspended()).isTrue();
    assertThat(r.isAdministrative()).isFalse();
    assertThat(r.get("noSuchField")).isNull();
  }

  @Test
  @DisplayName("길이가 모자란 줄은 건너뛰고 나머지는 파싱한다")
  void skipsMalformedLines() {
    String good = MasterLineBuilder.line(MasterFileLayout.KOSPI, "000660", "KR7000660001", "SK하이닉스",
        Map.of(MasterFileLayout.GROUP_CODE, "ST"));
    String bad = "000020   KR7000020008동화약품 short";
    byte[] bytes = (bad + "\n" + good + "\n\n").getBytes(MasterFileParser.CP949);

    List<MasterRecord> records = parser.parse(bytes, MasterFileLayout.KOSPI);

    assertThat(records).extracting(MasterRecord::ticker).containsExactly("000660");
  }

  @Test
  @DisplayName("업종코드 마스터는 시장구분 1 + 코드 4 + 이름 40 구조다")
  void parseIndexCodes() {
    String lines = "00001종합                                    \n" + "01001코스닥 종합\n" + "\n";
    List<IndexCodeRecord> codes = parser.parseIndexCodes(lines.getBytes(MasterFileParser.CP949));

    assertThat(codes).hasSize(2);
    assertThat(codes.get(0)).isEqualTo(new IndexCodeRecord("0", "0001", "종합"));
    assertThat(codes.get(1)).isEqualTo(new IndexCodeRecord("0", "1001", "코스닥 종합"));
  }
}
