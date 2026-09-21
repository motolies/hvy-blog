package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.slack.IntradayCheckMessage;
import kr.hvy.blog.modules.advisor.client.llm.PickNoteResponse;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteClass;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteStatus;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.IntradayCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.PickNoteRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.IntradayCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.PickNoteRepository;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisIndexPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * 장중 점검 규칙: 직전 영업일 판단이 없으면 SKIPPED, 지수·픽 대조와 일치율·판정, KIS 실패 1건은 삼키고 나머지로 판정, 저장·발행 (2026-09-13) +
 * note-v1: 픽별 정량·분류 노트 저장, FLAT 아닌 픽만 assist 1회 회고, 티커 언급 hypothesis 는 null, assist·노트 저장 예외는 격리(PARTIAL),
 * 전부 FLAT 이면 LLM 미호출, note.enabled=false 면 REFLECT 건너뛰되 노트는 저장, 지수 시가 결손 → PREV_CLOSE 폴백·MIXED, 고정 Clock 으로 장외 판정.
 */
class IntradayCheckJobTest {

  /** 2026-09-12 12:00 KST — 정규 점검 창 안 */
  private static final Instant NOON_KST = Instant.parse("2026-09-12T03:00:00Z");
  /** 2026-09-12 16:00 KST — 장외 */
  private static final Instant AFTER_CLOSE_KST = Instant.parse("2026-09-12T07:00:00Z");

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final KisProperties kisProperties = new KisProperties();
  private final KisMarketDataPort marketData = mock(KisMarketDataPort.class);
  private final MarketCalendarService calendar = mock(MarketCalendarService.class);
  private final AdviceWriter adviceWriter = mock(AdviceWriter.class);
  private final IntradayCheckWriter checkWriter = mock(IntradayCheckWriter.class);
  private final AdvisorNotifier notifier = mock(AdvisorNotifier.class);
  private final MarketJudgeClient assist = mock(MarketJudgeClient.class);
  private final PickNoteRepository notes = mock(PickNoteRepository.class);
  private final LocalDate today = LocalDate.of(2026, 9, 12);
  private IntradayCheckJob job;

  @BeforeEach
  void setUp() {
    kisProperties.setAppKey("k");
    kisProperties.setAppSecret("s");
    job = job(NOON_KST);
    when(calendar.isTradingDay(today)).thenReturn(true);
    when(calendar.lastTradingDayOnOrBefore(today.minusDays(1))).thenReturn(today.minusDays(1));
    when(notifier.publish(any())).thenReturn(true);
    when(checkWriter.insert(any())).thenReturn(5L);
    when(notes.insertAll(any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
  }

  @Test
  @DisplayName("직전 영업일 LIVE 판단이 없으면 SKIPPED, KIS 호출 없음")
  void skipsWithoutAdvice() {
    when(adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.empty());
    AdvisorExecution execution = execution();
    job.execute(execution);
    assertThat(execution.isSkipped()).isTrue();
    verify(marketData, never()).fetchIndexPrice(any(), any());
    verify(notes, never()).insertAll(any());
  }

  @Test
  @DisplayName("지수·픽을 대조해 일치율·판정을 저장·발행하고 종목 1건 실패는 삼킨다. FLAT 아닌 픽만 회고해 노트를 저장하고 티커를 언급한 가설은 null")
  void checksReflectsAndPublishes() {
    stubAdvice842();
    when(assist.call(any(), any(), any(), eq(PickNoteResponse.class))).thenReturn(callResult(new PickNoteResponse(List.of(
        new PickNoteResponse.Note("000660", "시가 대비 -2.2%, 지수 대비 -2.5% (-2.5σ) IDIOSYNCRATIC.", "thesis 의 외국인 순매수 지속 가정이 흔들렸다. risk 의 첫 신호(수급 반전)가 발동했다.",
            "반도체 섹터에서 외국인 순매수 시그널이 강한 후보는 하락 국면에서 확신을 낮춘다.", new PickNoteResponse.Tags(List.of("FOREIGN_FLOW", "NOT_A_SIGNAL"), "G2510", "RISK_ON")),
        new PickNoteResponse.Note("005930", "시가 대비 +1.4%, 지수 대비 +1.1% (1.6σ) ON_TRACK.", "thesis 의 모멘텀 가정이 유지됐다.\u0007",
            "005930 은 지수 강세일 때 따라간다.", new PickNoteResponse.Tags(List.of("MOM_20D"), null, null))))));

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.isSkipped()).isFalse();
    assertThat(execution.failureCount()).isEqualTo(1);
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.PARTIAL);
    assertThat(execution.steps()).extracting(AdvisorExecution.StepResult::name).containsExactly("INDEX", "PICKS", "REFLECT", "SAVE", "NOTES", "PUBLISH");
    assertThat(execution.steps()).extracting(AdvisorExecution.StepResult::status).containsOnly("OK");

    ArgumentCaptor<IntradayCheckRow> saved = ArgumentCaptor.forClass(IntradayCheckRow.class);
    verify(checkWriter).insert(saved.capture());
    IntradayCheckRow row = saved.getValue();
    assertThat(row.adviceId()).isEqualTo(842L);
    assertThat(row.checkedAt()).isEqualTo(NOON_KST);
    assertThat(row.agreementRatio()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9)); // 005930 ✓, 000660 ✗, 035420(AVOID, 음수) ✓
    assertThat(row.verdict()).isEqualTo(IntradayVerdict.ON_TRACK);
    assertThat(row.comment()).contains("판단 유지").contains("SK하이닉스 -2.1%");
    @SuppressWarnings("unchecked")
    Map<String, Object> kospi = (Map<String, Object>) row.indexJson().get("0001");
    assertThat(kospi).containsEntry("agree", true).containsEntry("changeRate", 0.31).containsEntry("open", 2730.0);
    @SuppressWarnings("unchecked")
    Map<String, Object> kosdaq = (Map<String, Object>) row.indexJson().get("1001");
    assertThat(kosdaq).containsEntry("agree", true); // NEUTRAL 예측, |−0.12| < 0.3
    assertThat(row.pickJson()).hasSize(4);
    assertThat(row.pickJson().get(0)).containsEntry("class", "ON_TRACK").containsKey("sinceOpen").containsKey("z");
    assertThat(row.pickJson().get(3)).containsKey("error").doesNotContainKey("class");

