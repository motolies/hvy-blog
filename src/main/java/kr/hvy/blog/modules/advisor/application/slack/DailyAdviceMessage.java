package kr.hvy.blog.modules.advisor.application.slack;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;

import com.slack.api.model.Attachment;
import com.slack.api.model.block.LayoutBlock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.InvalidationType;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.domain.model.TrendOutlook;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.message.SlackColor;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import lombok.Builder;

/**
 * 일일 판단 Block Kit 메시지 (#hvy-advisor, 멘션 없음).
 * <pre>
 * 헤더 → 추세(규칙) → 국면(5일) → 추세 전망(LLM) → 데이터 기준·적용 구간 → 주도 섹터 → 종목 표(고정폭: 순위 코드 종목명 방향 확신 점수)
 * → 픽별 근거·리스크 한 줄 → 최근 채점 → 총평 → run 메타 → 면책
 * </pre>
 * 종목 표는 고정폭 코드 블록(Block Kit fields 는 2열 고정이라 표가 깨진다)이며 폭은 {@link SlackWidth} 로 한글 2칸을 계산한다. 모바일 코드 블록이
 * 40칸 남짓에서 접히므로 표는 39칸 안에 두고 근거·리스크는 표 밖 인용 줄로 내렸다(advice-v2). 하단 면책 문구는 고정이다.
 */
@Builder
public class DailyAdviceMessage implements SlackMessage {

  public static final String DISCLAIMER = "⚠️ 투자 자문이 아닙니다. 개인 실험(정량 스크리닝 + LLM 판단) 결과이며 어떤 손실도 책임지지 않습니다.";
  static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MM-dd");
  static final int THESIS_CELLS = 60;
  static final int RISK_CELLS = 40;
  /** 미국 데이터가 이 영업일 수 이상 뒤처지면 경고 표시 */
  static final int GLOBAL_STALE_DAYS = 2;

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
    String trend = trendText();
    if (trend != null) {
      blocks.add(section(s -> s.text(markdownText(trend))));
    }
    blocks.add(section(s -> s.text(markdownText(regimeText()))));
    String outlook = outlookText();
    if (outlook != null) {
      blocks.add(section(s -> s.text(markdownText(outlook))));
    }
    blocks.add(section(s -> s.text(markdownText(dataText()))));
    blocks.add(divider());
    blocks.add(section(s -> s.text(markdownText("*주도 섹터*  " + sectorText()))));
    blocks.add(divider());
    blocks.add(section(s -> s.text(markdownText(String.format("*종목 (%d)*%n```%s```", picks.size(), pickTable())))));
    String notes = pickNotes();
    if (!notes.isBlank()) {
      blocks.add(section(s -> s.text(markdownText(notes))));
    }
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

  /**
   * 규칙 추세 한 줄: "*추세*  KOSPI 강세 32일째 (+4) · KOSDAQ 보합 7일째 (+1)". 상세가 없으면 라벨만, 둘 다 없으면 null(줄 생략).
   */
  String trendText() {
    List<String> parts = new ArrayList<>();
    for (String code : List.of("0001", "1001")) {
      MarketTrend t = header.trends() == null ? null : header.trends().stream().filter(x -> code.equals(x.indexCode())).findFirst().orElse(null);
      MarketTrendCode label = t != null ? t.code() : header.trendOf(code);
      if (label == null) {
        continue;
      }
      if (t != null) {
        parts.add(String.format("%s %s %d일째 (%+d)", indexName(code), label.getDesc(), t.days(), t.score()));
      } else {
        parts.add(indexName(code) + " " + label.getDesc());
      }
    }
    return parts.isEmpty() ? null : "*추세*  " + String.join(" · ", parts);
  }

  String regimeText() {
    return String.format("*국면* %s · KOSPI %s / KOSDAQ %s · 확신 %.2f%n%s",
        header.regimeCode() == null ? "-" : header.regimeCode().getDesc(), arrow(header.kospiDir()), arrow(header.kosdaqDir()),
        header.pUp() == null ? 0.0 : header.pUp(), header.regimeRationale() == null ? "" : header.regimeRationale());
  }

  /**
   * LLM 추세 전망: "*추세 전망*  KOSPI 20거래일 넘게 유지 · 확신 0.70 · 무효화 20일선(2,612) 하향 이탈". 전망이 없으면 null.
   */
  String outlookText() {
    if (header.outlooks() == null || header.outlooks().isEmpty()) {
      return null;
    }
    List<String> lines = new ArrayList<>();
    for (TrendOutlook o : header.outlooks()) {
      MarketTrend t = header.trends() == null ? null : header.trends().stream().filter(x -> o.indexCode().equals(x.indexCode())).findFirst().orElse(null);
      lines.add(String.format("%s %s · 확신 %.2f · %s", indexName(o.indexCode()), o.persist() == null ? "-" : o.persist().getDesc(), o.confidence(),
          invalidationText(o.invalidation(), t)));
    }
    return "*추세 전망*  " + String.join("\n", lines);
  }

