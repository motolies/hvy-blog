package kr.hvy.blog.modules.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit public projections: adding a database column never publishes it automatically.
 * <p>
 * 항목은 {@code col} 또는 {@code col AS alias}. 별칭은 응답 JSON 키만 바꾸고(to_jsonb 가 서브쿼리 출력 컬럼명을 쓴다) 필터·날짜·커서 키는 물리 컬럼명을 쓴다 —
 * 2026-10-16(P2): % 단위 컬럼(change_rate 등)을 {@code *_pct} 로 내보내 같은 응답의 소수 비율(ret_*)과 구분한다. DB 컬럼명은 뷰·MV·인덱스·writer 가 물려 있어 바꾸지 않는다.
 * 2026-10-16(2차 점검): 같은 이름이 소스마다 단위가 달라(상장주식수 마스터 천주·VALUATION 주, 시장 수급 수량 천주·종목 수급 주, 재무 억원) 단위 접미를
 * 고정 어휘({@link #UNIT_SUFFIXES})로 붙였다. 필터·날짜·커서 키와 METRIC(screenStocks 컬럼명과 같아야 함)에는 붙이지 않는다.
 */
final class McpFields {
  private McpFields() {}
  /** 응답 키 단위 접미 어휘: _won 원, _eok 억원, _thou 천주, _shr 주, _pct %(1=1%). 별칭은 "물리 컬럼명 + 이 중 하나" 만 허용한다(McpFieldsTest) */
  static final List<String> UNIT_SUFFIXES = List.of("_won", "_eok", "_thou", "_shr", "_pct");
  private static final String AS = " AS ";
  static final Map<String, String> COLUMNS = Map.ofEntries(
      Map.entry("tb_advisor_candidate", "advice_id, ticker, quant_rank, quant_score, stock_name, market_type, bench_index_code, sector_code, sector_name, signal_json, feature_json, applied_lesson_ids, ref_raw_close, ref_adj_close"),
      Map.entry("tb_advisor_pick", "advice_id, ticker, pick_rank, direction, conviction, thesis, risk_note, cited_json, cited_news, action, action_reason"),
      Map.entry("tb_advisor_candidate_score", "advice_id, ticker, horizon_days, stage, status, entry_date, entry_price, exit_date, exit_price, ret, dividend_ret, bench_ret, excess_ret, cost_adj_excess, scored_at"),
      Map.entry("tb_advisor_call_score", "advice_id, subject_type, subject_code, horizon_days, stage, status, predicted, p_up, base_value, exit_value, actual_ret, bench_ret, band, actual_dir, hit, brier, event_date, scored_at"),
      Map.entry("tb_advisor_signal_ic_daily", "signal_code, trade_date, horizon_days, rank_ic, n, computed_at"),
      Map.entry("tb_advisor_intraday_check", "check_id, advice_id, run_id, checked_at, index_json, pick_json, agreement_ratio, verdict, comment, created_at"),
      Map.entry("tb_advisor_morning_check", "check_id, advice_id, run_id, base_date, us_date, gap_kospi, gap_kosdaq, verdict, detail_json, published_at, created_at"),
      Map.entry("tb_stock_master", "ticker, stock_name, market_type, security_group, standard_code, listing_date, listed_shares AS listed_shares_thou, capital AS capital_won, par_value AS par_value_won, settle_month, sector_large_code, sector_mid_code, sector_small_code, kospi200_sector, is_kospi200, is_krx300, is_suspended, is_administrative, is_liquidating, is_active, delisting_date, created_at, updated_at"),
      Map.entry("tb_stock_master_history", "ticker, valid_from, valid_to, stock_name, market_type, security_group, sector_mid_code, is_kospi200, is_krx300, is_suspended, is_administrative, is_active, listed_shares AS listed_shares_thou, created_at"),
      Map.entry("tb_stock_daily_metric", "ticker, trade_date, adj_close, ret_1d, ret_5d, ret_20d, ret_60d, ret_120d, ma_5, ma_20, ma_60, ma_120, dist_ma20, dist_ma60, high_52w, dist_high_52w, tv_avg_5d, tv_avg_60d, tv_ratio_5_60, vol_avg_20d, foreign_net_5d, institution_net_5d, computed_at"),
      Map.entry("tb_stock_daily_price", "ticker, trade_date, open_price, high_price, low_price, close_price, volume, trading_value, prev_diff, prev_diff_sign, change_rate AS change_rate_pct, flng_cls_code, prtt_rate AS prtt_rate_pct, mod_yn, revl_issu_reas, collected_at"),
      Map.entry("tb_stock_valuation_daily", "ticker, trade_date, market_cap AS market_cap_won, listed_shares AS listed_shares_shr, per, pbr, eps AS eps_won, bps AS bps_won, week52_high, week52_low, foreign_hold_rate AS foreign_hold_rate_pct, collected_at"),
      Map.entry("tb_stock_investor_daily", "ticker, trade_date, foreign_net_amt AS foreign_net_amt_won, institution_net_amt AS institution_net_amt_won, individual_net_amt AS individual_net_amt_won, pension_net_amt AS pension_net_amt_won, other_net_amt AS other_net_amt_won, foreign_net_qty AS foreign_net_qty_shr, institution_net_qty AS institution_net_qty_shr, individual_net_qty AS individual_net_qty_shr, collected_at"),
      Map.entry("tb_stock_market_stat_daily", "ticker, trade_date, short_sale_qty, short_sale_amt, short_sale_ratio, credit_loan_qty, credit_loan_amt, credit_loan_ratio, stock_loan_qty, program_net_qty, program_net_amt, collected_at"),
      Map.entry("tb_stock_etf_nav_daily", "ticker, trade_date, close_price, prev_diff, prev_diff_sign, change_rate AS change_rate_pct, volume, nav, nav_prev_diff, nav_prev_diff_sign, nav_change_rate AS nav_change_rate_pct, nav_diff, disparity_rate AS disparity_rate_pct, collected_at"),
      Map.entry("tb_stock_index_daily", "index_code, trade_date, open_price, high_price, low_price, close_price, volume, trading_value, change_rate AS change_rate_pct, collected_at"),
      Map.entry("tb_stock_market_investor_daily", "market_type, trade_date, foreign_net_amt AS foreign_net_amt_won, foreign_net_qty AS foreign_net_qty_thou, foreign_reg_net_amt AS foreign_reg_net_amt_won, foreign_reg_net_qty AS foreign_reg_net_qty_thou, foreign_nreg_net_amt AS foreign_nreg_net_amt_won, foreign_nreg_net_qty AS foreign_nreg_net_qty_thou, individual_net_amt AS individual_net_amt_won, individual_net_qty AS individual_net_qty_thou, institution_net_amt AS institution_net_amt_won, institution_net_qty AS institution_net_qty_thou, securities_net_amt AS securities_net_amt_won, securities_net_qty AS securities_net_qty_thou, invest_trust_net_amt AS invest_trust_net_amt_won, invest_trust_net_qty AS invest_trust_net_qty_thou, private_fund_net_amt AS private_fund_net_amt_won, private_fund_net_qty AS private_fund_net_qty_thou, bank_net_amt AS bank_net_amt_won, bank_net_qty AS bank_net_qty_thou, insurance_net_amt AS insurance_net_amt_won, insurance_net_qty AS insurance_net_qty_thou, merchant_bank_net_amt AS merchant_bank_net_amt_won, merchant_bank_net_qty AS merchant_bank_net_qty_thou, pension_net_amt AS pension_net_amt_won, pension_net_qty AS pension_net_qty_thou, other_net_amt AS other_net_amt_won, other_net_qty AS other_net_qty_thou, other_org_net_amt AS other_org_net_amt_won, other_org_net_qty AS other_org_net_qty_thou, other_corp_net_amt AS other_corp_net_amt_won, other_corp_net_qty AS other_corp_net_qty_thou, collected_at"),
      Map.entry("tb_stock_global_market_daily", "symbol, trade_date, market_div, exchange, open_price, high_price, low_price, close_price, volume, change_rate AS change_rate_pct, collected_at"),
      Map.entry("tb_stock_macro_daily", "series_code, obs_date, value, source, available_from, observed_at, collected_at"),
      Map.entry("tb_stock_event_timeline", "theme_code, obs_date, source, article_vol, total_vol, vol_ratio, avg_tone, collected_at"),
      Map.entry("tb_stock_adjust_event", "ticker, effective_date, action_type, price_factor, volume_factor, verified, created_at"),
      Map.entry("tb_stock_financial", "ticker, fiscal_period, period_type, revision_seq, disclosed_at, available_from, available_rule, first_seen_at, revenue AS revenue_eok, operating_profit AS operating_profit_eok, net_income AS net_income_eok, total_asset AS total_asset_eok, total_equity AS total_equity_eok, total_debt AS total_debt_eok, roe AS roe_pct, debt_ratio AS debt_ratio_pct, revenue_growth AS revenue_growth_pct, profit_growth AS profit_growth_pct, operating_profit_growth AS operating_profit_growth_pct, equity_growth AS equity_growth_pct, asset_growth AS asset_growth_pct, roa AS roa_pct, net_margin AS net_margin_pct, gross_margin AS gross_margin_pct, current_ratio AS current_ratio_pct, quick_ratio AS quick_ratio_pct, borrowing_dependency AS borrowing_dependency_pct, created_at"),
      Map.entry("tb_stock_corporate_action", "id, ticker, effective_date, action_type, ratio_before, ratio_after, cash_amount, source, created_at"),
      Map.entry("tb_stock_sector_map", "ticker, sector_code, valid_from, valid_to, sector_name, source, created_at"),
      Map.entry("tb_advisor_advice", "advice_id, run_id, base_date, advice_kind, variant, horizon_days, regime_code, kospi_dir, kosdaq_dir, p_up, regime_rationale, leading_sectors, summary, trend_kospi, trend_kosdaq, trend_json, outlook_json, data_as_of_json, entry_date, exit_date, news_ids, prompt_version, model, weight_set_id, active_lesson_ids, data_quality, guard_json, published_at, created_at, parent_advice_id, diff_json, regime_json"),
      // listAdvices 기본 요약 투영(2026-10-16): 헤더만. JSON 블록(trend/outlook/data_as_of/guard/diff/regime)·rationale·sectors 는 full 또는 adviceDetail
      Map.entry("tb_advisor_advice:summary", "advice_id, run_id, base_date, advice_kind, variant, horizon_days, regime_code, kospi_dir, kosdaq_dir, p_up, summary, trend_kospi, trend_kosdaq, entry_date, exit_date, prompt_version, model, weight_set_id, data_quality, published_at, created_at, parent_advice_id"),
      Map.entry("tb_advisor_weight_set", "weight_set_id, as_of, window_days, n_eff, source, is_active, reason, run_id, created_at, horizon_days"),
      Map.entry("tb_advisor_signal_weight", "weight_set_id, signal_code, base_weight, multiplier, weight, enabled, ic_mean, ic_se, t_stat, n_days, flagged, note"),
      Map.entry("tb_advisor_lesson", "lesson_id, status, scope, condition_json, observation, evidence_json, rule, lesson_text, applied_count, post_n_applied, post_excess_applied, post_n_not_applied, post_excess_not_applied, activated_at, retired_at, retired_reason, model, run_id, created_at"),
      Map.entry("tb_advisor_pick_note", "note_id, advice_id, check_id, ticker, base_date, noted_at, direction, conviction, open_price, current_price, change_rate AS change_rate_pct, gap_rate, since_open_rate, bench_rate AS bench_rate_pct, excess_rate, z_score, note_class, deviation, why, hypothesis, tags_json, status, final_excess, finalized_at, model, run_id, created_at"),
      Map.entry("tb_stock_news", "news_id, source, provider_code, serial_no, published_at, title, category_code, origin, tickers, collected_at"),
      Map.entry("vw_stock_daily_price_adj", "ticker, trade_date, adj_open, adj_high, adj_low, adj_close, adj_volume, trading_value, raw_close, raw_volume, change_rate AS change_rate_pct"),
      Map.entry("mv_stock_index_metric", "index_code, trade_date, close_value, ret_1d, ret_5d, ret_20d, ret_60d, ret_120d, ma_20, ma_60, ma_120"),
      Map.entry("mv_stock_sector_daily", "sector_code, trade_date, member_count, sum_market_cap AS sum_market_cap_won, sum_trading_value AS sum_trading_value_won, avg_change_rate AS avg_change_rate_pct, cap_weighted_change_rate AS cap_weighted_change_rate_pct, rising_ratio, near_high_ratio, foreign_net_sum AS foreign_net_sum_won, institution_net_sum AS institution_net_sum_won"),
      Map.entry("mv_stock_market_breadth_daily", "market_type, trade_date, member_count, above_ma20_ratio, above_ma60_ratio, rising_ratio"));
  /** SELECT 목록 그대로(별칭 포함) */
  static String columns(String table) {
    String columns = COLUMNS.get(table);
    if (columns == null) throw new IllegalArgumentException("Unsupported data source");
    return columns;
  }
  /** 물리 컬럼명(별칭 앞) — 카탈로그 col_description 조회·필터용 */
  static List<String> names(String table) {
    return List.of(columns(table).split(", ")).stream().map(McpFields::physical).toList();
  }
  /** 응답 키(별칭 있으면 별칭) */
  static List<String> published(String table) {
    return List.of(columns(table).split(", ")).stream().map(McpFields::alias).toList();
  }
  /** 물리 컬럼명 → 응답 키 (별칭 없는 컬럼은 자기 자신) */
  static Map<String, String> aliases(String table) {
    Map<String, String> map = new LinkedHashMap<>();
    for (String item : columns(table).split(", ")) map.put(physical(item), alias(item));
    return map;
  }
  static String physical(String item) { int i = item.indexOf(AS); return i < 0 ? item : item.substring(0, i); }
  static String alias(String item) { int i = item.indexOf(AS); return i < 0 ? item : item.substring(i + AS.length()); }
}
