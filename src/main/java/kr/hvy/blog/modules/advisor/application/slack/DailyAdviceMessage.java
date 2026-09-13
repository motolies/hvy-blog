package kr.hvy.blog.modules.advisor.application.slack;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;

import com.slack.api.model.Attachment;
import com.slack.api.model.block.LayoutBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.message.SlackColor;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import lombok.Builder;

/**
 * 일일 판단 Block Kit 메시지 (#hvy-advisor, 멘션 없음). 종목 표는 고정폭 코드 블록으로 넣는다(Block Kit fields 는 2열 고정이라 표가 깨진다).
 * 하단 면책 문구는 고정이다 — 투자 자문이 아닌 개인 실험임을 매 메시지에 남긴다.
 */
@Builder
public class DailyAdviceMessage implements SlackMessage {

  public static final String DISCLAIMER = "⚠️ 투자 자문이 아닙니다. 개인 실험(정량 스크리닝 + LLM 판단) 결과이며 어떤 손실도 책임지지 않습니다.";

  private final AdviceHeader header;
  private final List<PickRow> picks;
  private final Map<String, CandidateRow> candidates;
  /** 전일 채점 요약 줄들 (없으면 빈 목록) */
  private final List<String> scoreboardLines;
  private final long runId;
  private final int promptTokens;
  private final int completionTokens;
  private final String costText;
  private final boolean degraded;

  @Override
  public String getChannel() {
    return SlackChannel.ADVISOR.getChannel();
  }

  @Override
  public boolean isNotify() {
    return false;
  }

  @Override
  public String getFallbackText() {
    return String.format("%s 시장 판단: %s, 종목 %d개", header.baseDate(), header.regimeCode(), picks.size());
  }

  @Override
  public List<LayoutBlock> toBlocks() {
    List<LayoutBlock> blocks = new ArrayList<>();
    blocks.add(header(h -> h.text(plainText(String.format("📈 %s 시장 판단 (%d거래일)", header.baseDate(), header.horizonDays())))));
    blocks.add(section(s -> s.text(markdownText(regimeText()))));
    blocks.add(divider());
    blocks.add(section(s -> s.text(markdownText("*주도 섹터*  " + sectorText()))));
    blocks.add(divider());
    blocks.add(section(s -> s.text(markdownText(String.format("*종목 (%d)*%n```%s```", picks.size(), pickTable())))));
    if (scoreboardLines != null && !scoreboardLines.isEmpty()) {
      blocks.add(divider());
      blocks.add(section(s -> s.text(markdownText("*최근 채점*  " + String.join("\n", scoreboardLines)))));
    }
    if (header.summary() != null && !header.summary().isBlank()) {
      blocks.add(section(s -> s.text(markdownText("_" + header.summary() + "_"))));
    }
    blocks.add(context(List.of(markdownText(String.format("run=%d · model=%s · prompt=%s · in %,d / out %,d tok%s%s",
        runId, header.model(), header.promptVersion(), promptTokens, completionTokens,
        costText == null ? "" : " · " + costText, degraded ? " · ⚠️ 수집 결손일(학습 제외)" : "")))));
    blocks.add(context(List.of(markdownText(DISCLAIMER))));
    return blocks;
  }

  @Override
  public List<Attachment> toAttachments() {
    return Collections.singletonList(Attachment.builder().color(SlackColor.NOTICE).fallback(getFallbackText()).build());
  }

  String regimeText() {
    return String.format("*국면* %s · KOSPI %s / KOSDAQ %s · 확신 %.2f%n%s",
        header.regimeCode() == null ? "-" : header.regimeCode().getDesc(), arrow(header.kospiDir()), arrow(header.kosdaqDir()),
        header.pUp() == null ? 0.0 : header.pUp(), header.regimeRationale() == null ? "" : header.regimeRationale());
  }

  String sectorText() {
    if (header.leadingSectors() == null || header.leadingSectors().isEmpty()) {
      return "-";
    }
    return header.leadingSectors().stream().map(s -> s.name() == null ? s.code() : s.name() + "(" + s.code() + ")")
        .collect(Collectors.joining(" · "));
  }

  /**
   * 고정폭 표: 순위 종목 섹터 방향 확신 점수 근거.
   */
  String pickTable() {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%-3s %-10s %-8s %-5s %-4s %-5s %s%n", "순위", "종목", "섹터", "방향", "확신", "점수", "근거"));
    for (PickRow p : picks) {
      CandidateRow c = candidates.get(p.ticker());
      String name = c == null || c.stockName() == null ? p.ticker() : c.stockName();
      String sector = c == null || c.sectorName() == null ? "-" : c.sectorName();
      String score = c == null ? "-" : String.format("%.2f", c.quantScore());
      sb.append(String.format("%-3d %-10s %-8s %-5s %.2f %-5s %s%n", p.pickRank(), abbreviate(name, 10), abbreviate(sector, 8),
          p.direction() == PickDirection.AVOID ? "회피" : "매수", p.conviction(), score, abbreviate(p.thesis(), 60)));
    }
    return sb.toString().stripTrailing();
  }

  static String arrow(DirectionCall call) {
    if (call == null) {
      return "-";
    }
    return switch (call) {
      case UP -> "▲";
      case DOWN -> "▼";
      case NEUTRAL -> "■";
    };
  }

  static String abbreviate(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  /** 섹터 콜 목록을 헤더 형태로 (테스트·미리보기용) */
  static String sectorNames(List<SectorCall> sectors) {
    return sectors.stream().map(SectorCall::name).collect(Collectors.joining(", "));
  }
}
