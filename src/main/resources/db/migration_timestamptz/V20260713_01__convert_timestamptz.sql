-- =============================================================================
-- timestamp without time zone → timestamptz 전환 마이그레이션
-- =============================================================================
-- 목적:
--   기존 모든 timestamp 컬럼(TIMESTAMP WITHOUT TIME ZONE)을
--   timestamptz(TIMESTAMP WITH TIME ZONE)로 전환한다.
--   앱은 그동안 시간 값을 "UTC 벽시계"로 저장해 왔으므로, 각 값을 UTC로
--   해석(AT TIME ZONE 'UTC')하여 동일한 순간(instant)을 가리키는 timestamptz로 변환한다.
--
--   예외: tb_jira_worklog.started 컬럼만 Jira API의 +09:00 오프셋에서 오프셋만
--   소거된 "KST 벽시계"가 저장돼 있으므로 AT TIME ZONE 'Asia/Seoul'로 해석한다.
--
-- 실행 전제:
--   1. 앱이 모든 timestamp 값을 UTC 벽시계로 저장해 왔다(2026-04-10 MariaDB→PostgreSQL
--      이관분 포함). 이관 시에도 CONVERT_TZ 없이 문자열 그대로 옮겼고 당시 컨테이너도
--      UTC였으므로 기본 가정은 전부 UTC다.
--   2. 실행 전 반드시 아래 "섹션 0 — 사전 감사 쿼리"를 먼저 수행하여 이관분(2026-04-10
--      이전 행)이 실제로 UTC로 저장돼 있는지, tb_jira_worklog.started가 KST 벽시계인지
--      검증한다. 감사 결과가 가정과 어긋나면 해당 컬럼의 USING 절을 조정해야 한다.
--
-- 실행 방법(수동 실행 — Flyway 미사용, spring.sql.init.mode=never):
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f V20260713_01__convert_timestamptz.sql
--   (먼저 백업 권장:  pg_dump ... > backup_before_timestamptz.dump )
--
-- 경고 — 구버전 앱 재시작 금지:
--   이 마이그레이션 실행 후에는 반드시 timestamptz를 인지하는 신버전 앱만 기동해야 한다.
--   구버전 앱(엔티티가 아직 timestamp without time zone/LocalDateTime 매핑 기준)을 재시작하면
--   Hibernate ddl-auto=validate 단계에서 컬럼 타입 불일치로 부팅에 실패할 수 있다.
--   무중단 배포가 아니라면 "앱 중지 → 마이그레이션 → 신버전 배포" 순서를 지킨다.
--
-- 멱등성:
--   각 ALTER는 information_schema.columns의 data_type이 'timestamp without time zone'인
--   경우에만 실행되도록 DO 블록으로 감쌌다. 전환 후 재실행하면 data_type이
--   'timestamp with time zone'이 되어 자동으로 건너뛴다.
--
-- 정밀도:
--   기존 TIMESTAMP(6) → timestamptz(6),  shedlock의 TIMESTAMP(3) → timestamptz(3).
--   DATE 타입(tb_jira_issue.start_date/end_date)은 시각 정보가 없으므로 대상에서 제외한다.
--
-- DEFAULT:
--   tb_master_code.created_at(DEFAULT NOW())와 tb_series.created_at/updated_at
--   (운영 DB 기준 DEFAULT CURRENT_TIMESTAMP)의 기본값 함수는 이미 timestamptz를 반환하므로,
--   ALTER COLUMN TYPE 시 기본값이 자동 재캐스팅되어 그대로 유지된다. 별도 조치 불필요.
-- =============================================================================


-- =============================================================================
-- 섹션 0 — 사전 감사 쿼리 (실행 전 수동 확인용 / 이 블록은 마이그레이션 실행 대상이 아님)
-- =============================================================================
-- 아래 쿼리들은 컬럼이 아직 timestamp without time zone인 상태에서 실행한다.
-- EXTRACT(HOUR FROM col)은 "저장된 벽시계 시각"을 그대로 돌려주므로, 저장 타임존이
-- 구간별로 달랐다면(예: 이관 이전은 KST, 이후는 UTC) 시간대 분포가 약 9시간 시프트된다.
--
-- ── 감사 A. 이관분(UTC 여부) 검증: tb_system_log.created_at 시간 분포 비교 ──
-- 트래픽 피크 시간대가 이관 경계(2026-04-10)를 기준으로 약 9시간 이동하면
-- 이관 이전 데이터가 KST로 저장돼 있다는 의심 근거가 된다(가정 위배 → USING 조정 필요).
/*
SELECT CASE WHEN created_at < TIMESTAMP '2026-04-10 00:00:00'
            THEN 'pre_migration (~2026-04-09)'
            ELSE 'post_migration (2026-04-10~)' END          AS period,
       EXTRACT(HOUR FROM created_at)::int                    AS hour_of_day,
       COUNT(*)                                              AS cnt
FROM   tb_system_log
GROUP  BY period, hour_of_day
ORDER  BY period, hour_of_day;
*/

