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
    // 2026-10-16 단위 접미: 같은 이름이 소스마다 단위가 달랐던 컬럼
    assertThat(McpFields.aliases("tb_stock_master")).containsEntry("listed_shares", "listed_shares_thou").containsEntry("capital", "capital_won");
    assertThat(McpFields.aliases("tb_stock_valuation_daily")).containsEntry("listed_shares", "listed_shares_shr").containsEntry("market_cap", "market_cap_won");
    assertThat(McpFields.aliases("tb_stock_market_investor_daily")).containsEntry("foreign_net_amt", "foreign_net_amt_won").containsEntry("foreign_net_qty", "foreign_net_qty_thou");
    assertThat(McpFields.aliases("tb_stock_investor_daily")).containsEntry("foreign_net_qty", "foreign_net_qty_shr");
    assertThat(McpFields.aliases("tb_stock_financial")).containsEntry("revenue", "revenue_eok").containsEntry("roe", "roe_pct")
        .containsEntry("borrowing_dependency", "borrowing_dependency_pct");
    assertThat(McpFields.aliases("tb_stock_daily_metric")).as("METRIC 은 screenStocks 컬럼명과 같아야 해 접미 없음").containsEntry("tv_avg_60d", "tv_avg_60d")
        .containsEntry("foreign_net_5d", "foreign_net_5d");
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
  @DisplayName("별칭은 '물리 컬럼명 + 단위 접미(_won·_eok·_thou·_shr·_pct)' 만이고, 별칭 대상이 어떤 소스의 필터·날짜·커서 키 컬럼도 아니다(page() 가 물리명으로 WHERE/ORDER BY 를 만든다)")
  void unitAliasesUseFixedVocabularyAndNeverHitKeys() {
    // 필터·날짜·커서 키로 쓰이는 컬럼(STOCK·MARKET 소스 밖의 재무·섹터·판단·가중치 조회 포함) — 별칭을 붙이면 page() 의 WHERE 가 깨진다
    var keyColumns = java.util.Set.of("ticker", "market_type", "index_code", "sector_code", "symbol", "series_code", "theme_code", "trade_date", "obs_date",
        "valid_from", "valid_to", "available_from", "first_seen_at", "effective_date", "base_date", "as_of", "horizon_days", "advice_kind", "variant",
        "fiscal_period", "period_type", "revision_seq", "id", "advice_id", "note_id", "lesson_id", "weight_set_id", "created_at", "published_at");
    for (String table : McpFields.COLUMNS.keySet()) {
      for (String item : McpFields.columns(table).split(", ")) {
        String physical = McpFields.physical(item);
        String alias = McpFields.alias(item);
        if (!physical.equals(alias)) {
          assertThat(McpFields.UNIT_SUFFIXES).as("%s.%s → %s", table, physical, alias).contains(alias.substring(physical.length()));
          assertThat(alias).as("%s.%s", table, physical).startsWith(physical);
          assertThat(keyColumns).as("%s.%s 는 키 컬럼이라 별칭 금지", table, physical).doesNotContain(physical);
        }
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
