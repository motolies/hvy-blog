# Jira 모듈

## 개요
이 모듈은 Jira 이슈와 워크로그를 자동으로 수집하여 데이터베이스에 저장하는 기능을 제공합니다.

## 주요 기능
- 10분마다 자동 동기화 (스케줄러)
- Jira 이슈 정보 수집 및 저장
- Jira 워크로그 정보 수집 및 저장
- REST API를 통한 조회 기능 (추후 컨트롤러 추가 예정)

## 아키텍처
```
modules/jira/
├── domain/
│   ├── entity/          # JPA 엔티티
│   └── repository/      # 리포지토리 인터페이스
├── application/
│   ├── dto/             # 데이터 전송 객체
│   └── service/         # 비즈니스 로직
└── infrastructure/
    ├── client/          # Jira REST 클라이언트
    ├── config/          # 설정 및 구성
    └── scheduler/       # 스케줄러
```

## 설정

### application.yml
```yaml
jira:
  url: https://your-company.atlassian.net
  username: your-email@company.com
  api-token: your-api-token
  project-key: PROJECT

scheduler:
  jira:
    lock-name: JIRA-SYNC-PROD
    cron-expression: "0 */10 * * * ?"
```

### 환경변수
- `JIRA_URL`: Jira 서버 URL
- `JIRA_USERNAME`: Jira 사용자명 (이메일)
- `JIRA_API_TOKEN`: Jira API 토큰
- `JIRA_PROJECT_KEY`: 수집할 프로젝트 키

## 데이터 모델

### JiraIssue (tb_jira_issue)
- 이슈 키, 링크, 요약, 타입, 상태
- 담당자, 컴포넌트, 스토리 포인트
- 시작일, 생성/수정 정보

### JiraWorklog (tb_jira_worklog)
- 이슈 정보 (FK)
- 작업자, 소요시간, 코멘트
- 작업 시작 일시, 워크로그 ID

## 사용법

### 수동 동기화
```java
@Autowired
private JiraSyncScheduler jiraSyncScheduler;

// 수동 동기화 실행
jiraSyncScheduler.manualSync();
```

### 데이터 조회
```java
@Autowired
private JiraQueryService jiraQueryService;

// 모든 이슈 조회
Page<JiraIssueDto> issues = jiraQueryService.getAllIssues(pageable);

// 특정 이슈 조회
JiraIssueDto issue = jiraQueryService.getIssueByKey("PROJ-123");

// 워크로그 조회
List<JiraWorklogDto> worklogs = jiraQueryService.getWorklogsByIssueKey("PROJ-123");
```

## 모니터링
- 스케줄러 실행 로그 확인
- 데이터베이스 동기화 상태 모니터링
- Jira API 연결 상태 테스트

## 주의사항
1. Jira API 토큰은 보안이 중요하므로 환경변수로 관리
2. 대량 데이터 동기화 시 API 호출 제한에 주의
3. 네트워크 오류 시 재시도 로직 구현 권장
4. 스케줄러 잠금 시간 조정 필요 시 설정 변경