    // 노트: 조회 실패 픽 제외 3행, FLAT 은 회고 없이 class 만
    Map<String, PickNoteRow> byTicker = savedNotes();
    assertThat(byTicker).containsOnlyKeys("005930", "000660", "035420");
    assertThat(byTicker.values()).allMatch(n -> n.status() == PickNoteStatus.OPEN && n.checkId() == 5L && n.adviceId() == 842L
        && n.baseDate().equals(today.minusDays(1)) && Boolean.FALSE.equals(n.tags().get("offHours")) && n.notedAt().equals(NOON_KST));

    PickNoteRow samsung = byTicker.get("005930");
    assertThat(samsung.noteClass()).isEqualTo(PickNoteClass.ON_TRACK);
    assertThat(samsung.sinceOpenRate()).isCloseTo(71000.0 / 70000 - 1, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(samsung.gapRate()).isCloseTo(70000.0 / 70200 - 1, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(samsung.excessRate()).isCloseTo((71000.0 / 70000 - 1) - (2740.10 / 2730 - 1), org.assertj.core.data.Offset.offset(1e-9));
    assertThat(samsung.zScore()).isGreaterThan(1.0).isLessThan(2.0);
    assertThat(samsung.deviation()).startsWith("시가 대비 +1.4%");
    assertThat(samsung.why()).as("제어문자 제거").doesNotContain("\u0007").contains("모멘텀 가정");
    assertThat(samsung.hypothesis()).as("6자리 종목코드 언급 → null").isNull();
    assertThat(samsung.model()).isEqualTo("assist-model");
    assertThat(samsung.tags()).containsEntry("excessBasis", "OPEN").containsEntry("benchCode", "0001").containsEntry("signals", List.of("MOM_20D"))
        .containsEntry("sector", "G2510").containsEntry("regime", "RISK_ON").as("secRs20 < 0 → secCons 0").containsEntry("secCons", 0);

    PickNoteRow hynix = byTicker.get("000660");
    assertThat(hynix.noteClass()).isEqualTo(PickNoteClass.IDIOSYNCRATIC);
    assertThat(hynix.hypothesis()).contains("외국인 순매수");
    assertThat(hynix.tags()).containsEntry("signals", List.of("FOREIGN_FLOW")).as("secRs5/20/60 전부 양수 → AdvicePromptBuilder.secCons = 1").containsEntry("secCons", 1);

    PickNoteRow naver = byTicker.get("035420");
    assertThat(naver.noteClass()).isEqualTo(PickNoteClass.FLAT);
    assertThat(naver.deviation()).isNull();
    assertThat(naver.model()).isNull();
    assertThat(naver.direction()).isEqualTo(PickDirection.AVOID);
    assertThat(naver.tags()).as("secRs 없음 → secCons null").containsEntry("secCons", null);

    // assist 는 1회, FLAT 픽(035420)은 입력·스키마에 없다, 픽 행에 basis·secCons 가 실린다
    ArgumentCaptor<String> userJson = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> schema = ArgumentCaptor.forClass(String.class);
    verify(assist).call(any(), userJson.capture(), schema.capture(), eq(PickNoteResponse.class));
    assertThat(userJson.getValue()).contains("\"000660\"").contains("\"005930\"").doesNotContain("035420").contains("\"excessBasis\":\"OPEN\"")
        .contains("\"regime\":\"RISK_ON\"").contains("\"basis\":\"OPEN\"").contains("\"secCons\":1").contains("\"secCons\":0");
    assertThat(schema.getValue()).contains("\"000660\"").contains("\"005930\"").doesNotContain("035420").contains("FOREIGN_FLOW");
    assertThat(execution.llmCalls()).isEqualTo(1);
    assertThat(execution.promptVersion()).isEqualTo(PromptResources.NOTE_VERSION);

    assertThat(execution.metadata("verdict")).isEqualTo("ON_TRACK");
    assertThat(execution.metadata("notes")).isEqualTo(3);
    assertThat(execution.metadata("reflected")).as("응답 노트 수").isEqualTo(2);
    assertThat(execution.metadata("excessBasis")).isEqualTo("OPEN");
    assertThat(execution.metadata("offHours")).isNull();

    ArgumentCaptor<IntradayCheckMessage> message = ArgumentCaptor.forClass(IntradayCheckMessage.class);
    verify(notifier).publish(message.capture());
    List<LayoutBlock> blocks = message.getValue().toBlocks();
    assertThat(blocks).as("요약 section + 픽 줄 section + context").hasSize(3);
    assertThat(sectionText(blocks.get(0))).contains("12:00 중간 점검").doesNotContain("장외 점검");
    assertThat(sectionText(blocks.get(1))).contains("SK하이닉스 시가대비 -2.2%").contains("IDIOSYNCRATIC").contains("ON_TRACK").contains("FLAT").doesNotContain("999999");
  }

  @Test
  @DisplayName("일치율 30% 미만이면 OFF_TRACK. 후보 특징(vol20)이 없으면 전부 FLAT → assist 미호출·REFLECT SKIPPED, 노트는 class 만 저장")
  void offTrackAllFlat() {
    AdviceHeader advice = AdviceHeader.builder().adviceId(1L).baseDate(today.minusDays(1)).kospiDir(DirectionCall.DOWN).kosdaqDir(DirectionCall.DOWN).build();
    when(adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.of(advice));
    when(adviceWriter.picks(1L)).thenReturn(List.of(
        PickRow.builder().ticker("A").pickRank(1).direction(PickDirection.LONG).conviction(0.7).build(),
        PickRow.builder().ticker("B").pickRank(2).direction(PickDirection.LONG).conviction(0.7).build()));
    when(adviceWriter.candidates(1L)).thenReturn(List.of());
    when(marketData.fetchIndexPrice(any(), any())).thenReturn(index("1", "1.0", "1"));
    when(marketData.fetchPrice(any(), any())).thenReturn(price("1", "-1.0", "1", "1"));

    AdvisorExecution execution = execution();
    job.execute(execution);

    ArgumentCaptor<IntradayCheckRow> saved = ArgumentCaptor.forClass(IntradayCheckRow.class);
    verify(checkWriter).insert(saved.capture());
    assertThat(saved.getValue().verdict()).isEqualTo(IntradayVerdict.OFF_TRACK);
    assertThat(saved.getValue().agreementRatio()).isEqualTo(0.0);
    verify(assist, never()).call(any(), any(), any(), any());
    assertThat(execution.metadata("skip.REFLECT")).asString().contains("회고 대상 없음");
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(savedNotes().values()).hasSize(2).allMatch(n -> n.noteClass() == PickNoteClass.FLAT && n.deviation() == null && n.zScore() == null);
    assertThat(execution.metadata("reflected")).isEqualTo(0);
  }

  @Test
  @DisplayName("assist 예외는 격리된다: 노트는 정량·class 만 저장, run PARTIAL(STEP:REFLECT), 점검·Slack 은 그대로")
  void reflectFailureIsolated() {
    stubAdvice842();
    when(assist.call(any(), any(), any(), eq(PickNoteResponse.class))).thenThrow(new IllegalStateException("판단 모델 응답이 미완입니다"));

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.PARTIAL);
    assertThat(execution.failures()).extracting(AdvisorExecution.Failure::target).contains("STEP:REFLECT", "PICK:999999");
    assertThat(stepStatus(execution, "REFLECT")).isEqualTo("FAILED");
    verify(checkWriter).insert(any());
    verify(notifier).publish(any());
    Map<String, PickNoteRow> byTicker = savedNotes();
    assertThat(byTicker.values()).hasSize(3).allMatch(n -> n.deviation() == null && n.why() == null && n.hypothesis() == null && n.model() == null);
    assertThat(byTicker.values()).extracting(PickNoteRow::noteClass).containsExactlyInAnyOrder(PickNoteClass.ON_TRACK, PickNoteClass.IDIOSYNCRATIC,
        PickNoteClass.FLAT);
    assertThat(execution.metadata("reflected")).isEqualTo(0);
    assertThat(execution.llmCalls()).isZero();
  }

