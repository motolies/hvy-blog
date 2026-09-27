package kr.hvy.blog.modules.advisor.application.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.service.MorningAdviceGuard;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 아침 재판정 메시지: 조치 표는 table 블록(조치·종목명·코드·확신, 방향 열 없음), 전환기 AVOID 행만 "(회피)", 최종 0픽이면 관망 문구, 조치가 없으면 표 없음.
 */
class MorningAdviceMessageTest {

  @Test
  @DisplayName("조치 표: 유지 → 제외 → 추가 순, 확신 오른쪽 정렬, 전환기 저녁의 AVOID 유지 행은 '유지(회피)'")
  void actionTable() {
    MorningAdviceMessage message = message(
        List.of(pick("000001", PickDirection.LONG, 0.80, PickAction.KEEP), pick("000004", PickDirection.AVOID, 0.60, PickAction.KEEP)),
        List.of(pick("000005", PickDirection.LONG, 0.60, PickAction.ADD)),
        List.of(new MorningAdviceGuard.Drop(pick("000002", PickDirection.LONG, 0.70, null), "역풍")));

    List<LayoutBlock> blocks = message.toBlocks();
    List<SlackTableBlock> tables = blocks.stream().filter(b -> b instanceof SlackTableBlock).map(b -> (SlackTableBlock) b).toList();
    assertThat(tables).hasSize(1);
    List<List<String>> rows = tables.getFirst().getRows().stream().map(r -> r.stream().map(SlackTableBlock.Cell::text).toList()).toList();
    assertThat(rows).containsExactly(
        List.of("조치", "종목명", "코드", "확신"),
        List.of("유지", "종목000001", "000001", "0.80"),
        List.of("유지(회피)", "종목000004", "000004", "0.60"),
        List.of("제외", "종목000002", "000002", "0.70"),
        List.of("추가", "종목000005", "000005", "0.60"));
    assertThat(tables.getFirst().getColumnSettings().getLast().align()).isEqualTo("right");
    assertThat(texts(blocks)).noneMatch(t -> t.contains("```")).noneMatch(t -> t.contains(DailyAdviceMessage.ABSTAIN_TEXT));
    assertThat(message.getFallbackText()).isEqualTo("2026-09-24 판단 아침 재판정: 유지 2 · 제외 1 · 추가 1");
  }

  @Test
  @DisplayName("전부 DROP 이면 헤딩에 관망 문구(표에는 제외 행만), 조치가 하나도 없으면(저녁 관망·추가 없음) 표를 만들지 않는다")
  void abstainHeadingAndNoTable() {
    MorningAdviceMessage allDrop = message(List.of(), List.of(),
        List.of(new MorningAdviceGuard.Drop(pick("000001", PickDirection.LONG, 0.80, null), "급락")));
    assertThat(texts(allDrop.toBlocks())).anyMatch(t -> t.equals("*유지 0 · 제외 1 · 추가 0*\n*" + DailyAdviceMessage.ABSTAIN_TEXT + "*"));
    assertThat(allDrop.toBlocks()).filteredOn(b -> b instanceof SlackTableBlock).hasSize(1);
    assertThat(allDrop.getFallbackText()).endsWith(" — " + DailyAdviceMessage.ABSTAIN_TEXT);

    MorningAdviceMessage nothing = message(List.of(), List.of(), List.of());
    assertThat(nothing.toBlocks()).noneMatch(b -> b instanceof SlackTableBlock);
    assertThat(texts(nothing.toBlocks())).anyMatch(t -> t.contains(DailyAdviceMessage.ABSTAIN_TEXT));
  }

  private static MorningAdviceMessage message(List<PickRow> kept, List<PickRow> added, List<MorningAdviceGuard.Drop> drops) {
    Map<String, String> names = Map.of("000001", "종목000001", "000002", "종목000002", "000004", "종목000004", "000005", "종목000005");
    return MorningAdviceMessage.builder().baseDate(LocalDate.of(2026, 9, 24)).entryDate(LocalDate.of(2026, 9, 25)).exitDate(LocalDate.of(2026, 10, 1))
        .overnightLines(List.of("미국 2026-09-24 마감")).kept(kept).added(added).drops(drops).names(names).summary("요약").runId(1L).adviceId(2L)
        .parentAdviceId(3L).build();
  }

  private static PickRow pick(String ticker, PickDirection direction, double conviction, PickAction action) {
    return PickRow.builder().ticker(ticker).pickRank(1).direction(direction).conviction(conviction).action(action).actionReason("사유").build();
  }

  private static List<String> texts(List<LayoutBlock> blocks) {
    return blocks.stream().filter(b -> b instanceof SectionBlock s && s.getText() instanceof MarkdownTextObject)
        .map(b -> ((MarkdownTextObject) ((SectionBlock) b).getText()).getText()).toList();
  }
}
