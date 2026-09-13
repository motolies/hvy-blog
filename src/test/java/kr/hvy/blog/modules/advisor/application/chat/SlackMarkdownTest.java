package kr.hvy.blog.modules.advisor.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import java.util.List;
import kr.hvy.blog.modules.advisor.application.slack.DailyAdviceMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlackMarkdownTest {

  @Test
  @DisplayName("굵게·기울임·헤더·링크·불릿·취소선을 mrkdwn 으로 바꾼다")
  void convertsInlineSyntax() {
    String md = "# 제목\n**굵게** 와 *기울임* 그리고 _밑줄기울임_\n- 항목 하나\n* 항목 둘\n[문서](https://example.com/a) ~~취소~~";
    String out = SlackMarkdown.toMrkdwn(md);
    assertThat(out).isEqualTo("*제목*\n*굵게* 와 _기울임_ 그리고 _밑줄기울임_\n• 항목 하나\n• 항목 둘\n<https://example.com/a|문서> ~취소~");
  }

  @Test
  @DisplayName("굵게 변환 결과가 기울임 규칙에 다시 걸리지 않는다")
  void boldDoesNotBecomeItalic() {
    assertThat(SlackMarkdown.toMrkdwn("**A** and **B**")).isEqualTo("*A* and *B*");
    assertThat(SlackMarkdown.toMrkdwn("2 * 3 = 6")).isEqualTo("2 * 3 = 6");
  }

  @Test
  @DisplayName("코드 블록 안은 손대지 않고, 닫히지 않은 펜스는 닫는다")
  void codeBlocksAreUntouched() {
    String md = "앞 **굵게**\n```\n순위 **코드** - 이름\n# 주석\n```\n뒤 - 줄 중간 하이픈\n- 불릿";
    assertThat(SlackMarkdown.toMrkdwn(md)).isEqualTo("앞 *굵게*\n```\n순위 **코드** - 이름\n# 주석\n```\n뒤 - 줄 중간 하이픈\n• 불릿");
    assertThat(SlackMarkdown.toMrkdwn("```\n열린 채")).isEqualTo("```\n열린 채```");
  }

  @Test
  @DisplayName("줄 단위로 상한 아래에서 끊고 코드 블록은 펜스를 닫고 다시 연다")
  void splitsByLineAndKeepsFences() {
    String longLine = "x".repeat(40);
    StringBuilder md = new StringBuilder("```\n");
    for (int i = 0; i < 10; i++) {
      md.append(longLine).append('\n');
    }
    md.append("```");
    List<String> parts = SlackMarkdown.splitSections(md.toString(), 120);
    assertThat(parts).hasSizeGreaterThan(1);
    for (String part : parts) {
      assertThat(part.length()).isLessThanOrEqualTo(120);
      assertThat(part).startsWith("```").endsWith("```");
    }
    assertThat(String.join("", parts).replace("```", "").replace("\n", "")).isEqualTo(longLine.repeat(10));
  }

  @Test
  @DisplayName("상한보다 긴 한 줄은 그 줄만 문자 단위로 자른다")
  void veryLongSingleLineIsChunked() {
    List<String> parts = SlackMarkdown.splitSections("y".repeat(250), 100);
    assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(100));
    assertThat(String.join("", parts)).isEqualTo("y".repeat(250));
  }

  @Test
  @DisplayName("답글 블록은 본문 section + 메타 context + 고정 면책 context 이며 상한을 넘으면 생략 표식을 붙인다")
  void replyBlocksLayout() {
    List<LayoutBlock> blocks = SlackMarkdown.replyBlocks("**답변** 본문", "chat=1 · model=m · tools: 없음 · in 1 / out 2 tok", 20);
    assertThat(blocks).hasSize(3);
    assertThat(((MarkdownTextObject) ((SectionBlock) blocks.get(0)).getText()).getText()).isEqualTo("*답변* 본문");
    assertThat(((MarkdownTextObject) ((ContextBlock) blocks.get(2)).getElements().getFirst()).getText()).isEqualTo(DailyAdviceMessage.DISCLAIMER);

    String huge = ("z".repeat(2_000) + "\n").repeat(10);
    List<LayoutBlock> capped = SlackMarkdown.replyBlocks(huge, "meta", 4);
    assertThat(capped).hasSize(4);
    assertThat(((MarkdownTextObject) ((SectionBlock) capped.get(1)).getText()).getText()).endsWith(SlackMarkdown.TRUNCATED);
  }

  @Test
  @DisplayName("fallback 은 첫 줄을 기호 없이 80자로 자른다")
  void fallbackLine() {
    assertThat(SlackMarkdown.fallback("\n**삼성전자**는 _최근_ 20일 +3.1%")).isEqualTo("삼성전자는 최근 20일 +3.1%");
    assertThat(SlackMarkdown.fallback("가".repeat(100))).hasSize(80).endsWith("…");
    assertThat(SlackMarkdown.fallback(null)).isEmpty();
  }
}
