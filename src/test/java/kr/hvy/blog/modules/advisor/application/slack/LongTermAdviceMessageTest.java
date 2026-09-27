package kr.hvy.blog.modules.advisor.application.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장기 규칙 추천 메시지: 종목 표는 table 블록(순위·종목명·코드·장기 점수), 코드 블록은 없다.
 */
class LongTermAdviceMessageTest {

  @Test
  @DisplayName("종목 표: 순위·종목명·코드·장기 점수(부호 포함, 오른쪽 정렬) — 후보가 없으면 종목명 자리에 코드, 점수 '-'")
  void pickTable() {
    AdviceHeader header = AdviceHeader.builder().adviceId(1L).runId(2L).baseDate(LocalDate.of(2026, 9, 26)).adviceKind(AdviceKind.H60)
        .variant(AdviceVariant.LIVE).horizonDays(60).promptVersion("longterm-v1").model("m").build();
    List<PickRow> picks = List.of(
        PickRow.builder().ticker("005930").pickRank(1).direction(PickDirection.LONG).conviction(0.55).thesis("t").build(),
        PickRow.builder().ticker("000660").pickRank(2).direction(PickDirection.LONG).conviction(0.55).thesis("t").build());
    LongTermAdviceMessage message = LongTermAdviceMessage.builder().header(header).picks(picks)
        .candidates(Map.of("005930", CandidateRow.builder().ticker("005930").stockName("삼성전자").quantScore(0.4213).build()))
        .weights(Map.of("MOM_12_1", 0.3)).verdictLabel("판정 불가").runId(2L).build();

    List<SlackTableBlock> tables = message.toBlocks().stream().filter(b -> b instanceof SlackTableBlock).map(b -> (SlackTableBlock) b).toList();
    assertThat(tables).hasSize(1);
    List<List<String>> rows = tables.getFirst().getRows().stream().map(r -> r.stream().map(SlackTableBlock.Cell::text).toList()).toList();
    assertThat(rows).containsExactly(List.of("순위", "종목명", "코드", "장기 점수"), List.of("1", "삼성전자", "005930", "+0.42"),
        List.of("2", "000660", "000660", "-"));
    assertThat(tables.getFirst().getColumnSettings().getLast().align()).isEqualTo("right");
    assertThat(message.toBlocks().toString()).doesNotContain("```");
  }
}
