package kr.hvy.blog.modules.jira.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Jira 설정 프로퍼티
 */
@Data
@Component
@ConfigurationProperties(prefix = "jira")
public class JiraProperties {

    /**
     * Jira 서버 URL
     */
    private String url;

    /**
     * Jira 사용자명 (이메일)
     */
    private String username;

    /**
     * Jira API 토큰
     */
    private String apiToken;

    /**
     * 프로젝트 키
     */
    private String projectKey;
}
