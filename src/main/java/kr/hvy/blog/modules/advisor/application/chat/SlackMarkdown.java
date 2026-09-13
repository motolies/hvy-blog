package kr.hvy.blog.modules.advisor.application.chat;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;

import com.slack.api.model.block.LayoutBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import kr.hvy.blog.modules.advisor.application.slack.DailyAdviceMessage;

/**
 * 모델이 쓴 일반 마크다운을 Slack mrkdwn 답글 블록으로 바꾼다.
 * <ul>
 *   <li>변환: {@code **굵게**}→{@code *굵게*}, {@code *기울임*}/{@code _기울임_}→{@code _기울임_}, {@code # 제목}→{@code *제목*},
 *       {@code [t](url)}→{@code <url|t>}, {@code - }/{@code * } 불릿→{@code • }, {@code ~~취소~~}→{@code ~취소~}.
 *       삼중 backtick 코드 블록 안은 손대지 않는다 — 문자열을 펜스 기준으로 토막 내 밖(짝수 토막)만 변환한다.</li>
 *   <li>분할: section 텍스트 상한 3,000자 아래인 {@value #SECTION_LIMIT}자에서 <b>줄 단위</b>로 끊는다. 코드 블록 안에서 끊어야 하면 펜스를 닫고
 *       다음 section 을 펜스로 다시 열어 고정폭 표가 두 동강 나지 않게 한다.</li>
 *   <li>꼬리: context 블록 2개(메타 줄 · 고정 면책 {@link DailyAdviceMessage#DISCLAIMER}) — 면책은 프롬프트가 아니라 여기서 붙인다.</li>
 * </ul>
 */
public final class SlackMarkdown {

  static final int SECTION_LIMIT = 2_900;
  static final int FALLBACK_LIMIT = 80;
  static final String TRUNCATED = "…(생략)";
  private static final String FENCE = "```";
  private static final Pattern HEADER = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*#*\\s*$");
  private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
  private static final Pattern ITALIC_STAR = Pattern.compile("(?<![*\\w])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![*\\w])");
  private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
  private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");
  private static final Pattern BULLET = Pattern.compile("(?m)^(\\s*)[-*]\\s+");
  /** 굵게 변환 결과가 기울임 규칙에 다시 걸리지 않도록 잠시 바꿔 두는 표식(사용자 텍스트에 나올 수 없는 제어문자) */
  private static final char BOLD_MARK = (char) 1;

  private SlackMarkdown() {
  }

  /**
   * 마크다운 → mrkdwn. 코드 블록 안은 그대로 두고, 닫히지 않은 펜스는 닫아 준다.
   */
  public static String toMrkdwn(String markdown) {
    if (markdown == null || markdown.isEmpty()) {
      return "";
    }
    String text = markdown.replace("\r\n", "\n");
    String[] parts = text.split(FENCE, -1);
    StringBuilder out = new StringBuilder(text.length() + 16);
    for (int i = 0; i < parts.length; i++) {
      if (i % 2 == 1) {
        out.append(FENCE).append(parts[i]).append(FENCE);
      } else {
        out.append(convertOutsideFence(parts[i]));
      }
    }
    return out.toString();
  }

  /**
   * 코드 블록 밖 텍스트 변환. 순서: 헤더(표식) → 굵게(표식) → 기울임 → 표식을 * 로 복원 → 취소선 → 링크 → 불릿. 헤더·굵게가 만든 * 가 기울임 규칙에 다시 걸리지 않게 표식을 거친다.
   */
  static String convertOutsideFence(String s) {
    String r = HEADER.matcher(s).replaceAll(m -> BOLD_MARK + escapeReplacement(m.group(1)) + BOLD_MARK);
    r = BOLD.matcher(r).replaceAll(m -> BOLD_MARK + escapeReplacement(m.group(1)) + BOLD_MARK);
    r = ITALIC_STAR.matcher(r).replaceAll(m -> "_" + escapeReplacement(m.group(1)) + "_");
    r = r.replace(BOLD_MARK, '*');
    r = STRIKE.matcher(r).replaceAll("~$1~");
    r = LINK.matcher(r).replaceAll("<$2|$1>");
    r = BULLET.matcher(r).replaceAll("$1• ");
    return r;
  }

  private static String escapeReplacement(String s) {
    return s.replace("\\", "\\\\").replace("$", "\\$");
  }

  /**
   * 줄 단위로 상한 아래에서 끊는다. 코드 블록 안이면 펜스를 닫고 다음 조각을 펜스로 다시 연다. 한 줄이 상한보다 길면 그 줄만 문자 단위로 자른다.
   */
  static List<String> splitSections(String text, int limit) {
    List<String> out = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return out;
    }
    StringBuilder cur = new StringBuilder();
    boolean inFence = false;
    for (String rawLine : text.split("\n", -1)) {
      boolean toggles = rawLine.trim().startsWith(FENCE);
      for (String line : chunk(rawLine, limit - FENCE.length() - 2)) {
        int extra = line.length() + (cur.isEmpty() ? 0 : 1);
        if (!cur.isEmpty() && cur.length() + extra > limit) {
          if (inFence) {
            cur.append('\n').append(FENCE);
            out.add(cur.toString());
            cur = new StringBuilder(FENCE);
          } else {
            out.add(cur.toString());
            cur = new StringBuilder();
          }
        }
        if (!cur.isEmpty()) {
          cur.append('\n');
        }
        cur.append(line);
      }
      if (toggles) {
        inFence = !inFence;
      }
    }
    if (!cur.isEmpty()) {
      out.add(cur.toString());
    }
    return out;
  }

  private static List<String> chunk(String line, int size) {
    if (line.length() <= size) {
      return List.of(line);
    }
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < line.length(); i += size) {
      parts.add(line.substring(i, Math.min(line.length(), i + size)));
    }
    return parts;
  }

  /**
   * 답글 블록: 본문 section 들 + context(메타) + context(면책). 본문이 maxBlocks−2 를 넘으면 마지막 section 끝에 생략 표식.
   */
  public static List<LayoutBlock> replyBlocks(String answerMarkdown, String contextLine, int maxBlocks) {
    int bodyLimit = Math.max(1, maxBlocks - 2);
    List<String> sections = splitSections(toMrkdwn(answerMarkdown), SECTION_LIMIT);
    List<LayoutBlock> blocks = new ArrayList<>();
    if (sections.isEmpty()) {
      sections = List.of("(빈 답변)");
    }
    for (int i = 0; i < sections.size() && i < bodyLimit; i++) {
      String body = sections.get(i);
      if (i == bodyLimit - 1 && sections.size() > bodyLimit) {
        body = body + "\n" + TRUNCATED;
      }
      String text = body;
      blocks.add(section(s -> s.text(markdownText(text))));
    }
    if (contextLine != null && !contextLine.isBlank()) {
      blocks.add(context(List.of(markdownText(contextLine))));
    }
    blocks.add(context(List.of(markdownText(DailyAdviceMessage.DISCLAIMER))));
    return blocks;
  }

  /**
   * 알림·검색용 요약 한 줄 — 첫 비어 있지 않은 줄을 마크다운 기호 없이 {@value #FALLBACK_LIMIT}자로 자른다.
   */
  public static String fallback(String answer) {
    if (answer == null) {
      return "";
    }
    for (String line : answer.split("\n")) {
      String plain = line.replaceAll("[*_`#>~]", "").trim();
      if (!plain.isEmpty()) {
        return plain.length() <= FALLBACK_LIMIT ? plain : plain.substring(0, FALLBACK_LIMIT - 1) + "…";
      }
    }
    return "";
  }
}
