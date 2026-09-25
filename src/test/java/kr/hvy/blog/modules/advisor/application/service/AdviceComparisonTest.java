package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdviceComparisonTest {

  static PickRow pick(String ticker, int rank, PickDirection dir, double conv) {
    return PickRow.builder().ticker(ticker).pickRank(rank).direction(dir).conviction(conv).thesis("근거 " + ticker).build();
  }

  @Test
  @DisplayName("조치 미기록(M4 이전): 코드 집합 차이로 KEEP·ADD·DROP 을 계산하고 declared=false, KEEP 은 방향·확신 변화를 사유로 적는다")
  void derivedFromSetDifference() {
    List<PickRow> evening = List.of(pick("A", 1, PickDirection.LONG, 0.70), pick("B", 2, PickDirection.LONG, 0.60), pick("C", 3, PickDirection.AVOID, 0.55));
    List<PickRow> morning = List.of(pick("B", 1, PickDirection.LONG, 0.70), pick("D", 2, PickDirection.LONG, 0.60), pick("A", 3, PickDirection.LONG, 0.70));

    List<AdviceComparison.Change> changes = AdviceComparison.compare(evening, morning);

    assertThat(changes).extracting(AdviceComparison.Change::ticker).containsExactly("B", "D", "A", "C");
    assertThat(changes).extracting(AdviceComparison.Change::action).containsExactly(PickAction.KEEP, PickAction.ADD, PickAction.KEEP, PickAction.DROP);
    assertThat(changes).noneMatch(AdviceComparison.Change::declared);
    assertThat(changes.get(0).reason()).contains("확신 0.60→0.70").contains("순위 2→1");
    assertThat(changes.get(1).reason()).isEqualTo("근거 D");
    assertThat(changes.get(3).reason()).contains("사유 미기록");
    assertThat(changes.get(3).morning()).isNull();
  }

  @Test
  @DisplayName("조치가 기록돼 있으면(M4 이후) 선언값을 그대로 쓰고, 아침 픽에 없는 저녁 픽만 DROP 으로 보충한다")
  void declaredActionsWin() {
    List<PickRow> evening = List.of(pick("A", 1, PickDirection.LONG, 0.70), pick("B", 2, PickDirection.LONG, 0.60));
    List<PickRow> morning = List.of(
        pick("A", 1, PickDirection.LONG, 0.70).toBuilder().action(PickAction.DROP).actionReason("미국 반도체 급락").build(),
        pick("E", 2, PickDirection.LONG, 0.60).toBuilder().action(PickAction.ADD).actionReason("저녁 후보 중 갭 수혜").build());

    List<AdviceComparison.Change> changes = AdviceComparison.compare(evening, morning);

    assertThat(changes).extracting(AdviceComparison.Change::ticker).containsExactly("A", "E", "B");
    assertThat(changes.get(0).action()).isEqualTo(PickAction.DROP);
    assertThat(changes.get(0).reason()).isEqualTo("미국 반도체 급락");
    assertThat(changes.get(0).declared()).isTrue();
    assertThat(changes.get(2).action()).isEqualTo(PickAction.DROP);
    assertThat(changes.get(2).declared()).isFalse();
  }
}