  /**
   * 데이터 기준일과 적용 구간 두 줄. 미국 데이터가 2영업일 이상 뒤처지면 ⚠️.
   */
  String dataText() {
    Map<String, Object> asOf = header.dataAsOf() == null ? Map.of() : header.dataAsOf();
    StringBuilder sb = new StringBuilder("*데이터 기준*  ");
    sb.append("국내 ").append(mmdd(asOf.get("domestic"), header.baseDate())).append(" 종가");
    if (asOf.get("flow") != null) {
      sb.append(" · 수급 ").append(mmdd(asOf.get("flow"), null)).append(Boolean.TRUE.equals(asOf.get("flowProvisional")) ? "(잠정)" : "");
    }
    if (asOf.get("global") != null) {
      sb.append(" · 미국 ").append(mmdd(asOf.get("global"), null)).append(" 종가");
      Object age = asOf.get("globalAgeTradingDays");
      if (age instanceof Number n && n.intValue() >= GLOBAL_STALE_DAYS) {
        sb.append(String.format(" ⚠️ %d영업일 지연", n.intValue()));
      }
    }
    if (header.entryDate() != null && header.exitDate() != null) {
      sb.append(String.format("%n*적용 구간*  %s 시가 진입 → %s 종가 청산 · %d거래일 (예정)", dateWithDow(header.entryDate()), dateWithDow(header.exitDate()),
          header.horizonDays()));
    }
    return sb.toString();
  }

  String sectorText() {
    if (header.leadingSectors() == null || header.leadingSectors().isEmpty()) {
      return "-";
    }
    return header.leadingSectors().stream().map(s -> s.name() == null ? s.code() : s.name() + "(" + s.code() + ")")
        .collect(Collectors.joining(" · "));
  }

  /** 종목명 열 폭(칸). 한글 6자까지 그대로, 그 이상은 … 절단 */
  static final int NAME_CELLS = 13;

  /**
   * 고정폭 표: 순위(4) 코드(6) 종목명(13) 방향(4) 확신(4) 점수(4) + 구분 공백 5 = 40칸. 근거는 표 밖 {@link #pickNotes()}.
   */
  String pickTable() {
    StringBuilder sb = new StringBuilder();
    sb.append(SlackWidth.padRight("순위", 4)).append(' ').append(SlackWidth.padRight("코드", 6)).append(' ').append(SlackWidth.padRight("종목명", NAME_CELLS))
        .append(' ').append(SlackWidth.padRight("방향", 4)).append(' ').append(SlackWidth.padRight("확신", 4)).append(' ').append("점수").append('\n');
    for (PickRow p : picks) {
      CandidateRow c = candidates.get(p.ticker());
      String name = c == null || c.stockName() == null ? p.ticker() : c.stockName();
      String score = c == null ? "-" : String.format("%.2f", c.quantScore());
      sb.append(SlackWidth.padRight(String.format("%2d", p.pickRank()), 4)).append(' ')
          .append(SlackWidth.padRight(p.ticker(), 6)).append(' ')
          .append(SlackWidth.padRight(name, NAME_CELLS)).append(' ')
          .append(SlackWidth.padRight(p.direction() == PickDirection.AVOID ? "회피" : "매수", 4)).append(' ')
          .append(String.format("%.2f", p.conviction())).append(' ')
          .append(score).append('\n');
    }
    return sb.toString().stripTrailing();
  }

  /**
   * 픽별 근거·리스크 한 줄: "> *1 삼성전자(005930)* 근거… ⚠ 리스크…".
   */
  String pickNotes() {
    StringBuilder sb = new StringBuilder();
    for (PickRow p : picks) {
      CandidateRow c = candidates.get(p.ticker());
      String name = c == null || c.stockName() == null ? p.ticker() : c.stockName();
      sb.append("> *").append(p.pickRank()).append(' ').append(name).append('(').append(p.ticker()).append(")* ")
          .append(SlackWidth.abbreviate(p.thesis() == null ? "" : p.thesis(), THESIS_CELLS));
      if (p.riskNote() != null && !p.riskNote().isBlank()) {
        sb.append(" ⚠ ").append(SlackWidth.abbreviate(p.riskNote(), RISK_CELLS));
      }
      sb.append('\n');
    }
    return sb.toString().stripTrailing();
  }

  static String invalidationText(InvalidationType type, MarketTrend trend) {
    if (type == null || type == InvalidationType.NONE) {
      return "무효화 없음";
    }
    Double level = switch (type) {
      case BELOW_MA20, ABOVE_MA20 -> trend == null ? null : trend.ma20();
      case BELOW_MA60, ABOVE_MA60 -> trend == null ? null : trend.ma60();
      default -> null;
    };
    String line = type.getDesc(); // 예: 20일선 하향 이탈
    if (level == null) {
      return "무효화 " + line;
    }
    int idx = line.indexOf(' ');
    return "무효화 " + (idx > 0 ? line.substring(0, idx) + String.format("(%,.0f)", level) + line.substring(idx) : line);
  }

  static String indexName(String code) {
    return switch (code) {
      case "0001" -> "KOSPI";
      case "1001" -> "KOSDAQ";
      case "2001" -> "KOSPI200";
      default -> code;
    };
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

  /** ISO 날짜 문자열(또는 폴백 날짜)을 MM-dd 로 */
  static String mmdd(Object iso, LocalDate fallback) {
    LocalDate d = iso == null ? fallback : LocalDate.parse(String.valueOf(iso));
    return d == null ? "-" : d.format(MMDD);
  }

  static String dateWithDow(LocalDate date) {
    return date.format(MMDD) + "(" + dow(date.getDayOfWeek()) + ")";
  }

  static String dow(DayOfWeek day) {
    return switch (day) {
      case MONDAY -> "월";
      case TUESDAY -> "화";
      case WEDNESDAY -> "수";
      case THURSDAY -> "목";
      case FRIDAY -> "금";
      case SATURDAY -> "토";
      case SUNDAY -> "일";
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
