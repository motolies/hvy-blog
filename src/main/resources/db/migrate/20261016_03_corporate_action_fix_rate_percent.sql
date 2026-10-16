-- 예탁원 확정배정율(fix_rate) 단위 보정 (2026-10-16): % → 주당 신주 수. 데이터 UPDATE·DELETE 있음.
-- 디바이스 187870 무상증자 fix_rate=100(1주당 1주)을 주당 100주로 해석해 price_factor 0.0099(올바른 값 0.5)가 저장됐고,
-- 효력일 이전 전 구간에 곱해져 adj_close 04-27 379원·ret_120d 수십 배 이상치를 만들었다(plan/mcp.md P0-4).
-- raw_json 의 fix_rate 로 다시 계산하므로 재실행 안전. 수집·advisor 스케줄이 돌지 않는 낮 시간에 적용하고 **같은 순서로** 이어간다:
--   1) 새 매퍼(CorporateActionMapper.percentToRatio) 코드 배포
--   2) psql -1 -f src/main/resources/db/migrate/20261016_03_corporate_action_fix_rate_percent.sql
--   3) POST /api/stock/admin/collect/ADJUST_FACTOR  → run 메타 eventsImplausible·verification 확인
--   4) POST /api/stock/admin/collect/DERIVED_REFRESH {"force":true}  (또는 일요일 WEEKLY) — 누적 계수는 효력일 이전 전 구간에 곱해져 증분 30일로는 못 고친다
-- 순서가 뒤집히면(새 코드로 ADJUST_FACTOR 먼저) ratio 100 이 상한(20)에 걸려 이벤트가 안 만들어지고 옛 0.0099 행이 남는다.
UPDATE tb_stock_corporate_action
SET    ratio_after = REPLACE(raw_json->>'fix_rate', ',', '')::numeric / 100
WHERE  source = 'KSD'
  AND  action_type IN ('BONUS_ISSUE', 'RIGHTS_ISSUE')
  AND  REPLACE(raw_json->>'fix_rate', ',', '') ~ '^-?[0-9]*\.?[0-9]+$';
-- 잘못된 비율로 만든 계수 이벤트는 지운다 — upsert 는 toEvent 가 empty 로 바뀐 행을 지우지 못한다. ADJUST_FACTOR 가 다시 만든다(verified=false).
DELETE FROM tb_stock_adjust_event WHERE action_type IN ('BONUS_ISSUE', 'RIGHTS_ISSUE');
COMMENT ON COLUMN tb_stock_corporate_action.ratio_after IS '행사 후 비율(신주). BONUS_ISSUE·RIGHTS_ISSUE 는 주당 신주 배정 주수(예탁원 fix_rate % ÷ 100, 2026-10-16). CAPITAL_REDUCTION 은 reduce_cap_rate 원값(단위 미확정)';
-- 확인: SELECT trade_date, raw_close, adj_close FROM vw_stock_daily_price_adj WHERE ticker='187870' AND trade_date BETWEEN '2026-04-24' AND '2026-04-30';
--       04-27 adj_close ≈ 19,150 (38,300 × 0.5), 04-28 = 20,600 (DERIVED_REFRESH 뒤)
