# 오류 수정
- worklog 조회시에 맵핑 오류 수정 (아래 응답 참조) 
- 시간 형식 확인하여 맵핑 필요 `2025-09-05T17:01:46.633+0900`
- 아래 오류 확인
  - `Caused by: com.fasterxml.jackson.databind.exc.MismatchedInputException: Cannot deserialize value of type `java.lang.String` from Object value (token `JsonToken.START_OBJECT`)
   at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 2078] (through reference chain: kr.hvy.blog.modules.jira.infrastructure.client.dto.JiraIssueResponse$JiraWorklogContainerDto["worklogs"]->java.util.ArrayList[0]->kr.hvy.blog.modules.jira.infrastructure.client.dto.JiraWorklogResponse["comment"])`

---

없을 때 
```json
{
  "startAt" : 0,
  "maxResults" : 1000,
  "total" : 0,
  "worklogs" : [ ]
}

```

있을 때 
```json
{
  "startAt": 0,
  "maxResults": 1000,
  "total": 2,
  "worklogs": [
    {
      "self": "https://deleokorea.atlassian.net/rest/api/3/issue/38527/worklog/17682",
      "author": {
        "self": "https://deleokorea.atlassian.net/rest/api/3/user?accountId=63719fe93c26ca7fa0d02e91",
        "accountId": "63719fe93c26ca7fa0d02e91",
        "avatarUrls": {
          "48x48": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "24x24": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "16x16": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "32x32": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png"
        },
        "displayName": "윤문성",
        "active": true,
        "timeZone": "Asia/Seoul",
        "accountType": "atlassian"
      },
      "updateAuthor": {
        "self": "https://deleokorea.atlassian.net/rest/api/3/user?accountId=63719fe93c26ca7fa0d02e91",
        "accountId": "63719fe93c26ca7fa0d02e91",
        "avatarUrls": {
          "48x48": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "24x24": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "16x16": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "32x32": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png"
        },
        "displayName": "윤문성",
        "active": true,
        "timeZone": "Asia/Seoul",
        "accountType": "atlassian"
      },
      "comment": {
        "type": "doc",
        "version": 1,
        "content": [
          {
            "type": "paragraph",
            "content": [
              { "type": "text", "text": "요구사항 확인 코드 변경 qa 요청" }
            ]
          }
        ]
      },
      "created": "2025-09-05T16:17:20.357+0900",
      "updated": "2025-09-05T16:17:20.357+0900",
      "started": "2025-09-05T10:16:51.417+0900",
      "timeSpent": "6h",
      "timeSpentSeconds": 21600,
      "id": "17682",
      "issueId": "38527"
    },
    {
      "self": "https://deleokorea.atlassian.net/rest/api/3/issue/38527/worklog/17684",
      "author": {
        "self": "https://deleokorea.atlassian.net/rest/api/3/user?accountId=63719fe93c26ca7fa0d02e91",
        "accountId": "63719fe93c26ca7fa0d02e91",
        "avatarUrls": {
          "48x48": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "24x24": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "16x16": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "32x32": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png"
        },
        "displayName": "윤문성",
        "active": true,
        "timeZone": "Asia/Seoul",
        "accountType": "atlassian"
      },
      "updateAuthor": {
        "self": "https://deleokorea.atlassian.net/rest/api/3/user?accountId=63719fe93c26ca7fa0d02e91",
        "accountId": "63719fe93c26ca7fa0d02e91",
        "avatarUrls": {
          "48x48": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "24x24": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "16x16": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png",
          "32x32": "https://secure.gravatar.com/avatar/c0866452aefd992a4f114564465776fb?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Fdefault-avatar-0.png"
        },
        "displayName": "윤문성",
        "active": true,
        "timeZone": "Asia/Seoul",
        "accountType": "atlassian"
      },
      "comment": {
        "type": "doc",
        "version": 1,
        "content": [
          {
            "type": "paragraph",
            "content": [{ "type": "text", "text": "문의 cs 처리" }]
          }
        ]
      },
      "created": "2025-09-05T17:01:46.633+0900",
      "updated": "2025-09-05T17:01:46.633+0900",
      "started": "2025-09-05T16:01:36.899+0900",
      "timeSpent": "1h",
      "timeSpentSeconds": 3600,
      "id": "17684",
      "issueId": "38527"
    }
  ]
}

```