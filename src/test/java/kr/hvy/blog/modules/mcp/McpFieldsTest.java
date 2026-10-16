package kr.hvy.blog.modules.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공개 컬럼 투영의 별칭 계층(2026-10-16): {@code col AS alias} 는 응답 키만 바꾸고 물리 컬럼명은 필터·날짜·커서 키에 그대로 쓰인다.
 */
class McpFieldsTest {

  @Test
  @DisplayName("별칭 파싱: columns 는 SELECT 목록 그대로, names 는 물리 컬럼, published 는 응답 키, aliases 는 물리→응답 매핑")
  void aliasLayer() {
    assertThat(McpFields.physical("change_rate AS change_rate_pct")).isEqualTo("change_rate");
    assertThat(McpFields.alias("change_rate AS change_rate_pct")).isEqualTo("change_rate_pct");
    assertThat(McpFields.physical("ticker")).isEqualTo("ticker");
    assertThat(McpFields.alias("ticker")).isEqualTo("ticker");
    assertThat(McpFields.columns("vw_stock_daily_price_adj")).contains("change_rate AS change_rate_pct");
    assertThat(McpFields.names("vw_stock_daily_price_adj")).contains("change_rate").doesNotContain("change_rate_pct");
    assertThat(McpFields.published("vw_stock_daily_price_adj")).contains("change_rate_pct").doesNotContain("change_rate");
    assertThat(McpFields.aliases("tb_advisor_pick_note")).containsEntry("change_rate", "change_rate_pct").containsEntry("bench_rate", "bench_rate_pct")
        .containsEntry("excess_rate", "excess_rate");
  }

  @Test
  @DisplayName("listAdvices 요약 투영은 전체 투영의 부분집합이고 JSON 블록 컬럼이 없다")
  void adviceSummaryProjectionIsSubset() {
    var full = McpFields.published("tb_advisor_advice");
    var summary = McpFields.published("tb_advisor_advice:summary");
    assertThat(full).containsAll(summary);
    assertThat(summary).contains("advice_id", "base_date", "summary", "prompt_version").doesNotContain("trend_json", "regime_json", "guard_json", "diff_json", "regime_rationale");
  }

  @Test
  @DisplayName("% 컬럼은 전부 _pct 로 나가고, 별칭 대상이 어떤 소스의 필터·날짜·커서 키 컬럼도 아니다(page() 가 물리명으로 WHERE/ORDER BY 를 만든다)")
  void pctAliasesNeverCollideWithSourceKeys() {
    for (String table : McpFields.COLUMNS.keySet()) {
      for (String item : McpFields.columns(table).split(", ")) {
        String physical = McpFields.physical(item);
        String alias = McpFields.alias(item);
        if (!physical.equals(alias)) assertThat(alias).as("%s.%s", table, physical).endsWith("_pct").startsWith(physical);
      }
    }
    var sources = new java.util.ArrayList<>(McpReadService.STOCK.values());
    sources.addAll(McpReadService.MARKET.values());
    for (McpReadService.Source s : sources) {
      var aliases = McpFields.aliases(s.table());
      assertThat(aliases.get(s.date())).as("%s date", s.table()).isEqualTo(s.date());
      assertThat(aliases.get(s.code())).as("%s code", s.table()).isEqualTo(s.code());
    }
  }
}
