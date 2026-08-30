-- ============================================================
-- V20260830_02__master_code_tsid.sql
-- tb_master_code PK 를 BIGINT IDENTITY → TSID 문자열(VARCHAR(13)) 로 전환
--
-- [왜]
--   path 가 '/부모path/자기id' 인데 IDENTITY 는 INSERT 후에야 id 가 정해진다.
--   그래서 시드 SQL 은 path 를 뒤따르는 UPDATE 로 채워야 했고, 그 UPDATE 를 빠뜨리면
--   path 가 NULL 로 남았다. 그러면:
--     · loadFullTree()  → parent_id/depth 로만 조립 → 멀쩡히 보임 (/admin/master-code)
--     · loadSubTree()   → findSubtree(path LIKE) → 0건    (/admin/favorites, 홈 플랫폼 섹션)
--   JPQL CONCAT 이 PG 방언에서 || 로 렌더링되어 `path LIKE (NULL || '%')` → UNKNOWN → 0건이 된다.
--   게다가 MasterCodeQuery.getSubTree 가 빈 리스트도 캐시 히트로 판정해 그 []가 하루 고착됐다.
--
--   TSID 는 애플리케이션이 만들므로 id 를 INSERT 전에 알 수 있다 → path 를 같은 INSERT 에 넣고
--   path 에 NOT NULL 을 걸어 DB 가 직접 사고를 거부한다. 고정 13자라 prefix 충돌('/6' 이 '/60'
--   을 삼키는 문제)도 사라진다. tb_category 가 이미 쓰는 방식과 같다.
--
-- ⚠️ 이 프로젝트에는 Flyway 가 없다(ddl-auto: validate, sql.init.mode: never).
--    파일명은 관례일 뿐 자동 실행되지 않는다 — psql 로 수기 실행할 것.
--
-- ⚠️ 외부 FK 는 없다. REFERENCES tb_master_code 는 자기참조(fk_master_code_parent) 하나뿐이고,
--    MasterCode 를 참조하는 JPA 관계도, attributes JSONB 안의 노드 id 참조도 없다.
--    따라서 이 테이블만 교체하면 되고 다른 테이블은 건드리지 않는다.
--
-- ⚠️ 배포 절차 (롤링 불가)
--    1) 애플리케이션 전 인스턴스 중지  ← DTO 의 id 타입이 Long → String 으로 바뀌어
--                                      구/신 버전이 같은 Redis L2 를 공유하면 역직렬화가 깨진다
--    2) 이 SQL 실행
--    3) 캐시 flush:  DELETE /api/codes/admin/cache   (또는 Redis 에서 cache:masterCode* 삭제)
--    4) hvy-common 0.3.1 + hvy-blog + blog-nextjs 동시 배포
--
-- ⚠️ 관리 화면 URL(/nodes/{id})은 id 가 새로 발급되어 무효화된다.
--    공개 URL 은 code 기반이라 영향 없다.
-- ============================================================

BEGIN;

-- ------------------------------------------------------------
-- [1] 기존 테이블 보존 — 되돌릴 수 있게 남겨둔다.
--     검증이 끝난 뒤 수동으로 DROP 할 것 (이 스크립트는 지우지 않는다).
-- ------------------------------------------------------------
ALTER TABLE tb_master_code RENAME TO tb_master_code_bak_20260830;

-- 제약·인덱스 이름은 테이블에 종속이 아니라 스키마 전역이라, 새 테이블이 같은 이름을 쓰려면
-- 백업 쪽 이름을 먼저 비켜준다.
ALTER TABLE tb_master_code_bak_20260830 RENAME CONSTRAINT uk_master_code_parent_code TO uk_mc_bak_20260830_parent_code;
ALTER TABLE tb_master_code_bak_20260830 RENAME CONSTRAINT fk_master_code_parent      TO fk_mc_bak_20260830_parent;
ALTER INDEX idx_mc_parent_id  RENAME TO idx_mc_bak_20260830_parent_id;
ALTER INDEX idx_mc_path       RENAME TO idx_mc_bak_20260830_path;
ALTER INDEX idx_mc_code       RENAME TO idx_mc_bak_20260830_code;
ALTER INDEX idx_mc_attributes RENAME TO idx_mc_bak_20260830_attributes;

