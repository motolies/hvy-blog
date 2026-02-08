package kr.hvy.blog.modules.jira.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Jira 워크로그 API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraWorklogResponse {

    private String self;
    private JiraIssueResponse.JiraUserDto author;

    @JsonProperty("updateAuthor")
    private JiraIssueResponse.JiraUserDto updateAuthor;

    private JiraCommentDto comment;

    private OffsetDateTime created;
    private OffsetDateTime updated;
    private OffsetDateTime started; // ISO 8601 format with timezone

    @JsonProperty("timeSpent")
    private String timeSpent; // "2h 30m" 형태

    @JsonProperty("timeSpentSeconds")
    private Integer timeSpentSeconds;

    private Long id;

    @JsonProperty("issueId")
    private String issueId;

    /**
     * Jira 댓글 구조 DTO
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraCommentDto {
        private String type;
        private Integer version;
        private List<JiraContentDto> content;

        /**
         * 댓글에서 텍스트만 추출하는 메서드
         */
        public String extractText() {
            if (content == null || content.isEmpty()) {
                return "";
            }

            StringBuilder textBuilder = new StringBuilder();
            for (JiraContentDto contentItem : content) {
                if (contentItem.getContent() != null) {
                    for (JiraContentDto innerContent : contentItem.getContent()) {
                        if ("text".equals(innerContent.getType()) && innerContent.getText() != null) {
                            textBuilder.append(innerContent.getText());
                        }
                    }
                }
            }
            return textBuilder.toString();
        }
    }

    /**
     * Jira 컨텐츠 구조 DTO
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraContentDto {
        private String type;
        private List<JiraContentDto> content;
        private String text;
    }
}
