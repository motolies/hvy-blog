package kr.hvy.blog.modules.jira.infrastructure.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Jira 워크로그 배치 조회 요청 DTO
 * /rest/api/3/worklog/list API용
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JiraWorklogBulkRequestDto {

    /**
     * 조회할 이슈 ID 리스트
     */
    private List<Long> ids;
}
