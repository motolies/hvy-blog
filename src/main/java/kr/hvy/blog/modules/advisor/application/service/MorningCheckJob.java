package kr.hvy.blog.modules.advisor.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.slack.MorningCheckMessage;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.MorningVerdict;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.GlobalLink;
import kr.hvy.blog.modules.advisor.domain.model.MorningCheckRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 아침 점검 (MORNING_CHECK, 평일 07:30 KST — 06:30 해외 수집 뒤, 09:00 개장 전).
 * <p>
 * 19:30 판단은 미국 당일 세션을 못 보고 국내 D+1 개장은 그것을 반영한다. 여기가 미국 정보가 전방인 유일한 구간이라, 밤사이 미국 마감 수익률에
 * 판단 기준일 기준 β(GlobalLinkService, 지수별 주 심볼)를 곱한 <b>예상 갭</b>으로 직전 영업일 LIVE 판단의 지수 방향을 유지/강화/주의로 판정한다.
 * 규칙 기반(LLM 없음)·보고 전용이며 원 판단 레코드와 픽 채점은 건드리지 않는다(채점 일관성). 결과는 tb_advisor_morning_check 에 남고 h=1 패스에서
 * D+1 시가 갭과 대조된다(CallSubject.MORNING). 휴장일·판단 없음·이미 점검·밤사이 데이터 없음이면 SKIPPED.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MorningCheckJob implements AdvisorJob {

  static final List<String> INDEX_CODES = List.of("0001", "1001");
  static final String FX_SYMBOL = "FX@KRW";
  /** 예상 갭이 없을 때(β·σ 결손) 임계 폴백 */
  static final double FALLBACK_THRESHOLD = 0.005;

  private final AdvisorProperties properties;
  private final MarketCalendarService calendar;
  private final AdviceWriter adviceWriter;
  private final GlobalLinkService links;
  private final MorningCheckWriter checkWriter;
  private final AdvisorNotifier notifier;

  /** 지수 1개의 판정 재료 */
  record IndexCheck(String indexCode, String symbol, Double beta, Double usR1, Double gapEst, double threshold, DirectionCall predicted, MorningVerdict verdict) {
  }

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.MORNING_CHECK;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    LocalDate today = execution.baseDate();
    if (!calendar.isTradingDay(today)) {
      execution.skip("휴장일 " + today);
      return;
    }
    Optional<AdviceHeader> latest = adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1));
    if (latest.isEmpty() || latest.get().baseDate().isBefore(calendar.lastTradingDayOnOrBefore(today.minusDays(1)))) {
      execution.skip("점검할 직전 영업일 LIVE 판단이 없습니다");
      return;
    }
    AdviceHeader advice = latest.get();
    if (checkWriter.findByAdvice(advice.adviceId()).isPresent()) {
      execution.skip("이미 점검한 판단입니다 (advice=" + advice.adviceId() + ")");
      return;
    }
    Map<String, String> primary = properties.getMorning().primarySymbols();
    LinkedHashSet<String> symbols = new LinkedHashSet<>(primary.values());
    for (String pair : properties.getMorning().getLinkPairs()) {
      String[] parts = pair.split(":", 2);
      if (parts.length == 2) {
        symbols.add(parts[1].trim());
      }
    }
    symbols.add(FX_SYMBOL);
    Map<String, GlobalLinkService.Overnight> overnight = links.overnight(new ArrayList<>(symbols), today);
    LocalDate usDate = primary.values().stream().map(overnight::get).filter(o -> o != null).map(GlobalLinkService.Overnight::date)
        .max(LocalDate::compareTo).orElse(null);
    if (usDate == null || ChronoUnit.DAYS.between(usDate, advice.baseDate()) > properties.getMorning().getMaxUsLagDays()) {
      execution.skip("밤사이 미국 데이터가 없습니다 (usDate=" + usDate + ", base=" + advice.baseDate() + ")");
      return;
    }
    // 미국이 판단 기준일에 휴장이었으면 밤사이 새 정보가 없다 — 유지로 기록만 한다
    boolean usClosed = usDate.isBefore(advice.baseDate());

    Map<String, Object> detail = new LinkedHashMap<>();
    Map<String, Object> usJson = new LinkedHashMap<>();
    List<String> usParts = new ArrayList<>();
    for (String symbol : symbols) {
      GlobalLinkService.Overnight o = overnight.get(symbol);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("date", o == null ? null : o.date().toString());
      m.put("r1", o == null ? null : o.r1());
      usJson.put(symbol, m);
      if (o != null && o.r1() != null && !FX_SYMBOL.equals(symbol)) {
        usParts.add(String.format("%s %+.1f%%", symbol, o.r1() * 100));
      } else if (o != null && o.r1() != null) {
        usParts.add(String.format("환율 %+.1f%%", o.r1() * 100));
      }
    }
    detail.put("us", usJson);
    detail.put("usClosed", usClosed);

    Map<String, Object> indexJson = new LinkedHashMap<>();
    List<String> indexLines = new ArrayList<>();
    Map<String, IndexCheck> checks = new LinkedHashMap<>();
    for (String code : INDEX_CODES) {
      String symbol = primary.get(code);
      GlobalLinkService.Overnight o = symbol == null ? null : overnight.get(symbol);
      GlobalLink link = symbol == null ? null : links.link(code, symbol, advice.baseDate());
      Double sigma = links.sigma1d(code, advice.baseDate());
      double threshold = sigma == null || sigma <= 0 ? FALLBACK_THRESHOLD : properties.getMorning().getSigmaMultiple() * sigma;
      Double beta = link == null ? null : link.beta();
      Double usR1 = o == null || usClosed ? null : o.r1();
      Double gapEst = beta == null || usR1 == null ? null : beta * usR1;
      DirectionCall predicted = "0001".equals(code) ? advice.kospiDir() : advice.kosdaqDir();
      MorningVerdict verdict = verdict(gapEst, threshold, predicted);
      checks.put(code, new IndexCheck(code, symbol, beta, usR1, gapEst, threshold, predicted, verdict));

      Map<String, Object> m = new LinkedHashMap<>();
      m.put("symbol", symbol);
      m.put("beta", beta);
      m.put("usR1", usR1);
      m.put("gapEst", gapEst);
      m.put("threshold", threshold);
      m.put("predicted", predicted == null ? null : predicted.getCode());
      m.put("verdict", verdict.getCode());
      indexJson.put(code, m);
      indexLines.add(indexLine(code, symbol, beta, gapEst, threshold, predicted, verdict));
    }
    detail.put("index", indexJson);
    MorningVerdict overall = checks.values().stream().map(IndexCheck::verdict).max((a, b) -> Integer.compare(a.severity(), b.severity()))
        .orElse(MorningVerdict.HOLD);
    String comment = comment(overall, usClosed, checks);

    long checkId = checkWriter.insert(MorningCheckRow.builder().adviceId(advice.adviceId()).runId(execution.runId()).baseDate(advice.baseDate())
        .usDate(usDate).gapKospi(checks.get("0001").gapEst()).gapKosdaq(checks.get("1001").gapEst()).verdict(overall).detailJson(detail).build());
    execution.putMetadata("checkId", checkId);
    execution.putMetadata("usDate", usDate.toString());
    execution.putMetadata("verdict", overall.getCode());
    execution.putMetadata("gapKospi", checks.get("0001").gapEst());
    execution.putMetadata("gapKosdaq", checks.get("1001").gapEst());

    String usLine = (usClosed ? String.format("미국 %s 마감(기준일 휴장·새 정보 없음)", usDate) : "미국 " + usDate + " 마감") + ": "
        + (usParts.isEmpty() ? "-" : String.join(" · ", usParts));
    MorningCheckMessage message = MorningCheckMessage.builder().baseDate(advice.baseDate().toString()).usLine(usLine).indexLines(indexLines)
        .verdict(overall).comment(comment).runId(execution.runId()).adviceId(advice.adviceId()).build();
    if (notifier.publish(message)) {
      checkWriter.markPublished(checkId, Instant.now());
    } else {
      execution.warn("아침 점검 Slack 발행 실패 (check=" + checkId + ")");
    }
  }

  /**
   * 판정: 예상 갭 없음·|갭| < 임계 → HOLD, 어제 방향과 같은 부호 → REINFORCE, 반대 부호(또는 NEUTRAL 예측에 큰 갭) → CAUTION.
   */
  static MorningVerdict verdict(Double gapEst, double threshold, DirectionCall predicted) {
    if (gapEst == null || Math.abs(gapEst) < threshold) {
      return MorningVerdict.HOLD;
    }
    if (predicted == null || predicted == DirectionCall.NEUTRAL) {
      return MorningVerdict.CAUTION;
    }
    boolean sameSign = predicted == DirectionCall.UP ? gapEst > 0 : gapEst < 0;
    return sameSign ? MorningVerdict.REINFORCE : MorningVerdict.CAUTION;
  }

  static String indexLine(String code, String symbol, Double beta, Double gapEst, double threshold, DirectionCall predicted, MorningVerdict verdict) {
    String name = "0001".equals(code) ? "KOSPI" : "KOSDAQ";
    String arrow = predicted == null ? "-" : switch (predicted) {
      case UP -> "▲";
      case DOWN -> "▼";
      case NEUTRAL -> "■";
    };
    if (gapEst == null) {
      return String.format("%s 예상 갭 - (β %s) → 어제 %s %s", name, beta == null ? "-" : String.format("%.2f", beta), arrow, verdict.getDesc());
    }
    return String.format("%s 예상 갭 %+.2f%% (β %.2f×%s, 임계 ±%.2f%%) → 어제 %s %s", name, gapEst * 100, beta, symbol, threshold * 100, arrow,
        verdict.getDesc());
  }

  /**
   * 규칙 기반 한 줄 코멘트.
   */
  static String comment(MorningVerdict overall, boolean usClosed, Map<String, IndexCheck> checks) {
    if (usClosed) {
      return "기준일에 미국이 휴장이라 밤사이 새 정보가 없다. 판단 유지.";
    }
    return switch (overall) {
      case HOLD -> "밤사이 미국 움직임이 임계 안. 어제 판단대로 진입.";
      case REINFORCE -> "밤사이 미국이 어제 방향을 지지. 시가 갭을 확인하되 판단 유지.";
      case CAUTION -> {
        List<String> against = new ArrayList<>();
        checks.values().stream().filter(c -> c.verdict() == MorningVerdict.CAUTION).forEach(c -> against.add("0001".equals(c.indexCode()) ? "KOSPI" : "KOSDAQ"));
        yield "밤사이 미국이 어제 방향과 어긋남(" + String.join("·", against) + ") — 역풍 갭. 시가 확인 후 진입, 픽은 채점 그대로 둔다.";
      }
    };
  }
}
