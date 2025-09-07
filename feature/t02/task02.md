# 지라 이슈 및 작업 로그 수집 스케줄러 구현 

## 요구사항 
- 스케줄러가 10분마다 동작하면서 지라의 특정 프로젝트의 모든 티켓의 내용을 수집 및 업데이트 
- 현재는 파이썬으로 수집해서 csv 로 만들고 있으나 db에 저장하여 사용하려고 함 
- com.atlassian.jira 사용하여 개발하였으면 함 
- 인증정보는 application.yml 에 별도로 지정 할 예정 
- modules/jira 패키지를 만들고 entity도 같이 추가해줘 
- 컨트롤러는 나중에 별도로 만들꺼야 
- 생성한 스키마는 schema.sql 파일에 업데이트 할꺼야

``` python
# 이슈의 수집 내역 
  for issue in all_issues:
    issue_start_date = getattr(issue.fields, 'customfield_10078', None)
    component = ", ".join([component.name for component in issue.fields.components])
    # todo : 한시적으로 QA요청 상태도 포함
    if (issue.fields.status.statusCategory.key == 'done'
        or issue.fields.status.name == 'QA요청'
    ) \
        and isinstance(component, str) and component.startswith(component_year):
      sprintlogs.append({
        'Issue Key': issue.key,
        'Link': link_prefix + issue.key,
        'Summary': issue.fields.summary,
        'Type': issue.fields.issuetype.name,
        'Status': issue.fields.status.name,
        'Assignee': issue.fields.assignee.displayName if issue.fields.assignee else None,
        'Components': component,
        'Components Length': len(issue.fields.components),
        'Story Points': getattr(issue.fields, 'customfield_10026', 0),
        'Start Date': issue_start_date
      })

# 작업로그 수집 내역 
    if hasattr(issue.fields, 'worklog') and issue.fields.worklog:
      if issue.fields.worklog.total > 19:
        origin_worklogs = jira_client.get_all_worklogs_for_issue(issue_key)
        # get_all_worklogs_for_issue가 dict 형태로 반환하는 경우 "worklogs" 키 사용
        if isinstance(origin_worklogs, dict) and "worklogs" in origin_worklogs:
          origin_worklogs = origin_worklogs["worklogs"]
      else:
        origin_worklogs = issue.fields.worklog.worklogs

      for wlog in origin_worklogs:
        # started 값은 클래스이든 dict이든 get_nested_value로 추출
        started = get_nested_value(wlog, "started", "")
        if started.startswith(current_year):
          author = get_nested_value(wlog, "author.displayName")
          time_spent = get_nested_value(wlog, "timeSpent")
          time_spent_seconds = get_nested_value(wlog, "timeSpentSeconds", 0)
          comment = get_nested_value(wlog, "comment", "No Comment")

          worklogs.append({
            'Issue Key': issue_key,
            'Type': issue_type,
            'Status': status,
            'Link': link_prefix + issue.key,
            'Summary': issue.fields.summary,
            'Author': author,
            'Components': component,
            'Time Spent': time_spent,
            'Time Hours': Decimal(time_spent_seconds / 3600).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP),
            'Comment': comment.replace('\n', ' '),
            'Started': started
          })
```


