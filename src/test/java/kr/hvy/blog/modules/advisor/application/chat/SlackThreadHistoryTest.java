package kr.hvy.blog.modules.advisor.application.chat;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.Message;
import com.slack.api.model.block.LayoutBlock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

class SlackThreadHistoryTest {

  static Message bot(String ts, String fallback, List<LayoutBlock> blocks) {
    Message m = new Message();
    m.setTs(ts);
    m.setBotId("B0BOT");
    m.setText(fallback);
    m.setBlocks(blocks);
    return m;
  }

  static Message human(String ts, String text) {
    Message m = new Message();
    m.setTs(ts);
    m.setUser("U0OWNER");
    m.setText(text);
    return m;
  }

  @Test
  @DisplayName("봇 판단 루트는 블록을 평문으로 펼치고 기준일을 뽑으며, 질문 자신은 제외된다")
  void flattensAdviceRootAndExtractsBaseDate() {
    List<LayoutBlock> blocks = List.of(
        header(h -> h.text(plainText("📈 2026-09-12 시장 판단 (5거래일)"))),
        section(s -> s.text(markdownText("*국면* RISK_ON · KOSPI UP"))),
        section(s -> s.text(markdownText("*종목 (2)*\n```\n1 005930 삼성전자 LONG\n```"))),
        context(List.of(markdownText("run=1 · model=m")))
    );
    List<Message> thread = List.of(
        bot("1.0", "2026-09-12 시장 판단: RISK_ON, 종목 2개", blocks),
        human("2.0", "왜 삼성전자야?"),
        bot("3.0", "근거는 …", null),
        human("4.0", "아직 유효해?")
    );
    SlackThreadHistory.History h = SlackThreadHistory.convert(thread, "4.0", null, 12_000);
    assertThat(h.adviceBaseDate()).isEqualTo("2026-09-12");
    assertThat(h.dropped()).isZero();
    assertThat(h.messages()).hasSize(3);
    assertThat(h.messages().get(0)).isInstanceOf(AssistantMessage.class);
    assertThat(h.messages().get(0).getText()).isEqualTo("📈 2026-09-12 시장 판단 (5거래일)\n*국면* RISK_ON · KOSPI UP\n*종목 (2)*\n```\n1 005930 삼성전자 LONG\n```\nrun=1 · model=m");
    assertThat(h.messages().get(1)).isInstanceOf(UserMessage.class);
    assertThat(h.messages().get(1).getText()).isEqualTo("왜 삼성전자야?");
    assertThat(h.messages().get(2).getText()).isEqualTo("근거는 …");
  }

  @Test
  @DisplayName("봇 user id 로도 봇 메시지를 가려낸다(bot_id 없이 user 만 있는 경우)")
  void botUserIdMarksAssistant() {
    Message m = human("1.0", "봇이 쓴 글");
    m.setUser("U0BOT");
    SlackThreadHistory.History h = SlackThreadHistory.convert(List.of(m, human("2.0", "q")), "2.0", "U0BOT", 12_000);
    assertThat(h.messages().getFirst()).isInstanceOf(AssistantMessage.class);
  }

  @Test
  @DisplayName("상한을 넘으면 루트는 남기고 그다음 오래된 것부터 버린다")
  void trimsOldestButKeepsRoot() {
    List<Message> thread = List.of(
        bot("1.0", "루트 " + "r".repeat(100), null),
        human("2.0", "a".repeat(100)),
        human("3.0", "b".repeat(100)),
        human("4.0", "c".repeat(100)),
        human("5.0", "질문")
    );
    SlackThreadHistory.History h = SlackThreadHistory.convert(thread, "5.0", null, 250);
    assertThat(h.dropped()).isEqualTo(2);
    assertThat(h.messages()).extracting(org.springframework.ai.chat.messages.Message::getText)
        .containsExactly("루트 " + "r".repeat(100), "c".repeat(100));
  }

  @Test
  @DisplayName("루트 하나만으로 상한을 넘으면 루트를 잘라 생략 표식을 붙인다")
  void hugeRootIsCut() {
    SlackThreadHistory.History h = SlackThreadHistory.convert(List.of(bot("1.0", "x".repeat(500), null), human("2.0", "q")), "2.0", null, 100);
    assertThat(h.messages()).hasSize(1);
    assertThat(h.messages().getFirst().getText()).hasSize(100 + "\n…(생략)".length()).endsWith("…(생략)");
  }

  @Test
  @DisplayName("본문이 없는 메시지는 건너뛰고 블록 없는 메시지는 text 를 쓴다")
  void skipsEmptyMessages() {
    SlackThreadHistory.History h = SlackThreadHistory.convert(List.of(human("1.0", "  "), human("2.0", "본문"), human("3.0", "q")), "3.0", null, 1_000);
    assertThat(h.messages()).hasSize(1);
    assertThat(h.messages().getFirst().getText()).isEqualTo("본문");
  }
}
