# 작업 개선
- worklogs는 issueIds 를 사용하여 bulk로 가져올 수 없다는 걸 알았어.
  - getWorklogsForIssue 함수를 사용해야 해
- issue 별로 가져와서 worklogs를 업데이트 하려고 해.
- 이전에 사용하던 파이썬 파일이 있어.
  - 이건 worklog와 issue를 따로 csv로 만들긴 하지만 응용할 수 있을 것 같아.
  - `jira_helper.py`
  - `main.py`
- 속도 최적화가 필요해. 현재는 2000개 수집하는데 대략 1분 이상 걸리고 있어.
- 병렬처리 같은 걸 넣어서 개선하고 싶어

---

## Plan
Jira 워크로그 동기화 성능을 개선하기 위해 bulk API 대신 issue별 워크로그 조회 방식으로 변경하고, 병렬처리를 통해 속도를 최적화합니다. Python 코드의 효율적인 로직을 참고하여 Java Spring에 적용합니다.

## Tasks
- [ ] T1: 현재 bulk API 방식 제거 및 issue별 워크로그 조회 로직으로 변경
- [ ] T2: 워크로그 19개 이상 시 별도 API 조회하는 최적화 로직 추가
- [ ] T3: CompletableFuture를 활용한 병렬처리 구현으로 성능 개선(taskExecutor bean 사용)
- [ ] T4: 테스트 실행 및 성능 측정으로 개선사항 검증

---

## Progress

### 현재 상태
- [x] T1: bulk API 방식 제거 및 issue별 조회 방식 변경 ✅
- [x] T2: 워크로그 최적화 로직 추가 ✅
- [x] T2.5: 불필요한 API 호출 추가 제거 ✅
- [x] T3: 병렬처리 구현 ✅
- [ ] T4: 성능 테스트 및 검증

### 작업 로그
**T3 완료** - 2025-01-27 16:15
- 소요시간: 20분
- 완료사항:
  - TaskExecutor bean을 JiraClientWrapper에 주입하여 병렬처리 기반 구축
  - fetchIssueBatch와 getFilteredIssues에서 CompletableFuture 병렬처리 구현
  - 각 이슈의 워크로그 조회를 독립적인 스레드에서 병렬 실행
  - taskExecutor 설정: 20개 핵심 스레드, 50개 최대 스레드로 동시 처리
  - **대폭 성능 향상 예상**: 2000개 이슈 처리 시간을 순차처리 대비 크게 단축

**T2.5 완료** - 2025-01-27 16:00
- 소요시간: 10분
- 완료사항:
  - getWorklogsForIssue 메소드 오버로드 추가: JiraIssueResponse 객체를 받는 최적화 버전
  - getOptimizedWorklogs에서 최적화된 버전 사용하도록 수정
  - 불필요한 getIssueInternal API 호출 완전 제거
  - **추가 성능 개선**: 워크로그 많은 이슈에서도 이슈 재조회 API 호출 제거

**T2 완료** - 2025-01-27 15:45
- 소요시간: 15분
- 완료사항:
  - Python 로직 적용: worklog.total > 19면 별도 API 호출, 그렇지 않으면 기본 워크로그 사용
  - getOptimizedWorklogs 메소드 추가로 워크로그 최적화 조회 구현
  - convertToApplicationDtoWithoutWorklogs 메소드 추가
  - expand 파라미터에 worklog 포함하여 기본 워크로그 정보 수신
  - 성능 대폭 개선 예상: 대부분 이슈는 별도 API 호출 없이 처리

**T1 완료** - 2025-01-27 15:30
- 소요시간: 20분
- 완료사항:
  - fetchIssueBatch에서 bulk API 호출 제거, issue별 getWorklogsForIssue 호출로 변경
  - getFilteredIssues에서도 동일한 방식으로 수정
  - fetchWorklogsBatch와 convertToApplicationDtoWithWorklogs 메소드에 @Deprecated 표시

### 완료된 작업
**T3: 병렬처리 구현으로 성능 개선** ✅
- `TaskExecutor` bean 활용한 CompletableFuture 기반 병렬처리 완전 구현
- 각 이슈의 워크로그 조회를 독립적인 스레드에서 병렬 실행 (20-50개 동시 처리)
- `fetchIssueBatch`와 `getFilteredIssues` 양쪽 모두에 병렬처리 적용
- 개별 이슈 실패 시에도 전체 프로세스 중단 없이 안정적 처리
- **최종 성능 혁신**: 2000개 이슈 처리 시간을 순차 대비 최대 20-50배 단축 예상

**T2.5: 불필요한 API 호출 추가 제거** ✅
- `getWorklogsForIssue` 메소드 오버로드로 중복 이슈 조회 API 호출 제거
- 이미 보유한 `JiraIssueResponse` 객체 활용하여 `getIssueInternal` 호출 최소화
- 메소드 호환성 유지: 기존 `String issueKey` 버전과 새로운 `JiraIssueResponse` 버전 공존
- **최종 성능 개선**: 모든 워크로그 조회에서 불필요한 이슈 재조회 API 호출 완전 제거

**T2: 워크로그 최적화 로직 추가** ✅
- Python의 핵심 최적화 로직 완벽 구현: `worklog.total > 19` 체크
- 대부분 이슈(워크로그 ≤19개)는 기본 워크로그만 사용하여 API 호출 최소화
- 워크로그가 많은 이슈만 선별적으로 별도 API 호출하여 전체 조회
- **성능 개선 효과**: API 호출 횟수 대폭 감소로 속도 향상 예상

**T1: bulk API 방식 제거 및 issue별 조회 방식 변경** ✅
- Python 코드 로직 적용하여 각 issue별로 워크로그를 개별 조회하도록 변경
- 실제로 동작하지 않던 bulk worklog API 방식을 완전히 제거
- 개별 이슈 처리 실패 시에도 전체 프로세스가 중단되지 않도록 예외 처리 개선

---

## 결과물

T1 작업을 진행해도 될까요?
