package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.MorningAdviceResponse;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.MorningVerdict;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.GlobalLink;
import kr.hvy.blog.modules.advisor.domain.model.MorningCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.PromptInputWriter;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * 아침 재판정 잡: 게이트(휴장·08:50 마감·저녁 판단 없음·중복), 밤사이 블록 룩어헤드(현지일 ≥ 오늘 행 제외), 트리거는 호출 여부와 무관(매일 1회 호출),
 * 저장된 MORNING 헤더의 parent·같은 창·diff_json, 픽 action.
 */
class MorningAdviseJobTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final MarketCalendarService calendar = mock(MarketCalendarService.class);
  private final AdviceWriter adviceWriter = mock(AdviceWriter.class);
  private final PromptInputWriter promptInputs = mock(PromptInputWriter.class);
  private final MorningCheckWriter morningChecks = mock(MorningCheckWriter.class);
  private final GlobalLinkService links = mock(GlobalLinkService.class);
  private final MarketJudgeClient judge = mock(MarketJudgeClient.class);
  private final AdvisorNotifier notifier = mock(AdvisorNotifier.class);

  private final LocalDate base = LocalDate.of(2026, 9, 24); // 목요일 저녁 판단
  private final LocalDate today = LocalDate.of(2026, 9, 25); // 금요일 07:40
  /** 07:40 KST = 22:40 UTC 전날 */
  private final Instant at0740 = Instant.parse("2026-09-24T22:40:00Z");
  private final AdviceHeader evening = AdviceHeader.builder().adviceId(900L).runId(55L).baseDate(base).adviceKind(AdviceKind.DAILY)
      .variant(AdviceVariant.LIVE).horizonDays(5).kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.NEUTRAL).pUp(0.7).summary("저녁 총평")
      .entryDate(LocalDate.of(2026, 9, 25)).exitDate(LocalDate.of(2026, 10, 1)).weightSetId(3L).dataQuality(DataQuality.OK)
      .dataAsOf(Map.of("domestic", "2026-09-24")).build();
  private final List<PickRow> eveningPicks = List.of(
      pick("000001", 1, 0.80), pick("000002", 2, 0.70), pick("000003", 3, 0.65));
  private final List<CandidateRow> candidates = List.of(cand("000001"), cand("000002"), cand("000003"), cand("000004"), cand("000005"));

  @BeforeEach
  void setUp() {
    when(calendar.isTradingDay(any())).thenReturn(true);
    when(calendar.lastTradingDayOnOrBefore(today.minusDays(1))).thenReturn(base);
    when(adviceWriter.findLatest(AdviceKind.DAILY, AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.of(evening));
    when(adviceWriter.find(base, AdviceKind.MORNING, AdviceVariant.LIVE)).thenReturn(Optional.empty());
    when(adviceWriter.picks(900L)).thenReturn(eveningPicks);
    when(adviceWriter.candidates(900L)).thenReturn(candidates);
    when(adviceWriter.insertHeader(any())).thenReturn(901L);
    when(promptInputs.find(55L, AdviceVariant.LIVE)).thenReturn(Optional.of(new PromptInputRow(55L, AdviceVariant.LIVE, "advice-v7", "sha",
        "{\"asOf\":\"2026-09-24\",\"candidates\":{\"columns\":[\"tkr\"]}}", "{}", "{}")));
    when(morningChecks.findByAdvice(900L)).thenReturn(Optional.empty());
    when(notifier.publish(any())).thenReturn(true);
    // 미국: 기준일 현지일(정상 밤사이) 세션 + 오늘 현지일 행(수집 시계 오류 흉내 — 들어가면 룩어헤드)
    when(links.overnight(anyList(), eq(today))).thenReturn(Map.of(
        "SPX", new GlobalLinkService.Overnight("SPX", base, 0.004),
        "COMP", new GlobalLinkService.Overnight("COMP", today, 0.09),
        "FX@KRW", new GlobalLinkService.Overnight("FX@KRW", base, 0.002)));
    when(links.link(eq("0001"), eq("SPX"), eq(base))).thenReturn(new GlobalLink("0001", "SPX", 0.5, 0.5, 60));
    when(links.link(eq("1001"), eq("COMP"), eq(base))).thenReturn(new GlobalLink("1001", "COMP", 0.8, 0.5, 60));
    when(links.sigma1d(anyString(), eq(base))).thenReturn(0.01);
    when(links.sectorMoves(today)).thenReturn(List.of(
        new GlobalLinkService.SectorMove("SOXX", "SEMICON", base, 0.01, 0.02),
        new GlobalLinkService.SectorMove("XBI", "BIO", today, 0.20, 0.02)));
    when(judge.call(anyString(), anyString(), anyString(), eq(MorningAdviceResponse.class))).thenReturn(callResult(new MorningAdviceResponse(
        List.of(new MorningAdviceResponse.Decision("000001", "KEEP", "SOX 강세가 thesis 지지"),
            new MorningAdviceResponse.Decision("000002", "DROP", "밤사이 역풍"),
            new MorningAdviceResponse.Decision("000003", "KEEP", "변화 없음")),
        List.of(new MorningAdviceResponse.Addition("000004", "LONG", "0.60", "근거", "리스크", "밤사이 수혜"),
            new MorningAdviceResponse.Addition("999999", "LONG", "0.60", "근거", "리스크", "후보 밖")),
        "요약")));
  }

  @Test
  @DisplayName("저장된 MORNING 행: 저녁과 같은 base_date·창, parent_advice_id, 픽 = KEEP + ADD(action·사유), DROP 은 diff_json 에만, 후보는 저녁 스냅샷 복사")
  void savesMorningAdvice() {
    AdvisorExecution execution = execution();
    job(at0740).execute(execution);

    assertThat(execution.isSkipped()).isFalse();
    ArgumentCaptor<AdviceHeader> header = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter).insertHeader(header.capture());
    AdviceHeader saved = header.getValue();
    assertThat(saved.adviceKind()).isEqualTo(AdviceKind.MORNING);
    assertThat(saved.variant()).isEqualTo(AdviceVariant.LIVE);
    assertThat(saved.baseDate()).isEqualTo(base);
    assertThat(saved.entryDate()).isEqualTo(evening.entryDate());
    assertThat(saved.exitDate()).isEqualTo(evening.exitDate());
    assertThat(saved.horizonDays()).isEqualTo(5);
    assertThat(saved.parentAdviceId()).isEqualTo(900L);
    assertThat(saved.promptVersion()).isEqualTo(PromptResources.MORNING_VERSION);
    assertThat(saved.kospiDir()).as("아침은 국면을 다시 판단하지 않는다 — 국면 콜 채점이 중복되지 않게 비운다").isNull();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> drops = (List<Map<String, Object>>) saved.diffJson().get("drop");
    assertThat(drops).singleElement().satisfies(d -> assertThat(d).containsEntry("ticker", "000002").containsEntry("reason", "밤사이 역풍"));
    assertThat(saved.diffJson()).containsEntry("parentAdviceId", 900L).containsKey("triggers");
    assertThat(saved.guard()).containsEntry("addOutsideCandidates", 1);

    verify(adviceWriter).insertCandidates(901L, candidates);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter).insertPicks(eq(901L), picks.capture());
    assertThat(picks.getValue()).extracting(PickRow::ticker, PickRow::action)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("000001", PickAction.KEEP), org.assertj.core.groups.Tuple.tuple("000003", PickAction.KEEP),
            org.assertj.core.groups.Tuple.tuple("000004", PickAction.ADD));
    assertThat(picks.getValue().getFirst().actionReason()).isEqualTo("SOX 강세가 thesis 지지");
    verify(promptInputs).upsert(any());
    verify(adviceWriter).markPublished(eq(901L), any());
    assertThat(execution.metadata("drop")).isEqualTo(1);
  }

  @Test
  @DisplayName("룩어헤드: 현지일 = 오늘인 미국·섹터 행은 입력에서 빠진다 (us_date ≤ 오늘−1), β·σ 는 저녁 기준일로 추정")
  void noLookahead() {
    job(at0740).execute(execution());

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(judge).call(anyString(), payload.capture(), anyString(), eq(MorningAdviceResponse.class));
    Map<String, Object> json = AdvisorJson.readMap(payload.getValue());
    @SuppressWarnings("unchecked")
    Map<String, Object> overnight = (Map<String, Object>) json.get("overnight");
    @SuppressWarnings("unchecked")
    Map<String, Object> us = (Map<String, Object>) overnight.get("us");
    assertThat(us).containsOnlyKeys("SPX");
    assertThat(payload.getValue()).as("오늘 현지일 섹터 행(XBI)·오늘 현지일 COMP r1(0.09) 은 없다").doesNotContain("XBI").doesNotContain("0.09");
    assertThat(overnight).containsEntry("usDate", base.toString()).containsKey("fx");
    @SuppressWarnings("unchecked")
    Map<String, Object> evening = (Map<String, Object>) json.get("evening");
    assertThat(evening).as("저녁 입력 스냅샷이 원문 그대로 실린다").containsEntry("asOf", "2026-09-24");
    verify(links).link("0001", "SPX", base);
    verify(links).sigma1d("0001", base);
  }

  @Test
  @DisplayName("트리거 미달(|갭| 0.2% < σ 1%, 섹터 z 0.5, 점검 없음)이어도 LLM 은 1회 호출되고 트리거는 diff_json 에 false 로만 남는다")
  void callsEveryDayEvenWithoutTrigger() {
    job(at0740).execute(execution());

    verify(judge, times(1)).call(anyString(), anyString(), anyString(), eq(MorningAdviceResponse.class));
    ArgumentCaptor<AdviceHeader> header = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter).insertHeader(header.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> triggers = (Map<String, Object>) header.getValue().diffJson().get("triggers");
    assertThat(triggers).containsEntry("any", false).containsEntry("gap", false).containsEntry("sector", false).containsEntry("caution", false);
  }

  @Test
  @DisplayName("트리거: |β×r1| ≥ σ₁d, 섹터 연동 |z| ≥ 2, 07:30 CAUTION 을 각각 기록한다")
  void triggersRecorded() {
    when(links.overnight(anyList(), eq(today))).thenReturn(Map.of("SPX", new GlobalLinkService.Overnight("SPX", base, -0.03)));
    when(links.sectorMoves(today)).thenReturn(List.of(new GlobalLinkService.SectorMove("SOXX", "SEMICON", base, -0.06, 0.02)));
    when(morningChecks.findByAdvice(900L)).thenReturn(Optional.of(MorningCheckRow.builder().adviceId(900L).verdict(MorningVerdict.CAUTION).build()));

    MorningAdviseJob.Overnight o = job(at0740).overnight(today, evening);

    assertThat(o.triggers()).containsEntry("gap", true).containsEntry("gapIndexes", List.of("0001")).containsEntry("sector", true)
        .containsEntry("sectorSymbols", List.of("SOXX")).containsEntry("caution", true).containsEntry("any", true);
    assertThat(MorningAdviseJob.overnightLines(o)).anyMatch(l -> l.contains("SOXX(SEMICON)")).anyMatch(l -> l.contains("CAUTION"));
  }

  @Test
  @DisplayName("08:50 KST 이후 실행은 SKIPPED — LLM·저장 없음")
  void skipAfterDeadline() {
    AdvisorExecution execution = execution();
    job(Instant.parse("2026-09-24T23:50:00Z")).execute(execution); // 08:50 KST

    assertThat(execution.isSkipped()).isTrue();
    assertThat(execution.skipReason()).contains("08:50");
    verify(judge, never()).call(anyString(), anyString(), anyString(), any());
    verify(adviceWriter, never()).insertHeader(any());
  }

  @Test
  @DisplayName("저녁 DAILY LIVE 판단이 없거나 직전 영업일보다 오래됐거나 이미 재판정했으면 SKIPPED")
  void skipWithoutEvening() {
    when(adviceWriter.findLatest(AdviceKind.DAILY, AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.empty());
    AdvisorExecution none = execution();
    job(at0740).execute(none);
    assertThat(none.isSkipped()).isTrue();

    when(adviceWriter.findLatest(AdviceKind.DAILY, AdviceVariant.LIVE, today.minusDays(1)))
        .thenReturn(Optional.of(evening.toBuilder().baseDate(base.minusDays(1)).build()));
    AdvisorExecution stale = execution();
    job(at0740).execute(stale);
    assertThat(stale.isSkipped()).isTrue();

    when(adviceWriter.findLatest(AdviceKind.DAILY, AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.of(evening));
    when(adviceWriter.find(base, AdviceKind.MORNING, AdviceVariant.LIVE)).thenReturn(Optional.of(evening.toBuilder().adviceId(77L).build()));
    AdvisorExecution already = execution();
    job(at0740).execute(already);
    assertThat(already.isSkipped()).isTrue();
    assertThat(already.skipReason()).contains("advice=77");

    verify(judge, never()).call(anyString(), anyString(), anyString(), any());
    verify(adviceWriter, never()).insertHeader(any());
    verify(adviceWriter, never()).markPublished(anyLong(), any());
  }

  @Test
  @DisplayName("morning-v2: 저녁이 관망(0픽)이어도 후보가 있으면 재판정한다 — 결정 enum 은 저녁 후보로 대신하고, ADD 만 저장·발행된다")
  void eveningAbstainStillRejudges() {
    when(adviceWriter.picks(900L)).thenReturn(List.of());
    when(judge.call(anyString(), anyString(), anyString(), eq(MorningAdviceResponse.class))).thenReturn(callResult(new MorningAdviceResponse(
        List.of(), List.of(new MorningAdviceResponse.Addition("000004", null, "0.60", "근거", "리스크", "SOX 강세")), "밤사이 반도체 강세로 1종목 추가")));
    AdvisorExecution execution = execution();
    job(at0740).execute(execution);

    assertThat(execution.isSkipped()).isFalse();
    assertThat(execution.metadata("eveningPicks")).isEqualTo(0);
    ArgumentCaptor<String> schema = ArgumentCaptor.forClass(String.class);
    verify(judge).call(anyString(), anyString(), schema.capture(), eq(MorningAdviceResponse.class));
    assertThat(schema.getValue()).as("strict enum 은 빈 목록을 거부 — 결정 ticker 는 저녁 후보로 대신한다").doesNotContain("\"enum\":[]")
        .doesNotContain("\"direction\"");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter).insertPicks(eq(901L), picks.capture());
    assertThat(picks.getValue()).extracting(PickRow::ticker, PickRow::action, PickRow::direction)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("000004", PickAction.ADD, PickDirection.LONG));
    verify(adviceWriter).markPublished(eq(901L), any());
  }

  @Test
  @DisplayName("morning-v2: 전부 DROP 한 0픽 아침도 저장·발행한다 — 픽 0행, DROP 은 diff_json 에, 발행 문구는 관망")
  void allDropIsSavedAndPublished() {
    when(judge.call(anyString(), anyString(), anyString(), eq(MorningAdviceResponse.class))).thenReturn(callResult(new MorningAdviceResponse(
        List.of(new MorningAdviceResponse.Decision("000001", "DROP", "a"), new MorningAdviceResponse.Decision("000002", "DROP", "b"),
            new MorningAdviceResponse.Decision("000003", "DROP", "c")), List.of(), "밤사이 급락으로 전부 제외")));
    AdvisorExecution execution = execution();
    job(at0740).execute(execution);

    assertThat(execution.isSkipped()).isFalse();
    verify(adviceWriter).insertPicks(eq(901L), eq(List.of()));
    ArgumentCaptor<AdviceHeader> header = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter).insertHeader(header.capture());
    assertThat((List<?>) header.getValue().diffJson().get("drop")).hasSize(3);
    assertThat(header.getValue().guard()).doesNotContainKey("dropReverted");
    ArgumentCaptor<kr.hvy.common.infrastructure.notification.slack.message.SlackMessage> message =
        ArgumentCaptor.forClass(kr.hvy.common.infrastructure.notification.slack.message.SlackMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(message.getValue().getFallbackText()).endsWith(kr.hvy.blog.modules.advisor.application.slack.DailyAdviceMessage.ABSTAIN_TEXT);
    verify(adviceWriter).markPublished(eq(901L), any());
  }

  private MorningAdviseJob job(Instant now) {
    return new MorningAdviseJob(properties, calendar, adviceWriter, promptInputs, morningChecks, links, new PromptResources(), judge, notifier,
        clockProvider(now));
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<Clock> clockProvider(Instant fixedNow) {
    ObjectProvider<Clock> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(Clock.fixed(fixedNow, ZoneOffset.UTC));
    return provider;
  }

  private static MarketJudgeClient.CallResult<MorningAdviceResponse> callResult(MorningAdviceResponse response) {
    return new MarketJudgeClient.CallResult<>(response, "{}", null, 0, 0, "judge-model", "resp_1", Map.of());
  }

  private static PickRow pick(String ticker, int rank, double conviction) {
    return PickRow.builder().ticker(ticker).pickRank(rank).direction(PickDirection.LONG).conviction(conviction).thesis("t").riskNote("r")
        .cited(List.of()).citedNews(List.of()).build();
  }

  private static CandidateRow cand(String ticker) {
    return CandidateRow.builder().ticker(ticker).quantRank(1).quantScore(0.5).stockName("종목" + ticker).marketType("KOSPI").benchIndexCode("0001")
        .sectorCode("0013").signals(Map.of()).features(Map.of()).build();
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(71L).jobType(AdvisorJobType.MORNING_ADVISE).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(today).build();
    return new AdvisorExecution(run, today, properties);
  }
}
