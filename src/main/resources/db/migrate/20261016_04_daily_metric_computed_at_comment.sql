-- tb_stock_daily_metric.computed_at 주석 정정 (2026-10-16). 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_04_daily_metric_computed_at_comment.sql
-- 지표 upsert 는 값이 같으면 UPDATE 를 건너뛰어 computed_at 이 안 바뀐다. 가격 collected_at 과 비교해 "재계산 안 됐다" 고 읽으면 오탐이다(plan/mcp.md P0-5).
-- 재계산 범위의 감사 기록은 run 메타(metricFrom, 2026-10-16 부터 correctedFrom·correctedRows)다.
COMMENT ON COLUMN tb_stock_daily_metric.computed_at IS '값이 바뀐 마지막 계산 시각 — upsert 가 IS DISTINCT FROM 이라 값이 하나도 안 바뀌면 갱신되지 않는다. 재계산 범위는 tb_stock_collect_run.metadata_json.metricFrom/correctedFrom';
