-- =============================================
-- AI 시장 판단(advisor) 시드 데이터 (psql 수기 적용, 재실행 안전)
-- =============================================
-- 초기 가중치 세트(SEED). 설계 사전값이며 IC 사전 추정(IC_BACKFILL) 세트가 활성화되면 대체된다.
-- 코드는 SignalCode enum 상수명과 같아야 한다. GLOBAL_LINK 는 국면 특징으로만 쓰고 종목 점수에는 넣지 않는다(base 0, enabled false).
-- 밸류(VALUE_RANK)는 당일 스냅샷만 있어 과거 IC 를 못 구하므로 multiplier 1.0 고정 대상이다.
-- =============================================
INSERT INTO tb_advisor_weight_set (as_of, window_days, n_eff, source, is_active, reason)
SELECT CURRENT_DATE, 0, 0, 'SEED', TRUE, '설계 사전 가중치 (2026-09-13). IC 사전 추정 전 초기값'
WHERE NOT EXISTS (SELECT 1 FROM tb_advisor_weight_set WHERE source = 'SEED');

INSERT INTO tb_advisor_signal_weight (weight_set_id, signal_code, base_weight, multiplier, weight, enabled, note)
SELECT s.weight_set_id, v.code, v.base, 1.0, v.base, v.base > 0, v.note
FROM tb_advisor_weight_set s,
     (VALUES ('MOM_20D',         0.12, '20일 모멘텀'),
             ('MOM_60D',         0.10, '60일 모멘텀'),
             ('TREND_MA',        0.12, 'MA 정배열 강도'),
             ('NEAR_HIGH_52W',   0.10, '52주 고점 근접'),
             ('TV_SURGE',        0.10, '거래대금 5/60 급증'),
             ('FOREIGN_FLOW',    0.12, '외국인 5일 순매수/60일 평균 거래대금×5'),
             ('INST_FLOW',       0.08, '기관 5일 순매수/60일 평균 거래대금×5'),
             ('SECTOR_STRENGTH', 0.10, '섹터 5일 동일가중 등락'),
             ('RS_INDEX',        0.08, '지수 대비 20일 상대강도'),
             ('VALUE_RANK',      0.05, 'PBR 낮을수록 (당일 스냅샷, IC 갱신 대상 아님)'),
             ('VOL_20D',         0.03, '20일 변동성 낮을수록'),
             ('SECTOR_MOM_20D',  0.05, '섹터(업종 지수) 20일 시장 대비 초과 (advice-v6, 2026-09-21)'),
             ('SECTOR_MOM_60D',  0.05, '섹터(업종 지수) 60일 시장 대비 초과 (advice-v6, 2026-09-21)'),
             ('GLOBAL_LINK',     0.00, '해외 연동 — 국면 특징 전용, 종목 점수 제외')) AS v(code, base, note)
WHERE s.source = 'SEED'
ON CONFLICT (weight_set_id, signal_code) DO NOTHING;
