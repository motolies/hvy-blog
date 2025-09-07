package kr.hvy.blog.modules.jira.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Jira 서버 정보 API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraServerInfoDto {
    
    private String version;
    private String baseUrl;
    private String deploymentType; // Cloud, Server 등
}