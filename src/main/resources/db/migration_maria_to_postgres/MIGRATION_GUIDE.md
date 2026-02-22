# MariaDB → PostgreSQL 데이터 마이그레이션 가이드

## 개요

이 가이드는 MariaDB에 저장된 블로그 데이터를 PostgreSQL로 이관하는 절차를 설명합니다.

### 스크립트 파일 목록

| 파일                         | 실행 환경          | 역할                               |
|----------------------------|----------------|----------------------------------|
| `extract-from-mariadb.sql` | **MariaDB**    | 데이터를 추출하여 PostgreSQL INSERT 문 생성 |
| `reset-sequences.sql`      | **PostgreSQL** | IDENTITY 시퀀스를 max(id)+1로 리셋      |
| `verify-migration.sql`     | **PostgreSQL** | 마이그레이션 결과 검증                     |

---

## 사전 준비

### 1. MariaDB 접속 정보 확인
```bash
# MariaDB 접속 테스트
mysql -h {HOST} -u {USER} -p {DATABASE}
```

### 2. PostgreSQL 접속 정보 확인
```bash
# PostgreSQL 접속 테스트 (Docker 사용 시)
psql -h localhost -p 5432 -U motolies -d blog

# Docker Compose 사용 시
docker exec -it {postgres_container} psql -U motolies -d blog
```

### 3. PostgreSQL 스키마 생성 확인
PostgreSQL에 `schema-postgres.sql`로 스키마가 미리 생성되어 있어야 합니다:
```bash
psql -U motolies -d blog -f src/main/resources/schema-postgres.sql
```

---

## 마이그레이션 절차

### Step 1: MariaDB에서 데이터 추출

MariaDB에서 `extract-from-mariadb.sql`을 실행하여 PostgreSQL INSERT 문을 파일로 저장합니다.

```bash
# 방법 1: 로컬 MariaDB
mysql -N -r -u {USER} -p {DATABASE} \
  < src/main/resources/db/migration/extract-from-mariadb.sql \
  > extracted-data.sql

# 방법 2: 원격 MariaDB
mysql -N -r -h {HOST} -P {PORT} -u {USER} -p {DATABASE} \
  < src/main/resources/db/migration/extract-from-mariadb.sql \
  > extracted-data.sql

# 방법 3: Docker의 MariaDB
docker exec -i {mariadb_container} mysql -N -r -u {USER} -p{PASSWORD} {DATABASE} \
  < src/main/resources/db/migration/extract-from-mariadb.sql \
  > extracted-data.sql
```

> **주의**: 비밀번호에 특수문자가 포함된 경우 환경변수로 전달하거나 `.my.cnf` 파일을 사용하세요.

추출된 파일(`extracted-data.sql`) 내용을 간단히 확인합니다:
```bash
head -50 extracted-data.sql  # 처음 50줄 확인
wc -l extracted-data.sql     # 총 줄 수 확인
```

---

### Step 2: PostgreSQL에 데이터 적재

```bash
# 방법 1: 로컬 PostgreSQL
psql -U motolies -d blog -f extracted-data.sql

# 방법 2: Docker의 PostgreSQL
docker exec -i {postgres_container} psql -U motolies -d blog \
  < extracted-data.sql

# 방법 3: 원격 PostgreSQL
psql -h {HOST} -p 5432 -U motolies -d blog -f extracted-data.sql

# 디버깅
docker exec -i {postgres_container} psql -a -U motolies -d blog < extracted-data.sql 2>&1 | head -80
```

오류 발생 시 트랜잭션이 롤백되므로 안전하게 재실행 가능합니다.

---

### Step 3: IDENTITY 시퀀스 리셋

데이터 적재 후 **반드시 실행**해야 합니다. 시퀀스를 리셋하지 않으면 새 레코드 삽입 시 ID 충돌이 발생합니다.

```bash
# 방법 1: 로컬 PostgreSQL
psql -U motolies -d blog -f reset-sequences.sql

# 방법 2: Docker의 PostgreSQL
docker exec -i {postgres_container} psql -U motolies -d blog \
  < reset-sequences.sql

# 방법 3: 원격 PostgreSQL
psql -h {HOST} -p 5432 -U motolies -d blog -f reset-sequences.sql
```

실행 결과에서 각 시퀀스의 `last_value`가 해당 테이블의 `MAX(id)`보다 큰지 확인하세요.

---

### Step 4: 마이그레이션 검증

```bash
# 방법 1: 로컬 PostgreSQL
psql -U motolies -d blog -f verify-migration.sql

# 방법 2: Docker의 PostgreSQL
docker exec -i {postgres_container} psql -U motolies -d blog \
  < verify-migration.sql

# 방법 3: 원격 PostgreSQL
psql -h {HOST} -p 5432 -U motolies -d blog -f verify-migration.sql
```