-- ── 감사 B. 동일 검증: tb_post.created_at 시간 분포 비교 ──
-- 사람이 글을 쓰는 시간대(주로 주간/저녁)가 이관 전후로 9시간 시프트되는지 확인.
/*
SELECT CASE WHEN created_at < TIMESTAMP '2026-04-10 00:00:00'
            THEN 'pre_migration (~2026-04-09)'
            ELSE 'post_migration (2026-04-10~)' END          AS period,
       EXTRACT(HOUR FROM created_at)::int                    AS hour_of_day,
       COUNT(*)                                              AS cnt
FROM   tb_post
GROUP  BY period, hour_of_day
ORDER  BY period, hour_of_day;
*/

-- ── 감사 C. tb_jira_worklog.started 가 KST 벽시계인지 검증 ──
-- 업무 시간(09~18 KST 벽시계)이라면 hour 분포가 9~18에 몰린다.
-- 만약 UTC로 저장돼 있다면 같은 업무 시간이 0~9(=09-18 KST − 9h)에 몰릴 것이다.
-- 9~18 집중이 확인되면 이 컬럼만 AT TIME ZONE 'Asia/Seoul' 변환이 옳다(아래 섹션 1 참조).
/*
SELECT EXTRACT(HOUR FROM started)::int   AS hour_of_day,
       COUNT(*)                          AS cnt
FROM   tb_jira_worklog
GROUP  BY hour_of_day
ORDER  BY hour_of_day;
*/

-- ── 감사 D. (보조) started vs created_at 오프셋 확인 ──
-- started(KST 가정)와 created_at(UTC 가정)의 시(hour) 차이가 대체로 +9에 수렴하면
-- 두 컬럼의 저장 타임존이 서로 다르다는 방증이 된다.
/*
SELECT width_bucket(EXTRACT(HOUR FROM started)::int - EXTRACT(HOUR FROM created_at)::int,
                     -12, 12, 24)         AS hour_diff_bucket,
       COUNT(*)                           AS cnt
FROM   tb_jira_worklog
GROUP  BY hour_diff_bucket
ORDER  BY hour_diff_bucket;
*/


-- =============================================================================
-- 섹션 1 — 본 마이그레이션
-- =============================================================================
-- 규칙:
--   * UTC 벽시계 컬럼:  ... USING col AT TIME ZONE 'UTC'
--   * KST 벽시계 컬럼(tb_jira_worklog.started 단 하나): ... USING started AT TIME ZONE 'Asia/Seoul'
--   * 각 ALTER는 data_type = 'timestamp without time zone'일 때만 실행(멱등).

