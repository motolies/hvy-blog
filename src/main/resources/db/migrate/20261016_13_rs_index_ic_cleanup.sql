-- RS_INDEX rank-IC 행 삭제 (2026-10-16, MCP 2차 점검). 데이터 DELETE — **새 코드 배포 뒤** 적용한다(옛 코드의 증분 IC 가 다시 쓰지 않게).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_13_rs_index_ic_cleanup.sql
-- 유니버스가 KOSPI 하나(advisor.markets)라 벤치 지수 수익률이 그날 모든 종목에 같은 상수이고, RS_INDEX(ret_20d − index_ret_20d)의 순위가 MOM_20D 와 같다 —
-- 같은 IC 의 사본이다(세트 2·3 에서 ĪC·se·t 가 소수점 끝자리까지 일치). 코드가 점수·IC 에서 뺐으므로 남겨 두면 signalIc 창에 배포일에서 멈춘 행으로 보인다.
-- 사전 확인 — 0 이 기대값. 0 이 아니면 advice-v5 이전(양시장 유니버스) 시절 행이며 어차피 쓰이지 않는 사본이라 지워도 된다:
--   SELECT COUNT(*) FROM tb_advisor_signal_ic_daily r JOIN tb_advisor_signal_ic_daily m
--     ON m.trade_date = r.trade_date AND m.horizon_days = r.horizon_days AND m.signal_code = 'MOM_20D'
--    WHERE r.signal_code = 'RS_INDEX' AND ABS(r.rank_ic - m.rank_ic) > 1e-9;
DELETE FROM tb_advisor_signal_ic_daily WHERE signal_code = 'RS_INDEX';
