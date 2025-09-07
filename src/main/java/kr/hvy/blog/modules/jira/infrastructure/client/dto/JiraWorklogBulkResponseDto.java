package kr.hvy.blog.modules.jira.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Jira 워크로그 배치 조회 응답 DTO
 * /rest/api/3/worklog/list API용
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraWorklogBulkResponseDto {

    /**
     * 이슈 ID를 키로, 해당 이슈의 워크로그 리스트를 값으로 하는 맵
     * 예: {"10001": [...worklogs...], "10002": [...worklogs...]}
     */
    private Map<Long, List<JiraWorklogResponse>> values;
}
