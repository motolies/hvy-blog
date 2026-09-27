package kr.hvy.blog.modules.advisor.application.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.service.MorningAdviceGuard;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.ThemeStrength;
import kr.hvy.blog.modules.advisor.domain.code.VolRegimeCode;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실채널 수동 확인 (SLACK_BOT_TOKEN·ADVISOR_SLACK_TEST_CHANNEL 환경변수가 있을 때만 — 없으면 스킵). table 블록(SlackTableBlock)은 SDK 모델에 없는 자작 블록이라
 * Slack 이 받아들이는지·데스크톱과 모바일에서 어떻게 보이는지를 사람이 확인해야 한다. hvy-common SlackClient 는 응답 ok 를 보지 않아 invalid_blocks 가
 * 조용히 사라지므로, 여기서는 SDK 로 직접 보내 ok·error 를 단언한다. 보내는 메시지는 4개(일일 매수 표·일일 관망·아침 재판정·장기)이고 전부 샘플 데이터다.
 * <pre>SLACK_BOT_TOKEN=xoxb-... ADVISOR_SLACK_TEST_CHANNEL=#test-channel ./gradlew test --tests "kr.hvy.blog.modules.advisor.application.slack.AdvisorSlackTableManualTest"</pre>
 */
class AdvisorSlackTableManualTest {

  @Test
  @DisplayName("샘플 4종(일일 표·일일 관망·아침 조치 표·장기 표)을 실제 채널에 보내 ok=true 인지 확인한다 — 렌더링은 사람이 본다")
  void postsSampleTables() throws Exception {
    String token = System.getenv("SLACK_BOT_TOKEN");
    String channel = System.getenv("ADVISOR_SLACK_TEST_CHANNEL");
    Assumptions.assumeTrue(token != null && !token.isBlank(), "SLACK_BOT_TOKEN 없음 — 수동 확인 스킵");
    Assumptions.assumeTrue(channel != null && !channel.isBlank(), "ADVISOR_SLACK_TEST_CHANNEL 없음 — 수동 확인 스킵");

    MethodsClient client = Slack.getInstance().methods(token);
    for (SlackMessage message : List.of(dailyWithPicks(), dailyAbstain(), morning(), longTerm())) {
      ChatPostMessageResponse response = client.chatPostMessage(ChatPostMessageRequest.builder().channel(channel).text(message.getFallbackText())
          .blocks(message.toBlocks()).attachments(message.toAttachments()).build());
      System.out.println("=== " + message.getClass().getSimpleName() + " ok=" + response.isOk() + " error=" + response.getError()
          + " warning=" + response.getWarning());
      assertThat(response.isOk()).as(message.getClass().getSimpleName() + ": " + response.getError()).isTrue();
    }
  }

  /** 매수 3개 일일 판단 — 긴 종목명(줄바꿈 열)·테마 대표 종목 표기 포함 */
  private static DailyAdviceMessage dailyWithPicks() {
    MarketRegime regime = new MarketRegime("0001", LocalDate.of(2026, 9, 26), MarketTrendCode.BULL, 3, VolRegimeCode.NORMAL, 0.55, 0.011, 1200,
        new MarketRegime.Policy("regime-policy-v2", 10, null),
        List.of(new MarketRegime.Theme("5", 31, 0.01, 0.031, 0.02, 0.6, ThemeStrength.STRONG, List.of("삼성전자"))));
    AdviceHeader header = AdviceHeader.builder().adviceId(0L).runId(0L).baseDate(LocalDate.of(2026, 9, 26)).adviceKind(AdviceKind.DAILY)
        .variant(AdviceVariant.LIVE).horizonDays(5).regimeCode(MarketRegimeCode.RISK_ON).pUp(0.65).regime(regime)
        .summary("[수동 확인 샘플] 표 블록 렌더링 확인용 메시지입니다.").promptVersion("advice-v9").model("sample").build();
    List<PickRow> picks = List.of(
        PickRow.builder().ticker("005930").pickRank(1).direction(PickDirection.LONG).conviction(0.80).thesis("샘플 근거").riskNote("샘플 리스크").build(),
        PickRow.builder().ticker("373220").pickRank(2).direction(PickDirection.LONG).conviction(0.70).thesis("샘플 근거").build(),
        PickRow.builder().ticker("000660").pickRank(3).direction(PickDirection.LONG).conviction(0.65).thesis("샘플 근거").build());
    Map<String, CandidateRow> candidates = Map.of(
        "005930", CandidateRow.builder().ticker("005930").stockName("삼성전자").quantScore(0.72).features(Map.of("theme", "5")).build(),
        "373220", CandidateRow.builder().ticker("373220").stockName("LG에너지솔루션").quantScore(0.61).features(Map.of("theme", "6")).build(),
        "000660", CandidateRow.builder().ticker("000660").stockName("SK하이닉스").quantScore(0.58).features(Map.of()).build());
    return DailyAdviceMessage.builder().header(header).picks(picks).candidates(candidates).marketLabel("KOSPI").universeLabel("KOSPI200")
        .scoreboardLines(List.of()).runId(0L).build();
  }

