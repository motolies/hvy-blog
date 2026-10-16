-- 가중치 정책·테마 basis·flowProvisional 주석 갱신 (2026-10-16, MCP 2차 점검). 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_12_weight_policy_theme_basis_comments.sql
-- 가중치: n_eff 게이트(24) = prior(24) 라 축소 계수가 늘 0.5 여서 ĪC ≤ 0 이면 t 와 무관하게 하한 0.5 에 붙었다(MOM_60D t=−2.16 도 0.5) →
--   multiplier-min 0, IC 검증 시그널끼리만 재정규화(VALUE_RANK 0.05 고정), RS_INDEX 비활성(KOSPI 단일 유니버스에서 MOM_20D 와 순위 동일).
--   기존 세트(1·2·3)는 그대로 두고 배포 뒤 IC_BACKFILL 이 새 정책 세트를 만든다.
-- 테마: strength 를 동일가중 벤치(rsEq20/rsEq60) 기준으로 바꾸고 themes[].basis='EQ' 를 기록한다(advice-v11·h20-v4·longterm-v2).
-- flowProvisional: 하드코딩 true → 다음 DAILY 수집 여부로 계산.
COMMENT ON COLUMN tb_advisor_weight_set.as_of IS '산출 기준일 = IC 창의 마지막 기준일 d (d+h 영업일 수익률이 실현된 마지막 날). 생성 시각(created_at)보다 h 영업일 이상 이르다';
COMMENT ON COLUMN tb_advisor_signal_weight.base_weight IS '사전 가중치 (설계값, SignalCode.baseWeight 와 같다. 점수는 Σw 정규화라 합 1 불필요 — 2026-10-16 RS_INDEX 제외 뒤 활성 합 1.02)';
COMMENT ON COLUMN tb_advisor_signal_weight.multiplier IS 'IC 축소 추정 배수 clip[ic.multiplier-min, 2.0]. 2026-10-16 이후 세트는 하한 0(음의 IC 는 가중치 0 까지, 부호 반전 없음), 이전 세트는 0.5. 비학습(VALUE_RANK)·IC 없음·비활성 행은 1.0';
COMMENT ON COLUMN tb_advisor_signal_weight.weight IS '적용 가중치. 2026-10-16 이후 세트: IC 로 검증된 시그널은 base × multiplier 를 그들의 Σbase 로 재정규화, 비학습(VALUE_RANK)·IC 없음은 base 그대로, 표현식 없는 행(RS_INDEX)은 0·비활성. 이전 세트는 활성 전체 Σbase 재정규화';
COMMENT ON COLUMN tb_advisor_signal_weight.flagged IS 'IC 음수(ĪC < 0 또는 t < −2) 등 검토 필요 표시. 부호는 뒤집지 않고 배수 하한까지만 내린다';
COMMENT ON COLUMN tb_advisor_advice.data_as_of_json IS '입력 관측 기준일 {domestic,flow,flowProvisional,sector,global,globalAgeTradingDays,sectorIndex}. flowProvisional 은 2026-10-16 부터 계산값(flow 날짜보다 뒤 DAILY 가 시장 수급을 실패 없이 다시 받았으면 false), 그 전 행은 항상 true';
COMMENT ON COLUMN tb_advisor_advice.regime_json IS '합성 국면 스냅샷 (M6, 2026-09-25): {indexCode, tradeDate, trend, trendScore, vol LOW|NORMAL|HIGH|UNKNOWN, volPct(σ20 의 기준일 이전 최대 5년 분포 백분위), sigma20, volHistoryDays, policy{version,longMax,convictionCap}, themes[{code,name,members,rs5,rs20,rs60,rsEq5,rsEq20,rsEq60,breadth,strength,basis,leaders}]} — basis EQ(2026-10-16~, advice-v11)면 strength 는 rsEq20/rsEq60(동일가중 벤치) 기준, basis·name·rsEq 가 없는 행은 KOSPI 대비 rs20/rs60 기준. policy.version 이 regime-policy-v1 인 행(2026-09-27 이전)은 policy.avoidMax 도 있다 — v2 는 매수 전용이라 없다. DAILY·ADHOC·H20 LIVE 와 LLM 섀도에 저장(H20 의 policy 는 advisor.h20 픽 상한으로 재계산), H60·H180 은 맥락 스냅샷(규칙 선택이라 policy NULL, M8), MORNING·QUANT 섀도·M6 이전 행은 NULL';