-- ------------------------------------------------------------
-- [2] 새 스키마 — schema-postgres.sql 과 반드시 동일하게 유지할 것
-- ------------------------------------------------------------
CREATE TABLE tb_master_code
(
    id               VARCHAR(13)  NOT NULL PRIMARY KEY,

    parent_id        VARCHAR(13)  NULL,
    depth            INTEGER      NOT NULL DEFAULT 0,
    -- ★ NOT NULL. 서브트리 조회가 전적으로 이 값에 의존한다.
    path             VARCHAR(512) NOT NULL,

    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(512) NULL,

    attributes       JSONB        NOT NULL DEFAULT '{}'::JSONB,
    attribute_schema JSONB        NOT NULL DEFAULT '[]'::JSONB,

    sort             INTEGER      NOT NULL DEFAULT 0,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at       TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(64)  NULL,
    updated_at       TIMESTAMPTZ(6) NULL,
    updated_by       VARCHAR(64)  NULL,

    CONSTRAINT uk_master_code_parent_code UNIQUE (parent_id, code),
    CONSTRAINT fk_master_code_parent      FOREIGN KEY (parent_id) REFERENCES tb_master_code (id)
);

COMMENT ON TABLE  tb_master_code                   IS '마스터코드 (자기참조 트리 구조)';
COMMENT ON COLUMN tb_master_code.id                IS '마스터코드 ID (PK, TSID 13자)';
COMMENT ON COLUMN tb_master_code.parent_id         IS '부모 노드 ID (NULL이면 루트)';
COMMENT ON COLUMN tb_master_code.depth             IS '트리 깊이 (0=루트, 1+=하위)';
COMMENT ON COLUMN tb_master_code.path              IS 'Materialized Path (예: /0RF87Y7EXVPB9/0RF880A9HVQCF)';
COMMENT ON COLUMN tb_master_code.code              IS '코드값';
COMMENT ON COLUMN tb_master_code.name              IS '코드명';
COMMENT ON COLUMN tb_master_code.description       IS '설명';
COMMENT ON COLUMN tb_master_code.attributes        IS '코드별 속성값 (JSONB)';
COMMENT ON COLUMN tb_master_code.attribute_schema  IS '루트 노드 전용: 속성 스키마 정의 (JSONB)';
COMMENT ON COLUMN tb_master_code.sort              IS '정렬순서';
COMMENT ON COLUMN tb_master_code.is_active         IS '활성화 여부';
COMMENT ON COLUMN tb_master_code.created_at        IS '생성일시';
COMMENT ON COLUMN tb_master_code.created_by        IS '생성자';
COMMENT ON COLUMN tb_master_code.updated_at        IS '수정일시';
COMMENT ON COLUMN tb_master_code.updated_by        IS '수정자';

CREATE INDEX idx_mc_parent_id  ON tb_master_code (parent_id);
CREATE INDEX idx_mc_path       ON tb_master_code (path);
CREATE INDEX idx_mc_code       ON tb_master_code (code);
CREATE INDEX idx_mc_attributes ON tb_master_code USING GIN (attributes);

-- ------------------------------------------------------------
-- [3] 데이터 적재 — tb_master_code.csv (운영 덤프 65행) 기준
--
--     · id 는 원본 created_at 을 타임스탬프로 삼아 생성한 TSID 다. 시간 정렬 의미가 보존되고,
--       예전 숫자 id 와의 매핑은 남기지 않는다(외부에서 그 id 를 참조하는 곳이 없다).
--     · path 는 새 id 로 재구성했다. 최장 42자로 VARCHAR(512) 에 여유가 크다.
--     · 행 순서는 depth 오름차순이다 — 자기참조 FK 때문에 부모가 먼저 들어가야 한다.
--       (제약이 DEFERRABLE 이 아니라 순서로 보장한다. 행을 재정렬하지 말 것.)
--
--     ⚠️ CLAUDE / MY_CLAUDE 의 토큰은 <<refreshToken>> 같은 플레이스홀더로 비워 두었다.
--        실행 전에 실제 값으로 바꾸거나, 이 노드만 빼고 넣은 뒤 관리 화면에서 입력할 것.
--        (이 파일은 git 에 커밋되므로 실제 토큰을 적어 두지 말 것.)
--
--     ⚠️ PLATFORM 의 attribute_schema 에서 icon 의 sensitive 를 true → false 로 정정했다.
--        아이콘 이름은 lucide 컴포넌트명일 뿐이라 시크릿이 아니고, true 로 두면 비관리자
--        응답에서 아이콘만 사라진다. 지켜야 할 것은 내부 호스트명(url) 뿐이다.
-- ------------------------------------------------------------

INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, attribute_schema, sort, is_active, created_at, created_by, updated_at, updated_by)
VALUES
('0PRX0V713MAXY', NULL, 0, '/0PRX0V713MAXY', 'STATUS', '상태분류', '일반적인 상태 코드', '{}'::JSONB, '[]'::JSONB, 2, true, '2026-03-14 15:15:04.584625 +00:00', 'SYSTEM', NULL, NULL),
('0MQQNJ8M2MHGW', NULL, 0, '/0MQQNJ8M2MHGW', 'JIRA_STATUS', '지라 상태 코드', '지라 상태 코드', '{}'::JSONB, '[{"key": "statusCategory", "type": "text", "label": "상태카테고리"}, {"key": "isDone", "type": "text", "label": "완료여부"}]'::JSONB, 4, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V7P7DWVA', NULL, 0, '/0PRX0V7P7DWVA', 'FAVORITE', '즐겨찾기', '즐겨찾기 사이트 모음', '{}'::JSONB, '[{"key": "url", "type": "text", "label": "URL", "sensitive": "false"}, {"key": "icon", "type": "text", "label": "ICON", "sensitive": "false"}]'::JSONB, 5, true, '2026-03-14 15:15:04.753578 +00:00', 'SYSTEM', '2026-08-30 11:28:11.011786 +00:00', 'admin'),
('0Q1EWBKEKGT4K', NULL, 0, '/0Q1EWBKEKGT4K', 'CLAUDE', '클로드 세션', NULL, '{}'::JSONB, '[{"key": "refreshToken", "type": "text", "label": "refreshToken", "sensitive": "true"}, {"key": "accessToken", "type": "text", "label": "accessToken", "sensitive": "true"}, {"key": "expiresAt", "type": "text", "label": "expiresAt", "sensitive": "true"}]'::JSONB, 6, true, '2026-04-10 05:23:26.708329 +00:00', 'admin', '2026-06-21 09:58:26.279490 +00:00', 'admin'),
('0RF863EXAVWE0', NULL, 0, '/0RF863EXAVWE0', 'PLATFORM', '플랫폼', '내부 운영 플랫폼 링크 (관리자 전용)', '{}'::JSONB, '[{"key": "url", "type": "text", "label": "URL", "sensitive": "true"}, {"key": "icon", "type": "text", "label": "아이콘", "sensitive": "false"}]'::JSONB, 6, true, '2026-08-30 11:47:52.170146 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V75BSWV0', '0PRX0V713MAXY', 1, '/0PRX0V713MAXY/0PRX0V75BSWV0', 'ACTIVE', '활성', '활성화된 상태', '{}'::JSONB, '[]'::JSONB, 1, true, '2026-03-14 15:15:04.618447 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V7SGSTHT', '0PRX0V7P7DWVA', 1, '/0PRX0V7P7DWVA/0PRX0V7SGSTHT', 'COMMUNITY', 'Community', '커뮤니티 사이트', '{}'::JSONB, '[]'::JSONB, 1, true, '2026-03-14 15:15:04.780677 +00:00', 'SYSTEM', NULL, NULL),
('0RF863EX95CHC', '0RF863EXAVWE0', 1, '/0RF863EXAVWE0/0RF863EX95CHC', 'DEPLOY', '배포', NULL, '{"icon": "Rocket"}'::JSONB, '[]'::JSONB, 1, true, '2026-08-30 11:47:52.170146 +00:00', 'SYSTEM', NULL, NULL),
('0Q1F07R0HGFTH', '0Q1EWBKEKGT4K', 1, '/0Q1EWBKEKGT4K/0Q1F07R0HGFTH', 'MY_CLAUDE', '내 계정', NULL, '{"expiresAt": "<<expiresAt>>", "accessToken": "<<accessToken>>", "refreshToken": "<<refreshToken>>"}'::JSONB, '[]'::JSONB, 1, true, '2026-04-10 05:40:23.684103 +00:00', 'admin', '2026-08-30 07:05:00.541110 +00:00', 'SCHEDULER'),
('0MQQNJ8M14K7S', '0MQQNJ8M2MHGW', 1, '/0MQQNJ8M2MHGW/0MQQNJ8M14K7S', '해야 할 일(작업요청)', '해야 할 일(작업요청)', '해야 할 일(작업요청)', '{"isDone": "N", "statusCategory": "To Do"}'::JSONB, '[]'::JSONB, 1, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V75BR69A', '0PRX0V713MAXY', 1, '/0PRX0V713MAXY/0PRX0V75BR69A', 'INACTIVE', '비활성', '비활성화된 상태', '{}'::JSONB, '[]'::JSONB, 2, true, '2026-03-14 15:15:04.618447 +00:00', 'SYSTEM', NULL, NULL),
('0RF863EX9NR6F', '0RF863EXAVWE0', 1, '/0RF863EXAVWE0/0RF863EX9NR6F', 'OBSERVABILITY', '관측', NULL, '{"icon": "Activity"}'::JSONB, '[]'::JSONB, 2, true, '2026-08-30 11:47:52.170146 +00:00', 'SYSTEM', NULL, NULL),
('0MQQNJ8M2AMMX', '0MQQNJ8M2MHGW', 1, '/0MQQNJ8M2MHGW/0MQQNJ8M2AMMX', '작업예정(요청확인)', '작업예정(요청확인)', '작업예정(요청확인)', '{"isDone": "N", "statusCategory": "To Do"}'::JSONB, '[]'::JSONB, 2, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0RF863EX9J8P5', '0RF863EXAVWE0', 1, '/0RF863EXAVWE0/0RF863EX9J8P5', 'DATA', '데이터', NULL, '{"icon": "Database"}'::JSONB, '[]'::JSONB, 3, true, '2026-08-30 11:47:52.170146 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V75AV399', '0PRX0V713MAXY', 1, '/0PRX0V713MAXY/0PRX0V75AV399', 'PENDING', '대기', '처리 대기 상태', '{}'::JSONB, '[]'::JSONB, 3, true, '2026-03-14 15:15:04.618447 +00:00', 'SYSTEM', NULL, NULL),
('0MQQNJ8M1MPK1', '0MQQNJ8M2MHGW', 1, '/0MQQNJ8M2MHGW/0MQQNJ8M1MPK1', '중지(HOLDING)', '중지(HOLDING)', '중지(HOLDING)', '{"isDone": "N", "statusCategory": "To Do"}'::JSONB, '[]'::JSONB, 3, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V7584D72', '0PRX0V713MAXY', 1, '/0PRX0V713MAXY/0PRX0V7584D72', 'COMPLETED', '완료', '처리 완료 상태', '{}'::JSONB, '[]'::JSONB, 4, true, '2026-03-14 15:15:04.618447 +00:00', 'SYSTEM', NULL, NULL),
('0MQQNJ8M3JXQQ', '0MQQNJ8M2MHGW', 1, '/0MQQNJ8M2MHGW/0MQQNJ8M3JXQQ', '진행 불가(Reject)', '진행 불가(Reject)', '진행 불가(Reject)', '{"isDone": "N", "statusCategory": "To Do"}'::JSONB, '[]'::JSONB, 4, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V7595X6P', '0PRX0V713MAXY', 1, '/0PRX0V713MAXY/0PRX0V7595X6P', 'CANCELLED', '취소', '취소된 상태', '{}'::JSONB, '[]'::JSONB, 5, true, '2026-03-14 15:15:04.618447 +00:00', 'SYSTEM', NULL, NULL),
('0MQQNJ8M1NJ19', '0MQQNJ8M2MHGW', 1, '/0MQQNJ8M2MHGW/0MQQNJ8M1NJ19', '진행중', '진행중', '진행중', '{"isDone": "N", "statusCategory": "In Progress"}'::JSONB, '[]'::JSONB, 20, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V7SKMEZ8', '0PRX0V7P7DWVA', 1, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8', 'DEVTOOLS', '<devTools>', '개발 도구', '{}'::JSONB, '[]'::JSONB, 30, true, '2026-03-14 15:15:04.780677 +00:00', 'SYSTEM', '2026-03-20 05:20:37.334330 +00:00', 'admin'),
('0PRX0V7SK4618', '0PRX0V7P7DWVA', 1, '/0PRX0V7P7DWVA/0PRX0V7SK4618', 'MEMBERSHIP', 'Membership', '멤버십 관련', '{}'::JSONB, '[]'::JSONB, 31, true, '2026-03-14 15:15:04.780677 +00:00', 'SYSTEM', '2026-03-20 05:20:48.258965 +00:00', 'admin'),
('0PRX0V7SGHR3J', '0PRX0V7P7DWVA', 1, '/0PRX0V7P7DWVA/0PRX0V7SGHR3J', 'WEBTOOLS', 'WebTools', '웹 도구', '{}'::JSONB, '[]'::JSONB, 40, true, '2026-03-14 15:15:04.780677 +00:00', 'SYSTEM', '2026-03-20 05:20:34.251399 +00:00', 'admin'),
('0PRX0V7SGV19D', '0PRX0V7P7DWVA', 1, '/0PRX0V7P7DWVA/0PRX0V7SGV19D', 'GEOSERVICE', 'GeoService', '위치 서비스', '{}'::JSONB, '[]'::JSONB, 50, true, '2026-03-14 15:15:04.780677 +00:00', 'SYSTEM', '2026-03-20 05:20:30.272669 +00:00', 'admin'),
('0MQQNJ8M1JFMH', '0MQQNJ8M2MHGW', 1, '/0MQQNJ8M2MHGW/0MQQNJ8M1JFMH', 'QA요청', 'QA요청', 'QA요청', '{"isDone": "N", "statusCategory": "In Progress"}'::JSONB, '[]'::JSONB, 50, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V7SGXPB1', '0PRX0V7P7DWVA', 1, '/0PRX0V7P7DWVA/0PRX0V7SGXPB1', 'ETC', 'etc', '기타', '{}'::JSONB, '[]'::JSONB, 60, true, '2026-03-14 15:15:04.780677 +00:00', 'SYSTEM', '2026-03-20 05:20:26.992308 +00:00', 'admin'),
('0PRX0V7SJ2ZVS', '0PRX0V7P7DWVA', 1, '/0PRX0V7P7DWVA/0PRX0V7SJ2ZVS', 'HARDWARE', '철물점', '철물점', '{}'::JSONB, '[]'::JSONB, 70, true, '2026-03-14 15:15:04.780677 +00:00', 'SYSTEM', '2026-03-20 05:20:21.955795 +00:00', 'admin'),
('0PRX0V7SH5V14', '0PRX0V7P7DWVA', 1, '/0PRX0V7P7DWVA/0PRX0V7SH5V14', 'STREAMING', 'Streaming', '스트리밍', '{}'::JSONB, '[]'::JSONB, 80, true, '2026-03-14 15:15:04.780677 +00:00', 'SYSTEM', '2026-03-20 05:20:11.542338 +00:00', 'admin'),
('0QAFZG4XWNFNQ', '0PRX0V7P7DWVA', 1, '/0PRX0V7P7DWVA/0QAFZG4XWNFNQ', 'TRACKING_NO', 'TRACKING', NULL, '{}'::JSONB, '[]'::JSONB, 90, true, '2026-05-08 07:02:18.095898 +00:00', 'admin', '2026-05-08 07:02:34.894363 +00:00', 'admin'),
('0MQQNJ8M1H6JN', '0MQQNJ8M2MHGW', 1, '/0MQQNJ8M2MHGW/0MQQNJ8M1H6JN', '취소', '취소', '취소', '{"isDone": "N", "statusCategory": "Done"}'::JSONB, '[]'::JSONB, 90, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0MQQNJ8M1PB0Q', '0MQQNJ8M2MHGW', 1, '/0MQQNJ8M2MHGW/0MQQNJ8M1PB0Q', '배포준비', '배포준비', '배포준비', '{"isDone": "Y", "statusCategory": "Done"}'::JSONB, '[]'::JSONB, 99, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0MQQNJ8M225NV', '0MQQNJ8M2MHGW', 1, '/0MQQNJ8M2MHGW/0MQQNJ8M225NV', '작업완료', '작업완료', '작업완료', '{"isDone": "Y", "statusCategory": "Done"}'::JSONB, '[]'::JSONB, 100, true, '2025-08-24 04:01:40.000000 +00:00', 'SYSTEM', NULL, NULL),
('0PRX9XB08QD7T', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX9XB08QD7T', 'UTILS', 'UTILS', NULL, '{"url": "/util"}'::JSONB, '[]'::JSONB, 0, true, '2026-03-14 15:54:41.282504 +00:00', 'admin', '2026-03-14 15:55:27.617010 +00:00', 'admin'),
('0PRX0V7X4DCVN', '0PRX0V7SGSTHT', 2, '/0PRX0V7P7DWVA/0PRX0V7SGSTHT/0PRX0V7X4DCVN', 'CLIEN', 'clien', '클리앙', '{"url": "http://www.clien.net/"}'::JSONB, '[]'::JSONB, 1, true, '2026-03-14 15:15:04.809812 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V8G2NJ10', '0PRX0V7SJ2ZVS', 2, '/0PRX0V7P7DWVA/0PRX0V7SJ2ZVS/0PRX0V8G2NJ10', 'IVERANDA', '아이베란다', '아이베란다', '{"url": "http://www.iveranda.com/"}'::JSONB, '[]'::JSONB, 1, true, '2026-03-14 15:15:04.960962 +00:00', 'SYSTEM', NULL, NULL),
('0QAFZRTZ5P4PX', '0QAFZG4XWNFNQ', 2, '/0PRX0V7P7DWVA/0QAFZG4XWNFNQ/0QAFZRTZ5P4PX', 'KINGTRANS', 'KING TRANS(TPCell 배송추적)', NULL, '{"url": "https://17exp.kingtrans.cn/WebTrack?action=list"}'::JSONB, '[]'::JSONB, 1, true, '2026-05-08 07:03:29.273630 +00:00', 'admin', '2026-05-08 07:03:29.293869 +00:00', 'admin'),
('0PRX0V8A254NR', '0PRX0V7SGV19D', 2, '/0PRX0V7P7DWVA/0PRX0V7SGV19D/0PRX0V8A254NR', 'NAVER_MYPLACE', '네이버 마이플레이스', '네이버 마이플레이스', '{"url": "https://m.store.naver.com/myplace/home"}'::JSONB, '[]'::JSONB, 1, true, '2026-03-14 15:15:04.912347 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V80FZV9F', '0PRX0V7SK4618', 2, '/0PRX0V7P7DWVA/0PRX0V7SK4618/0PRX0V80FZV9F', 'NAVER_PLUS_LGU', '네이버플러스 X Lgu+', '네이버플러스 LGU+', '{"url": "https://nid.naver.com/membership/partner/uplus"}'::JSONB, '[]'::JSONB, 1, true, '2026-03-14 15:15:04.835767 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V8706WXD', '0PRX0V7SGHR3J', 2, '/0PRX0V7P7DWVA/0PRX0V7SGHR3J/0PRX0V8706WXD', 'PHOTOPEA', '웹용 포토샵', '웹 포토샵', '{"url": "https://www.photopea.com"}'::JSONB, '[]'::JSONB, 1, true, '2026-03-14 15:15:04.888676 +00:00', 'SYSTEM', NULL, NULL),
('0PXXT4VP7EWK6', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PXXT4VP7EWK6', 'PORTAINER_LOCAL', 'Local Potainer', NULL, '{"url": "https://localhost:9443"}'::JSONB, '[]'::JSONB, 1, true, '2026-03-30 05:55:14.481027 +00:00', 'admin', '2026-04-30 06:44:27.927486 +00:00', 'admin'),
('0PRX0V8D9MXP4', '0PRX0V7SGXPB1', 2, '/0PRX0V7P7DWVA/0PRX0V7SGXPB1/0PRX0V8D9MXP4', 'VPNGATE', 'VPN Gate', 'VPN Gate', '{"url": "http://www.vpngate.net/en/"}'::JSONB, '[]'::JSONB, 1, true, '2026-03-14 15:15:04.938091 +00:00', 'SYSTEM', NULL, NULL),
('0PTPSMRBB8KMS', '0PRX0V7SH5V14', 2, '/0PRX0V7P7DWVA/0PRX0V7SH5V14/0PTPSMRBB8KMS', 'Youtube', 'Youtube', NULL, '{"url": "https://www.youtube.com"}'::JSONB, '[]'::JSONB, 1, true, '2026-03-20 05:52:35.930213 +00:00', 'admin', '2026-03-20 05:52:53.943679 +00:00', 'admin'),
('0PRX0V8703ZS8', '0PRX0V7SGHR3J', 2, '/0PRX0V7P7DWVA/0PRX0V7SGHR3J/0PRX0V8703ZS8', 'CIDR', 'Calc Cidr(IP대역계산)', 'CIDR 계산기', '{"url": "https://www.ipaddressguide.com/cidr"}'::JSONB, '[]'::JSONB, 2, true, '2026-03-14 15:15:04.888676 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V8DABPT2', '0PRX0V7SGXPB1', 2, '/0PRX0V7P7DWVA/0PRX0V7SGXPB1/0PRX0V8DABPT2', 'DOSGAMES', 'Almost 2,400 DOS GAMES using PC Browsers', 'DOS 게임 아카이브', '{"url": "https://archive.org/details/softwarelibrary_msdos_games/v2"}'::JSONB, '[]'::JSONB, 2, true, '2026-03-14 15:15:04.938091 +00:00', 'SYSTEM', NULL, NULL),
('0R39535D2J15W', '0PRX0V7SGV19D', 2, '/0PRX0V7P7DWVA/0PRX0V7SGV19D/0R39535D2J15W', 'MAP_NAVER', '네이버지도', NULL, '{"url": "https://map.naver.com"}'::JSONB, '[]'::JSONB, 2, true, '2026-07-24 07:16:10.728284 +00:00', 'admin', '2026-07-24 07:16:10.745023 +00:00', 'admin'),
('0PRX0V80C57YW', '0PRX0V7SK4618', 2, '/0PRX0V7P7DWVA/0PRX0V7SK4618/0PRX0V80C57YW', 'NAVER_PLUS', '네이버플러스', '네이버플러스', '{"url": "https://nid.naver.com/membership/my?m=viewSaving"}'::JSONB, '[]'::JSONB, 2, true, '2026-03-14 15:15:04.835767 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V7X44PQ1', '0PRX0V7SGSTHT', 2, '/0PRX0V7P7DWVA/0PRX0V7SGSTHT/0PRX0V7X44PQ1', 'OKKY', 'okky', 'OKKY', '{"url": "https://okky.kr/"}'::JSONB, '[]'::JSONB, 2, true, '2026-03-14 15:15:04.809812 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V8D8H8GV', '0PRX0V7SGXPB1', 2, '/0PRX0V7P7DWVA/0PRX0V7SGXPB1/0PRX0V8D8H8GV', 'ARCADE', 'Internet Arcade', '인터넷 아케이드', '{"url": "https://archive.org/details/internetarcade"}'::JSONB, '[]'::JSONB, 3, true, '2026-03-14 15:15:04.938091 +00:00', 'SYSTEM', NULL, NULL),
('0R3955Z9TDCGM', '0PRX0V7SGV19D', 2, '/0PRX0V7P7DWVA/0PRX0V7SGV19D/0R3955Z9TDCGM', 'MAP_KAKAO', '카카오지도', NULL, '{"url": "https://map.kakao.com"}'::JSONB, '[]'::JSONB, 3, true, '2026-07-24 07:16:33.742648 +00:00', 'admin', '2026-07-24 07:16:33.754659 +00:00', 'admin'),
('0PRX0V7X5F6K9', '0PRX0V7SGSTHT', 2, '/0PRX0V7P7DWVA/0PRX0V7SGSTHT/0PRX0V7X5F6K9', 'AAGAG', 'AAGAG', 'AAGAG', '{"url": "https://aagag.com/mirror/?target=_blank&time=12"}'::JSONB, '[]'::JSONB, 4, true, '2026-03-14 15:15:04.809812 +00:00', 'SYSTEM', NULL, NULL),
('0R3958AAV2RJH', '0PRX0V7SGV19D', 2, '/0PRX0V7P7DWVA/0PRX0V7SGV19D/0R3958AAV2RJH', 'MAP_GOOGLE', '구글지도', NULL, '{"url": "https://google.com/maps"}'::JSONB, '[]'::JSONB, 4, true, '2026-07-24 07:16:52.950554 +00:00', 'admin', '2026-07-24 07:17:31.235003 +00:00', 'admin'),
('0Q7JMCH4STR6S', '0PRX0V7SGXPB1', 2, '/0PRX0V7P7DWVA/0PRX0V7SGXPB1/0Q7JMCH4STR6S', 'TECH_BLOG_POSTS', '테크 블로그 포스트', NULL, '{"url": "https://www.techblogposts.com/ko"}'::JSONB, '[]'::JSONB, 4, true, '2026-04-29 05:31:24.326308 +00:00', 'admin', '2026-04-29 05:31:24.350671 +00:00', 'admin'),
('0PRX0V7X6564E', '0PRX0V7SGSTHT', 2, '/0PRX0V7P7DWVA/0PRX0V7SGSTHT/0PRX0V7X6564E', 'PPOMPPU', '뽐뿌', '뽐뿌', '{"url": "http://www.ppomppu.co.kr/"}'::JSONB, '[]'::JSONB, 5, true, '2026-03-14 15:15:04.809812 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V7X5R6ZB', '0PRX0V7SGSTHT', 2, '/0PRX0V7P7DWVA/0PRX0V7SGSTHT/0PRX0V7X5R6ZB', 'PPOMPPU_CAMPING', '뽐뿌 - 캠핑포럼', '뽐뿌 캠핑포럼', '{"url": "http://m.ppomppu.co.kr/new/bbs_list.php?id=camping"}'::JSONB, '[]'::JSONB, 6, true, '2026-03-14 15:15:04.809812 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V7X7XZC3', '0PRX0V7SGSTHT', 2, '/0PRX0V7P7DWVA/0PRX0V7SGSTHT/0PRX0V7X7XZC3', 'PPOMPPU_FISHING', '뽐뿌 - 낚시포럼', '뽐뿌 낚시포럼', '{"url": "http://m.ppomppu.co.kr/new/bbs_list.php?id=fishing"}'::JSONB, '[]'::JSONB, 7, true, '2026-03-14 15:15:04.809812 +00:00', 'SYSTEM', NULL, NULL),
('0PRX0V83RNV05', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX0V83RNV05', 'MAKEREADME', 'Make readme.md', 'README 생성기', '{"url": "https://www.makeareadme.com/"}'::JSONB, '[]'::JSONB, 10, true, '2026-03-14 15:15:04.862421 +00:00', 'SYSTEM', '2026-04-30 06:45:16.723090 +00:00', 'admin'),
('0PRX0V8M58ZYP', '0PRX0V7SH5V14', 2, '/0PRX0V7P7DWVA/0PRX0V7SH5V14/0PRX0V8M58ZYP', 'NUNUTV', '누누.tv', '누누TV', '{"url": "https://nunutv1.me/"}'::JSONB, '[]'::JSONB, 10, true, '2026-03-14 15:15:04.993359 +00:00', 'SYSTEM', '2026-03-20 05:52:07.757810 +00:00', 'admin'),
('0PRX0V83RR3G6', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX0V83RR3G6', 'CODESANDBOX', 'Code Sandbox', '코드 샌드박스', '{"url": "https://codesandbox.io"}'::JSONB, '[]'::JSONB, 20, true, '2026-03-14 15:15:04.862421 +00:00', 'SYSTEM', '2026-04-30 06:45:12.382460 +00:00', 'admin'),
('0PRX0V83T4X2F', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX0V83T4X2F', 'JSFIDDLE', 'Js Fiddle', 'JS Fiddle', '{"url": "https://jsfiddle.net/user/fiddles/all/"}'::JSONB, '[]'::JSONB, 30, true, '2026-03-14 15:15:04.862421 +00:00', 'SYSTEM', '2026-04-30 06:45:07.326070 +00:00', 'admin'),
('0PRX0V83S33DN', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX0V83S33DN', 'ENCODING', 'Encoding', '인코딩 도구', '{"url": "https://coderstoolbox.net/"}'::JSONB, '[]'::JSONB, 40, true, '2026-03-14 15:15:04.862421 +00:00', 'SYSTEM', '2026-04-30 06:45:04.023216 +00:00', 'admin'),
('0PRX0V83SR6SB', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX0V83SR6SB', 'JSON2JAVA', 'Json to JavaClass', 'JSON to Java 변환', '{"url": "https://codebeautify.org/json-to-java-converter"}'::JSONB, '[]'::JSONB, 50, true, '2026-03-14 15:15:04.862421 +00:00', 'SYSTEM', '2026-04-30 06:44:59.647810 +00:00', 'admin'),
('0PRX0V83S09XM', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX0V83S09XM', 'EPOCH', 'Epoch & Unix Timestamp', 'Epoch 시간 변환', '{"url": "https://www.epochconverter.com/"}'::JSONB, '[]'::JSONB, 60, true, '2026-03-14 15:15:04.862421 +00:00', 'SYSTEM', '2026-04-30 06:44:52.943964 +00:00', 'admin'),
('0PRX0V83VV775', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX0V83VV775', 'REGEX101', 'regex101(정규식 검색)', '정규식 라이브러리', '{"url": "https://regex101.com/library"}'::JSONB, '[]'::JSONB, 70, true, '2026-03-14 15:15:04.862421 +00:00', 'SYSTEM', '2026-04-30 06:44:49.753802 +00:00', 'admin'),
('0PRX0V83TJ3TX', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX0V83TJ3TX', 'REGEXR', '정규식 테스트', '정규식 테스터', '{"url": "https://regexr.com"}'::JSONB, '[]'::JSONB, 80, true, '2026-03-14 15:15:04.862421 +00:00', 'SYSTEM', '2026-04-30 06:44:46.139422 +00:00', 'admin'),
('0PRX0V83R4ZKP', '0PRX0V7SKMEZ8', 2, '/0PRX0V7P7DWVA/0PRX0V7SKMEZ8/0PRX0V83R4ZKP', 'REGEXSTORM', '정규식 테스트(.net)', '.NET 정규식 테스터', '{"url": "http://regexstorm.net/tester"}'::JSONB, '[]'::JSONB, 90, true, '2026-03-14 15:15:04.862421 +00:00', 'SYSTEM', '2026-04-30 06:44:42.439777 +00:00', 'admin');

COMMIT;

-- ============================================================
-- 검증
-- ============================================================
-- (1) path 가 비었거나 자기 id 로 끝나지 않는 행 → 0건이어야 한다
--     (NOT NULL 이라 NULL 은 애초에 불가능하지만, 조립 실수를 잡는다)
-- SELECT id, depth, code, path FROM tb_master_code
--  WHERE path NOT LIKE '%/' || id;
--
-- (2) 서브트리 조회 재현 — 애플리케이션 findSubtree 와 같은 경계다
-- WITH r AS (SELECT path FROM tb_master_code WHERE code = 'PLATFORM' AND parent_id IS NULL)
-- SELECT m.id, m.depth, m.path, m.code, m.name FROM tb_master_code m, r
--  WHERE (m.path = r.path OR m.path LIKE r.path || '/%') AND m.is_active = true
--  ORDER BY m.depth, m.sort;
--
-- (3) 전체 트리와 건수 대조 — 두 조회가 같은 집합을 봐야 한다
-- SELECT count(*) FROM tb_master_code;
-- SELECT count(*) FROM tb_master_code_bak_20260830;
--
-- (4) 검증이 끝나면 백업 테이블 제거 (되돌릴 일이 없다고 확신한 뒤에)
-- DROP TABLE tb_master_code_bak_20260830;