-- ---------------------------------------------------------------------------
-- tb_post
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_post'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_post
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_post'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_post
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_post_draft
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_post_draft'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_post_draft
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_file
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_file'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_file
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_search_engine
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_search_engine'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_search_engine
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_search_engine'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_search_engine
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_tag
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_tag'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_tag
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_series  (운영 DB에는 created_at/updated_at에 DEFAULT CURRENT_TIMESTAMP 존재 — 자동 재캐스팅)
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_series'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_series
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_series'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_series
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_master_code  (created_at에 DEFAULT NOW() 존재 — 자동 재캐스팅되어 유지됨)
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_master_code'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_master_code
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_master_code'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_master_code
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_jira_issue  (start_date/end_date는 DATE 타입이므로 제외)
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_jira_issue'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_jira_issue
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_jira_issue'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_jira_issue
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_jira_worklog
--   ★ 예외: started 는 KST 벽시계 → AT TIME ZONE 'Asia/Seoul'
--     created_at/updated_at 은 앱이 기록한 UTC 벽시계 → AT TIME ZONE 'UTC'
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_jira_worklog'
                 AND column_name = 'started' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_jira_worklog
            ALTER COLUMN started TYPE timestamptz(6) USING started AT TIME ZONE 'Asia/Seoul';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_jira_worklog'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_jira_worklog
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_jira_worklog'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_jira_worklog
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_memo_category
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_memo_category'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_memo_category
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_memo_category'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_memo_category
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_memo
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_memo'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_memo
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_memo'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_memo
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_system_log
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_system_log'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_system_log
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_api_log
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_api_log'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_api_log
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- shedlock  (정밀도 3)
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'shedlock'
                 AND column_name = 'lock_until' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE shedlock
            ALTER COLUMN lock_until TYPE timestamptz(3) USING lock_until AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'shedlock'
                 AND column_name = 'locked_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE shedlock
            ALTER COLUMN locked_at TYPE timestamptz(3) USING locked_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_hot_deal_site  (hotdeal-schema.sql)
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_hot_deal_site'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_hot_deal_site
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_hot_deal_site'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_hot_deal_site
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- tb_hot_deal_item  (notified_at 은 NULL 허용 — AT TIME ZONE은 NULL을 NULL로 보존)
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_hot_deal_item'
                 AND column_name = 'notified_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_hot_deal_item
            ALTER COLUMN notified_at TYPE timestamptz(6) USING notified_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_hot_deal_item'
                 AND column_name = 'scraped_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_hot_deal_item
            ALTER COLUMN scraped_at TYPE timestamptz(6) USING scraped_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_hot_deal_item'
                 AND column_name = 'created_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_hot_deal_item
            ALTER COLUMN created_at TYPE timestamptz(6) USING created_at AT TIME ZONE 'UTC';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'blog' AND table_name = 'tb_hot_deal_item'
                 AND column_name = 'updated_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE tb_hot_deal_item
            ALTER COLUMN updated_at TYPE timestamptz(6) USING updated_at AT TIME ZONE 'UTC';
    END IF;
END $$;


-- =============================================================================
-- 섹션 1-말미 — 누락 검사
-- =============================================================================
-- 전환 후 public 스키마에 남아있는 timestamp without time zone 컬럼 목록을 출력한다.
-- 결과가 0행이면 모든 대상이 정상 전환된 것이다. (DATE/그 밖의 타입은 여기 나타나지 않는다.)
SELECT table_name,
       column_name,
       data_type,
       datetime_precision
FROM   information_schema.columns
WHERE  table_schema = 'blog'
  AND  data_type = 'timestamp without time zone'
ORDER  BY table_name, column_name;


-- =============================================================================
-- 섹션 2 — 롤백 참고 (필요 시 수동 실행)
-- =============================================================================
-- AT TIME ZONE은 대칭이다:
--   * timestamp without tz  AT TIME ZONE 'UTC'  → timestamptz            (UTC로 해석)
--   * timestamptz           AT TIME ZONE 'UTC'  → timestamp without tz   (UTC 벽시계로 렌더)
-- 따라서 아래 역변환은 전환 전의 "UTC 벽시계" 값을 그대로 복원한다.
-- (단, 원본이 UTC 벽시계였다는 전제가 성립할 때만 무손실이다.)
--
-- 예시 — 일반 컬럼(UTC 벽시계로 되돌림):
/*
ALTER TABLE tb_post
    ALTER COLUMN created_at TYPE timestamp(6) USING created_at AT TIME ZONE 'UTC';
ALTER TABLE tb_post
    ALTER COLUMN updated_at TYPE timestamp(6) USING updated_at AT TIME ZONE 'UTC';
*/
--
-- 예시 — tb_jira_worklog.started(KST 벽시계로 되돌림):
/*
ALTER TABLE tb_jira_worklog
    ALTER COLUMN started TYPE timestamp(6) USING started AT TIME ZONE 'Asia/Seoul';
*/
--
-- 예시 — shedlock(정밀도 3):
/*
ALTER TABLE shedlock
    ALTER COLUMN lock_until TYPE timestamp(3) USING lock_until AT TIME ZONE 'UTC';
ALTER TABLE shedlock
    ALTER COLUMN locked_at  TYPE timestamp(3) USING locked_at  AT TIME ZONE 'UTC';
*/
-- =============================================================================
-- 마이그레이션 끝
-- =============================================================================