  @Test
  @DisplayName("노트 저장 예외는 격리된다(NOTES 단계): 점검 저장·Slack 발행은 유지, run PARTIAL, notes=0")
  void noteSaveFailureIsolated() {
    stubAdvice842();
    properties.getNote().setEnabled(false);
    org.mockito.Mockito.doThrow(new org.springframework.jdbc.BadSqlGrammarException("insert", "INSERT INTO tb_advisor_pick_note",
        new java.sql.SQLException("relation \"tb_advisor_pick_note\" does not exist"))).when(notes).insertAll(any());

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.PARTIAL);
    assertThat(execution.failures()).extracting(AdvisorExecution.Failure::target).contains("STEP:NOTES");
    assertThat(stepStatus(execution, "SAVE")).isEqualTo("OK");
    assertThat(stepStatus(execution, "NOTES")).isEqualTo("FAILED");
    assertThat(stepStatus(execution, "PUBLISH")).isEqualTo("OK");
    verify(checkWriter).insert(any());
    verify(notifier).publish(any());
    assertThat(execution.metadata("checkId")).isEqualTo(5L);
    assertThat(execution.metadata("verdict")).isEqualTo("ON_TRACK");
    assertThat(execution.metadata("notes")).isEqualTo(0);
  }

  @Test
  @DisplayName("advisor.note.enabled=false 면 REFLECT 를 건너뛰고(assist 미호출) 정량·분류 노트는 그대로 저장한다")
  void noteDisabledSkipsReflect() {
    properties.getNote().setEnabled(false);
    stubAdvice842();

    AdvisorExecution execution = execution();
    job.execute(execution);

    verify(assist, never()).call(any(), any(), any(), any());
    assertThat(execution.metadata("skip.REFLECT")).asString().contains("advisor.note.enabled=false");
    assertThat(stepStatus(execution, "REFLECT")).isEqualTo("SKIPPED");
    assertThat(savedNotes().values()).hasSize(3).allMatch(n -> n.deviation() == null);
    assertThat(execution.metadata("notes")).isEqualTo(3);
    verify(notifier).publish(any());
  }

  @Test
  @DisplayName("정규 점검 창(KST 11:30~12:30) 밖의 고정 Clock 이면 노트 tags.offHours=true, run 메타 offHours, Slack 제목에 '장외 점검'")
  void offHoursByClock() {
    job = job(AFTER_CLOSE_KST);
    properties.getNote().setEnabled(false);
    stubAdvice842();

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(savedNotes().values()).hasSize(3).allMatch(n -> Boolean.TRUE.equals(n.tags().get("offHours")) && n.notedAt().equals(AFTER_CLOSE_KST));
    assertThat(execution.metadata("offHours")).isEqualTo(true);
    ArgumentCaptor<IntradayCheckMessage> message = ArgumentCaptor.forClass(IntradayCheckMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(sectionText(message.getValue().toBlocks().get(0))).contains("16:00 중간 점검 (장외 점검)");
  }

  @Test
  @DisplayName("지수 시가가 비면 그 픽은 PREV_CLOSE(전일 대비율 차·하루 σ)로 폴백하고, 픽별 기준이 섞이면 run 메타 excessBasis=MIXED·회고 입력에 픽별 basis")
  void prevCloseFallbackAndMixedBasis() {
    AdviceHeader advice = AdviceHeader.builder().adviceId(7L).baseDate(today.minusDays(1)).regimeCode(MarketRegimeCode.NEUTRAL)
        .kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.UP).build();
    when(adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.of(advice));
    when(adviceWriter.picks(7L)).thenReturn(List.of(
        PickRow.builder().ticker("111111").pickRank(1).direction(PickDirection.LONG).conviction(0.7).thesis("a").riskNote("b").build(),
        PickRow.builder().ticker("222222").pickRank(2).direction(PickDirection.LONG).conviction(0.7).thesis("c").riskNote("d").build()));
    when(adviceWriter.candidates(7L)).thenReturn(List.of(
        CandidateRow.builder().ticker("111111").stockName("코스피종목").benchIndexCode("0001").sectorCode("G1").features(Map.of("vol20", 0.02)).build(),
        CandidateRow.builder().ticker("222222").stockName("코스닥종목").benchIndexCode("1001").sectorCode("G2").features(Map.of("vol20", 0.02)).build()));
    when(marketData.fetchIndexPrice(eq("0001"), any())).thenReturn(index("2700.00", "0.50", ""));      // 시가 결손
    when(marketData.fetchIndexPrice(eq("1001"), any())).thenReturn(index("880.00", "0.20", "881.00"));
    when(marketData.fetchPrice(eq("111111"), any())).thenReturn(price("970", "-3.00", "1000", "1000"));  // 전일 대비 −3% vs KOSPI +0.5%
    when(marketData.fetchPrice(eq("222222"), any())).thenReturn(price("2100", "5.00", "2000", "2000")); // 시가 대비 +5%
    when(assist.call(any(), any(), any(), eq(PickNoteResponse.class))).thenReturn(callResult(new PickNoteResponse(List.of())));

    AdvisorExecution execution = execution();
    job.execute(execution);

    Map<String, PickNoteRow> byTicker = savedNotes();
    PickNoteRow kospiPick = byTicker.get("111111");
    assertThat(kospiPick.tags()).containsEntry("excessBasis", "PREV_CLOSE").containsEntry("benchCode", "0001").containsEntry("benchSinceOpen", null);
    assertThat(kospiPick.excessRate()).isCloseTo(-0.035, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(kospiPick.zScore()).as("하루 σ 로 나눈다").isCloseTo(-0.035 / 0.02, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(kospiPick.noteClass()).isEqualTo(PickNoteClass.IDIOSYNCRATIC);
    PickNoteRow kosdaqPick = byTicker.get("222222");
    assertThat(kosdaqPick.tags()).containsEntry("excessBasis", "OPEN");
    assertThat(kosdaqPick.excessRate()).isCloseTo(0.05 - (880.0 / 881 - 1), org.assertj.core.data.Offset.offset(1e-9));
    assertThat(kosdaqPick.noteClass()).isEqualTo(PickNoteClass.OVERSHOOT);
    assertThat(execution.metadata("excessBasis")).isEqualTo("MIXED");
    assertThat(execution.metadata("reflected")).isEqualTo(0);

    ArgumentCaptor<String> userJson = ArgumentCaptor.forClass(String.class);
    verify(assist).call(any(), userJson.capture(), any(), eq(PickNoteResponse.class));
    assertThat(userJson.getValue()).contains("\"excessBasis\":\"MIXED\"").contains("\"basis\":\"PREV_CLOSE\"").contains("\"basis\":\"OPEN\"");

    ArgumentCaptor<IntradayCheckMessage> message = ArgumentCaptor.forClass(IntradayCheckMessage.class);
    verify(notifier).publish(message.capture());
    String pickText = sectionText(message.getValue().toBlocks().get(1));
    assertThat(pickText).contains("코스피종목 전일대비 -3.0%").contains("코스닥종목 시가대비 +5.0%");
  }

  /**
   * 842 판단: 픽 4개(005930 LONG, 000660 LONG, 035420 AVOID, 999999 LONG=조회 실패), 후보 3개(vol20·섹터·secRs), 지수 2개(시가 있음).
   * 숫자는 005930 → ON_TRACK(z≈1.6, secRs 하나 음수 → secCons 0), 000660 → IDIOSYNCRATIC(z≈−2.5, secRs 전부 양수 → secCons 1), 035420 → FLAT(|z|≈0.5, secRs 없음) 이 되게 골랐다.
   */
  private void stubAdvice842() {
    AdviceHeader advice = AdviceHeader.builder().adviceId(842L).baseDate(today.minusDays(1)).regimeCode(MarketRegimeCode.RISK_ON)
        .kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.NEUTRAL).build();
    when(adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.of(advice));
    when(adviceWriter.picks(842L)).thenReturn(List.of(
        PickRow.builder().ticker("005930").pickRank(1).direction(PickDirection.LONG).conviction(0.8).thesis("모멘텀 지속").riskNote("지수 급락").build(),
        PickRow.builder().ticker("000660").pickRank(2).direction(PickDirection.LONG).conviction(0.7).thesis("외국인 순매수 지속").riskNote("수급 반전").build(),
        PickRow.builder().ticker("035420").pickRank(3).direction(PickDirection.AVOID).conviction(0.6).build(),
        PickRow.builder().ticker("999999").pickRank(4).direction(PickDirection.LONG).conviction(0.6).build()));
    when(adviceWriter.candidates(842L)).thenReturn(List.of(
        CandidateRow.builder().ticker("005930").stockName("삼성전자").benchIndexCode("0001").sectorCode("G2510")
            .features(Map.of("vol20", 0.01, "secRs5", 0.01, "secRs20", -0.02, "secRs60", 0.03)).build(),
        CandidateRow.builder().ticker("000660").stockName("SK하이닉스").benchIndexCode("0001").sectorCode("G2510")
            .features(Map.of("vol20", 0.015, "secRs5", 0.01, "secRs20", 0.02, "secRs60", 0.03)).build(),
        CandidateRow.builder().ticker("035420").stockName("NAVER").benchIndexCode("0001").sectorCode("G2520").features(Map.of("vol20", 0.02)).build()));
    when(marketData.fetchIndexPrice(eq("0001"), any())).thenReturn(index("2740.10", "0.31", "2730.00"));
    when(marketData.fetchIndexPrice(eq("1001"), any())).thenReturn(index("880.00", "-0.12", "881.00"));
    when(marketData.fetchPrice(eq("005930"), any())).thenReturn(price("71000", "1.20", "70000", "70200"));
    when(marketData.fetchPrice(eq("000660"), any())).thenReturn(price("180000", "-2.10", "184000", "183860"));
    when(marketData.fetchPrice(eq("035420"), any())).thenReturn(price("200000", "-0.50", "200500", "201000"));
    when(marketData.fetchPrice(eq("999999"), any())).thenThrow(new IllegalStateException("EGW00123"));
  }

  private IntradayCheckJob job(Instant fixedNow) {
    return new IntradayCheckJob(properties, kisProperties, marketData, calendar, adviceWriter, checkWriter, notifier, assist, notes, new PromptResources(),
        clockProvider(fixedNow));
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<Clock> clockProvider(Instant fixedNow) {
    ObjectProvider<Clock> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(Clock.fixed(fixedNow, ZoneOffset.UTC));
    return provider;
  }

  private Map<String, PickNoteRow> savedNotes() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickNoteRow>> captor = ArgumentCaptor.forClass(List.class);
    verify(notes).insertAll(captor.capture());
    return captor.getValue().stream().collect(Collectors.toMap(PickNoteRow::ticker, Function.identity()));
  }

  private static String stepStatus(AdvisorExecution execution, String step) {
    return execution.steps().stream().filter(s -> s.name().equals(step)).findFirst().orElseThrow().status();
  }

  private static String sectionText(LayoutBlock block) {
    return ((MarkdownTextObject) ((SectionBlock) block).getText()).getText();
  }

  private static MarketJudgeClient.CallResult<PickNoteResponse> callResult(PickNoteResponse response) {
    return new MarketJudgeClient.CallResult<>(response, "{}", null, 0, 0, "assist-model", "resp_1", Map.of());
  }

  private static KisIndexPriceResponse.Output index(String value, String rate, String open) {
    return new KisIndexPriceResponse.Output(value, null, null, rate, null, null, open);
  }

  private static KisPriceResponse.Output price(String value, String rate, String open, String base) {
    return new KisPriceResponse.Output(null, value, null, null, rate, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        open, null, null, base);
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(9L).jobType(AdvisorJobType.INTRADAY).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(today).build();
    return new AdvisorExecution(run, today, properties);
  }
}
