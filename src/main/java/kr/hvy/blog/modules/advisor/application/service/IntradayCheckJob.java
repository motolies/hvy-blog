package kr.hvy.blog.modules.advisor.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.slack.IntradayCheckMessage;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.IntradayCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.IntradayCheckWriter;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import kr.hvy.blog.modules.stock.client.KisCallContext;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisIndexPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 장중 점검 (INTRADAY, 평일 12:00 KST). 직전 영업일 LIVE 판단의 지수 방향·픽 방향을 KIS 현재가(전일 대비율)와 대조해 짧게 보고한다.
 * <p>
 * 규칙 기반·보고 전용이다(LLM 호출 없음, 학습·채점에 쓰지 않음, 12:00 정보를 픽 특징에 소급하지 않음). 호출은 지수 2 + 픽 ≤10 = 12건 이하를
 * 리미터 간격으로 순차 실행하고, 종목 1건 실패는 삼키고 나머지로 판정한다. 휴장일·판단 없음·KIS 키 없음이면 SKIPPED.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class IntradayCheckJob implements AdvisorJob {

  static final double ON_TRACK_RATIO = 0.6;
  static final double OFF_TRACK_RATIO = 0.3;
  /** 지수 NEUTRAL 예측이 맞다고 볼 장중 등락률 절대값 상한(%) */
  static final double INDEX_NEUTRAL_BAND_PCT = 0.3;
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final AdvisorProperties properties;
  private final KisProperties kisProperties;
  private final KisMarketDataPort marketData;
  private final MarketCalendarService calendar;
  private final AdviceWriter adviceWriter;
  private final IntradayCheckWriter checkWriter;
  private final AdvisorNotifier notifier;

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.INTRADAY;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    LocalDate today = execution.baseDate();
    if (!calendar.isTradingDay(today)) {
      execution.skip("휴장일 " + today);
      return;
    }
    if (!kisProperties.isConfigured()) {
      execution.skip("KIS 앱키가 없어 현재가를 조회할 수 없습니다");
      return;
    }
    Optional<AdviceHeader> latest = adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1));
    if (latest.isEmpty() || latest.get().baseDate().isBefore(calendar.lastTradingDayOnOrBefore(today.minusDays(1)))) {
      execution.skip("점검할 직전 영업일 LIVE 판단이 없습니다");
      return;
    }
    AdviceHeader advice = latest.get();
    List<PickRow> picks = adviceWriter.picks(advice.adviceId());
    Map<String, CandidateRow> candidates = new LinkedHashMap<>();
    adviceWriter.candidates(advice.adviceId()).forEach(c -> candidates.put(c.ticker(), c));
    KisCallContext context = KisCallContext.of(execution.runId());

    // 지수
    Map<String, Object> indexJson = new LinkedHashMap<>();
    List<String> indexLines = new ArrayList<>();
    for (String code : List.of("0001", "1001")) {
      DirectionCall predicted = "0001".equals(code) ? advice.kospiDir() : advice.kosdaqDir();
      try {
        KisIndexPriceResponse.Output out = marketData.fetchIndexPrice(code, context);
        Double rate = parse(out == null ? null : out.changeRate());
        Boolean agree = rate == null || predicted == null ? null : indexAgrees(predicted, rate);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("price", parse(out == null ? null : out.currentValue()));
        m.put("changeRate", rate);
        m.put("predicted", predicted == null ? null : predicted.getCode());
        m.put("agree", agree);
        indexJson.put(code, m);
        indexLines.add(String.format("%s %s%s", "0001".equals(code) ? "KOSPI" : "KOSDAQ", rate == null ? "-" : String.format("%+.2f%%", rate),
            agree == null ? "" : (agree ? " ✓" : " ✗")));
      } catch (RuntimeException e) {
        execution.recordFailure("INDEX:" + code, e.toString());
        indexLines.add(("0001".equals(code) ? "KOSPI" : "KOSDAQ") + " 조회 실패");
      }
    }

    // 픽
    List<Map<String, Object>> pickJson = new ArrayList<>();
    int agreed = 0;
    int total = 0;
    for (PickRow pick : picks) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("ticker", pick.ticker());
      m.put("direction", pick.direction().getCode());
      try {
        KisPriceResponse.Output out = marketData.fetchPrice(pick.ticker(), context);
        Double rate = parse(out == null ? null : out.changeRate());
        m.put("price", parse(out == null ? null : out.currentPrice()));
        m.put("changeRate", rate);
        if (rate != null) {
          boolean agree = pick.direction() == PickDirection.LONG ? rate > 0 : rate <= 0;
          m.put("agree", agree);
          total++;
          if (agree) {
            agreed++;
          }
        }
      } catch (RuntimeException e) {
        execution.recordFailure("PICK:" + pick.ticker(), e.toString());
        m.put("error", e.getClass().getSimpleName());
      }
      pickJson.add(m);
    }
    Double ratio = total == 0 ? null : (double) agreed / total;
    IntradayVerdict verdict = ratio == null ? IntradayVerdict.MIXED : ratio >= ON_TRACK_RATIO ? IntradayVerdict.ON_TRACK
        : ratio < OFF_TRACK_RATIO ? IntradayVerdict.OFF_TRACK : IntradayVerdict.MIXED;
    String comment = comment(verdict, pickJson, candidates);
    Instant now = Instant.now();
    long checkId = checkWriter.insert(IntradayCheckRow.builder().adviceId(advice.adviceId()).runId(execution.runId()).checkedAt(now)
        .indexJson(indexJson).pickJson(pickJson).agreementRatio(ratio).verdict(verdict).comment(comment).build());
    execution.putMetadata("checkId", checkId);
    execution.putMetadata("agreement", ratio);
    execution.putMetadata("verdict", verdict.getCode());

    IntradayCheckMessage message = IntradayCheckMessage.builder().baseDate(advice.baseDate().toString())
        .checkedAt(LocalTime.now(MarketClock.KST).format(TIME)).indexLine(String.join(" / ", indexLines)).agreed(agreed).total(total)
        .verdict(verdict).comment(comment).runId(execution.runId()).adviceId(advice.adviceId()).build();
    if (!notifier.publish(message)) {
      execution.warn("장중 점검 Slack 발행 실패 (check=" + checkId + ")");
    }
  }

  /**
   * 지수 예측 일치: UP 은 양, DOWN 은 음, NEUTRAL 은 |등락률| < 0.3%.
   */
  static boolean indexAgrees(DirectionCall predicted, double ratePct) {
    return switch (predicted) {
      case UP -> ratePct > 0;
      case DOWN -> ratePct < 0;
      case NEUTRAL -> Math.abs(ratePct) < INDEX_NEUTRAL_BAND_PCT;
    };
  }

  /**
   * 규칙 기반 한 줄 코멘트: 판정 + 가장 어긋난 픽 최대 2개.
   */
  static String comment(IntradayVerdict verdict, List<Map<String, Object>> picks, Map<String, CandidateRow> candidates) {
    List<String> off = new ArrayList<>();
    for (Map<String, Object> p : picks) {
      if (Boolean.FALSE.equals(p.get("agree")) && p.get("changeRate") instanceof Double rate) {
        CandidateRow c = candidates.get(String.valueOf(p.get("ticker")));
        String name = c == null || c.stockName() == null ? String.valueOf(p.get("ticker")) : c.stockName();
        off.add(String.format("%s %+.1f%%", name, rate));
        if (off.size() == 2) {
          break;
        }
      }
    }
    String base = switch (verdict) {
      case ON_TRACK -> "판단 유지.";
      case MIXED -> "혼조 — 장 마감 채점으로 확인.";
      case OFF_TRACK -> "예측과 반대로 움직임 — 마감 후 채점 주시.";
    };
    return off.isEmpty() ? base : base + " 어긋난 픽: " + String.join(", ", off);
  }

  static Double parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