확인 항목:
1. **Row Count**: MariaDB와 PostgreSQL의 각 테이블 행 수가 일치하는지 확인
2. **FK 위반**: 모든 FK 위반 수가 `0`인지 확인
3. **샘플 데이터**: 최신 포스트 5개 내용 확인
4. **시퀀스 값**: last_value가 max(id)보다 큰지 확인

MariaDB에서도 동일한 Row Count를 확인하려면:
```sql
-- MariaDB에서 실행
SELECT 'tb_post' AS tbl, COUNT(*) AS cnt FROM tb_post
UNION ALL SELECT 'tb_category', COUNT(*) FROM tb_category
-- ... 나머지 테이블
```

---

### Step 5: 애플리케이션 기동 테스트

```bash
cd hvy-blog
./gradlew bootRun
```

`ddl-auto: validate` 설정으로 인해 스키마 불일치가 있으면 기동 시 즉시 오류가 발생합니다. 정상 기동되면 마이그레이션이 성공한 것입니다.

---

## 트러블슈팅

### 문제 1: `extracted-data.sql`에 빈 행이나 오류가 포함됨

MySQL/MariaDB 클라이언트가 SELECT 결과 행 수 등의 메타 정보를 출력할 수 있습니다. 정리하려면:
```bash
grep -E "^(INSERT|BEGIN|COMMIT|--)" extracted-data.sql > extracted-data-clean.sql
```

### 문제 2: `$$` 달러 인용이 작동하지 않음

`body`, `content` 등 긴 텍스트 컬럼에서 `$$` 인용을 사용했습니다. 텍스트에 `$$`가 포함된 경우 대안:
```sql
-- 대안: E-string 사용
E'텍스트 내용'
```
이 경우 `'` → `''` 이스케이프가 필요합니다. `extract-from-mariadb.sql`의 해당 컬럼을 수정하세요.

### 문제 3: `tb_common_code` INSERT 시 `class_id`를 찾지 못함

MariaDB의 `tb_common_class.name` 값과 PostgreSQL의 `tb_common_class.code` 값이 다른 경우입니다. `extract-from-mariadb.sql`에서 common_class 추출 시 `name` 컬럼을 확인하세요:
```sql
-- MariaDB에서 확인
SELECT name, displayName FROM tb_common_class;
```

### 문제 4: FK 위반 발생

FK 위반이 있는 경우 데이터 정합성 문제입니다. 위반 레코드를 확인하고 원본 MariaDB 데이터를 점검하세요.

### 문제 5: 기존 데이터가 이미 있는 경우 재실행

```bash
# PostgreSQL에서 기존 데이터 삭제 후 재실행
psql -U motolies -d blog -c "
SET session_replication_role = 'replica';  -- FK 비활성화
TRUNCATE TABLE tb_memo, tb_memo_category, tb_jira_worklog, tb_jira_issue,
               tb_common_code, tb_common_class, tb_search_engine,
               tb_post_tag_map, tb_tag, tb_file, tb_post, tb_category,
               tb_user_authority_map, tb_user, tb_authority CASCADE;
SET session_replication_role = 'origin';  -- FK 복구
"
```

---

## 롤백

마이그레이션이 실패하거나 이전 상태로 돌아가야 할 경우:

1. PostgreSQL에서 스키마 전체 재생성:
```bash
psql -U motolies -d blog -f src/main/resources/schema-postgres.sql
# schema-postgres.sql 상단의 DROP TABLE이 포함되어 있으므로 재생성 가능
```

2. MariaDB 서버로 애플리케이션 연결 전환 (application.yml 수정)

---

## 변환 규칙 요약

| MariaDB                       | PostgreSQL                | 비고                           |
|-------------------------------|---------------------------|------------------------------|
| `camelCase` 컬럼명               | `snake_case` 컬럼명          | `categoryId` → `category_id` |
| `bit` 0                       | `false`                   | boolean 변환                   |
| `bit` 1                       | `true`                    | boolean 변환                   |
| `datetime(6)`                 | `TIMESTAMP(6)`            | 형식 동일                        |
| `auto_increment` ID           | `OVERRIDING SYSTEM VALUE` | IDENTITY 컬럼 직접 삽입            |
| `tb_common_class.name`        | `tb_common_class.code`    | 구조 변경                        |
| `tb_common_class.displayName` | `tb_common_class.name`    | 컬럼명 변경                       |
| `tb_common_code.className`    | `tb_common_code.class_id` | 문자열 FK → 숫자 FK               |
