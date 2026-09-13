package kr.hvy.blog.modules.advisor.application.chat;

import com.slack.api.model.Message;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.ContextBlockElement;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.TextObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 스레드 히스토리 → Spring AI 대화 메시지. Slack 스레드가 곧 대화 기억이다(별도 저장소 없음).
 * <p>
 * 봇의 일일 판단 메시지는 {@code text} 가 알림용 요약("2026-09-12 시장 판단: RISK_ON, 종목 7개")뿐이라 <b>Block Kit 블록을 평문으로 펼쳐야</b>
 * 국면·픽 표·근거가 맥락으로 들어온다. 봇 메시지는 {@link AssistantMessage}, 사람 메시지는 {@link UserMessage}. 질문 자신(ts 일치)은 뺀다.
 * 총 문자 수가 상한을 넘으면 오래된 것부터 버리되 루트(첫 메시지)는 맥락의 핵심이라 마지막까지 남긴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class SlackThreadHistory {

  /** 봇 판단 헤더 "📈 2026-09-12 시장 판단 (5거래일)" 에서 기준일을 뽑는다 */
  static final Pattern ADVICE_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}) 시장 판단");

  /**
   * 변환 결과 — 메시지 목록과, 루트가 봇 판단이면 그 기준일 힌트.
   *
   * @param messages       프롬프트에 넣을 이전 대화(오래된 것부터)
   * @param adviceBaseDate 루트 판단 메시지의 기준일(yyyy-MM-dd), 없으면 null
   * @param dropped        상한 때문에 버린 메시지 수
   */
  public record History(List<org.springframework.ai.chat.messages.Message> messages, String adviceBaseDate, int dropped) {

    public static History empty() {
      return new History(List.of(), null, 0);
    }
  }

  private final SlackChatGateway slack;
  private final AdvisorChatProperties properties;

  public SlackThreadHistory(SlackChatGateway slack, AdvisorChatProperties properties) {
    this.slack = slack;
    this.properties = properties;
  }

  /**
   * 질문이 스레드 댓글이면 스레드를 읽어 변환한다. 새 글이면 빈 히스토리. 조회 실패는 빈 히스토리로 폴백한다(맥락 없이라도 답하는 편이 낫다).
   */
  public History load(IncomingQuestion question, String botUserId) {
    if (!question.isThreadReply()) {
      return History.empty();
    }
    List<Message> replies;
    try {
      replies = slack.threadReplies(question.channelId(), question.threadTs(), properties.getThreadHistoryLimit());
    } catch (Exception e) {
      log.warn("스레드 히스토리 조회 실패 — 맥락 없이 답한다: thread={}, cause={}", question.threadTs(), e.toString());
      return History.empty();
    }
    return convert(replies, question.ts(), botUserId, properties.getMaxHistoryChars());
  }

  /**
   * 순수 변환(테스트 대상). 질문 자신은 제외, 봇/사람 역할 구분, 상한 초과 시 루트 다음의 오래된 것부터 제거.
   */
  static History convert(List<Message> replies, String questionTs, String botUserId, int maxChars) {
    List<Entry> entries = new ArrayList<>();
    String adviceBaseDate = null;
    for (Message m : replies) {
      if (m.getTs() != null && m.getTs().equals(questionTs)) {
        continue;
      }
      String text = flatten(m);
      if (text.isBlank()) {
        continue;
      }
      boolean bot = (m.getBotId() != null && !m.getBotId().isBlank()) || (botUserId != null && botUserId.equals(m.getUser()));
      if (entries.isEmpty() && bot) {
        Matcher matcher = ADVICE_DATE.matcher(text);
        if (matcher.find()) {
          adviceBaseDate = matcher.group(1);
        }
      }
      entries.add(new Entry(bot, text));
    }
    int total = entries.stream().mapToInt(e -> e.text().length()).sum();
    int dropped = 0;
    // 루트(0번)는 남기고 1번부터 오래된 순으로 버린다
    while (total > maxChars && entries.size() > 1) {
      Entry removed = entries.remove(1);
      total -= removed.text().length();
      dropped++;
    }
    if (total > maxChars && entries.size() == 1) {
      Entry root = entries.getFirst();
      entries.set(0, new Entry(root.bot(), root.text().substring(0, maxChars) + "\n…(생략)"));
    }
    List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
    for (Entry e : entries) {
      messages.add(e.bot() ? new AssistantMessage(e.text()) : new UserMessage(e.text()));
    }
    return new History(messages, adviceBaseDate, dropped);
  }

  /**
   * 블록이 있으면 header/section/context 텍스트를 줄로 이어 붙이고, 없으면 text 를 쓴다.
   */
  static String flatten(Message m) {
    List<LayoutBlock> blocks = m.getBlocks();
    if (blocks == null || blocks.isEmpty()) {
      return m.getText() == null ? "" : m.getText().strip();
    }
    StringBuilder sb = new StringBuilder();
    for (LayoutBlock block : blocks) {
      String line = blockText(block);
      if (line != null && !line.isBlank()) {
        if (!sb.isEmpty()) {
          sb.append('\n');
        }
        sb.append(line.strip());
      }
    }
    if (sb.isEmpty() && m.getText() != null) {
      return m.getText().strip();
    }
    return sb.toString();
  }

  private static String blockText(LayoutBlock block) {
    if (block instanceof HeaderBlock h && h.getText() != null) {
      return h.getText().getText();
    }
    if (block instanceof SectionBlock s) {
      StringBuilder sb = new StringBuilder();
      if (s.getText() != null && s.getText().getText() != null) {
        sb.append(s.getText().getText());
      }
      if (s.getFields() != null) {
        for (TextObject f : s.getFields()) {
          if (f != null && f.getText() != null) {
            sb.append(sb.isEmpty() ? "" : "\n").append(f.getText());
          }
        }
      }
      return sb.toString();
    }
    if (block instanceof ContextBlock c && c.getElements() != null) {
      StringBuilder sb = new StringBuilder();
      for (ContextBlockElement e : c.getElements()) {
        if (e instanceof TextObject t && t.getText() != null) {
          sb.append(sb.isEmpty() ? "" : " ").append(t.getText());
        }
      }
      return sb.toString();
    }
    return null;
  }

  private record Entry(boolean bot, String text) {
  }
}
