package kr.hvy.blog.modules.stock.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SCD2 snapshot_hash 를 고정값으로 핀 한다. 해시가 조용히 바뀌면 다음 MASTER 잡이 전 종목 이력을 새로 닫고 열어
 * tb_stock_master_history 가 두 배로 불어나므로, 값 자체를 회귀 대상으로 둔다.
 */
class MasterHistoryRowTest {

  private static final LocalDate VALID_FROM = LocalDate.of(2026, 9, 1);

  @Test
  @DisplayName("snapshot_hash 는 SCD 대상 컬럼의 '|' 결합 문자열 SHA-256 이며 값이 고정된다")
  void snapshotHashIsPinned() {
    MasterHistoryRow row = MasterHistoryRow.of("005930", VALID_FROM, "삼성전자", MarketType.KOSPI, "ST", "0021",
        true, true, false, false, true, 100L);

    // sha256("삼성전자|KOSPI|ST|0021|true|true|false|false|true|100")
    assertThat(row.snapshotHash()).isEqualTo("a9a0140c624ca23ecba59cfb00b6dc71c7c3e38782f67bb31cee4a9d8bb5ce73");
  }

  @Test
  @DisplayName("선택 컬럼(업종코드·상장주수)이 null 이면 빈 문자열로 해시한다")
  void nullOptionalColumnsHashAsEmpty() {
    MasterHistoryRow row = MasterHistoryRow.of("005930", VALID_FROM, "삼성전자", MarketType.KOSPI, "ST", null,
        true, true, false, false, true, null);

    // sha256("삼성전자|KOSPI|ST||true|true|false|false|true|")
    assertThat(row.snapshotHash()).isEqualTo("288b254f038cc262ba0e553634630d132af553ecb014430e3f83ef0d0f7b186c");
  }

  @Test
  @DisplayName("SCD 대상 컬럼이 하나라도 다르면 해시가 다르고, 비대상(ticker·valid_from)은 해시에 영향이 없다")
  void hashReflectsOnlyScdColumns() {
    MasterHistoryRow base = MasterHistoryRow.of("005930", VALID_FROM, "삼성전자", MarketType.KOSPI, "ST", "0021",
        true, true, false, false, true, 100L);
    MarketType otherMarket = MarketType.KOSDAQ;
    MasterHistoryRow marketChanged = MasterHistoryRow.of("005930", VALID_FROM, "삼성전자", otherMarket, "ST", "0021",
        true, true, false, false, true, 100L);
    MasterHistoryRow keyChanged = MasterHistoryRow.of("000660", VALID_FROM.plusDays(1), "삼성전자", MarketType.KOSPI,
        "ST", "0021", true, true, false, false, true, 100L);

    assertThat(marketChanged.snapshotHash()).isNotEqualTo(base.snapshotHash());
    assertThat(keyChanged.snapshotHash()).isEqualTo(base.snapshotHash());
  }
}
