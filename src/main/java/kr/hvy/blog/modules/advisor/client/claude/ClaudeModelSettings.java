package kr.hvy.blog.modules.advisor.client.claude;

/**
 * 역할(judge/assist/chat)별 Claude 모델 설정. {@code ClaudeMessagesChatModel} 인스턴스 하나가 이 값 하나를 갖는다 — OpenAI 쪽이 역할별
 * ChatModel 의 기본 옵션으로 model·max_output_tokens 를 갖는 것과 같은 자리다(AdvisorAiConfig). 값은 전부 yml({@code advisor.claude.*})에서 온다.
 *
 * @param model                     Claude 모델 ID. 코드에 박지 않는다(은퇴한 모델 ID 로 404 가 났던 전례)
 * @param maxTokens                 {@code max_tokens}. thinking 토큰을 포함한다. 비스트리밍 호출이라 16000 이하로 둔다
 * @param effort                    {@code output_config.effort}(low/medium/high/xhigh/max). null·공백이면 미전송 = 서버 기본
 * @param autoCache                 top-level {@code cache_control}(ephemeral) 자동 캐싱. 라운드마다 전체 입력을 재전송하는 채팅 도구 루프에서만 켠다
 * @param forceFinishAfterToolCalls 프롬프트에 쌓인 도구 결과 수가 이 값 이상이면 {@code tool_choice: none} 으로 답을 강제한다(0 이면 끔)
 */
public record ClaudeModelSettings(String model, int maxTokens, String effort, boolean autoCache, int forceFinishAfterToolCalls) {

  public ClaudeModelSettings {
    if (maxTokens <= 0) {
      throw new IllegalArgumentException("Claude max_tokens 는 양수여야 합니다: " + maxTokens);
    }
    if (forceFinishAfterToolCalls < 0) {
      throw new IllegalArgumentException("forceFinishAfterToolCalls 는 0 이상이어야 합니다: " + forceFinishAfterToolCalls);
    }
  }
}