  /** 관망(0픽) 일일 판단 — 표 없이 관망 문구 */
  private static DailyAdviceMessage dailyAbstain() {
    AdviceHeader header = AdviceHeader.builder().adviceId(0L).runId(0L).baseDate(LocalDate.of(2026, 9, 26)).adviceKind(AdviceKind.DAILY)
        .variant(AdviceVariant.LIVE).horizonDays(5).regimeCode(MarketRegimeCode.RISK_OFF).pUp(0.6)
        .summary("[수동 확인 샘플] 확신 있는 매수 근거가 없어 관망합니다. 표가 없어야 정상입니다.").promptVersion("advice-v9").model("sample").build();
    return DailyAdviceMessage.builder().header(header).picks(List.of()).candidates(Map.of()).universeLabel("KOSPI200").scoreboardLines(List.of())
        .runId(0L).build();
  }

  /** 아침 재판정 — 유지·제외·추가 한 행씩 */
  private static MorningAdviceMessage morning() {
    PickRow keep = PickRow.builder().ticker("005930").pickRank(1).direction(PickDirection.LONG).conviction(0.80).action(PickAction.KEEP)
        .actionReason("샘플 유지").build();
    PickRow add = PickRow.builder().ticker("000660").pickRank(2).direction(PickDirection.LONG).conviction(0.60).action(PickAction.ADD)
        .actionReason("샘플 추가").thesis("샘플 근거").build();
    PickRow dropped = PickRow.builder().ticker("373220").pickRank(2).direction(PickDirection.LONG).conviction(0.70).build();
    return MorningAdviceMessage.builder().baseDate(LocalDate.of(2026, 9, 26)).entryDate(LocalDate.of(2026, 9, 29)).exitDate(LocalDate.of(2026, 10, 6))
        .overnightLines(List.of("[수동 확인 샘플] 미국 세션 요약")).kept(List.of(keep)).added(List.of(add))
        .drops(List.of(new MorningAdviceGuard.Drop(dropped, "샘플 제외"))).names(Map.of("005930", "삼성전자", "000660", "SK하이닉스", "373220", "LG에너지솔루션"))
        .summary("샘플 총평").runId(0L).adviceId(0L).parentAdviceId(0L).build();
  }

  /** 장기 규칙 추천 — 순위·종목명·코드·장기 점수 */
  private static LongTermAdviceMessage longTerm() {
    AdviceHeader header = AdviceHeader.builder().adviceId(0L).runId(0L).baseDate(LocalDate.of(2026, 9, 26)).adviceKind(AdviceKind.H60)
        .variant(AdviceVariant.LIVE).horizonDays(60).summary("[수동 확인 샘플]").promptVersion("longterm-v1").model("sample").build();
    List<PickRow> picks = List.of(PickRow.builder().ticker("005930").pickRank(1).direction(PickDirection.LONG).conviction(0.55).thesis("샘플 서술").build());
    return LongTermAdviceMessage.builder().header(header).picks(picks)
        .candidates(Map.of("005930", CandidateRow.builder().ticker("005930").stockName("삼성전자").quantScore(0.42).build()))
        .weights(Map.of("MOM_12_1", 0.30)).verdictLabel("판정 불가: 표본 부족, 2년 이상 필요").universeLabel("KOSPI200").runId(0L).build();
  }
}
