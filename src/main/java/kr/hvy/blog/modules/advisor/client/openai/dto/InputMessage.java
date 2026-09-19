package kr.hvy.blog.modules.advisor.client.openai.dto;

/**
 * 역할 메시지 입력 항목. role 은 developer·user·assistant, content 는 평문.
 */
public record InputMessage(String role, String content) {

  public static final String ROLE_DEVELOPER = "developer";
  public static final String ROLE_USER = "user";
  public static final String ROLE_ASSISTANT = "assistant";
}
