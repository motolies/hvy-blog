package kr.hvy.blog.modules.advisor.application;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.PickUniverse;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * AI 시장 판단(advisor) 설정. 모델 ID·단가·임계값은 전부 여기(yml `advisor.*`)에서 온다.
 * <p>
 * OpenAI 키는 Spring AI 규약대로 {@code spring.ai.openai.api-key}(env OPENAI_API_KEY) 에 두고, 이 클래스는 {@link Environment} 로
 * 존재 여부만 본다. 값이 없어도 앱은 기동되며 잡 실행 시 {@link #isConfigured()} 로 조기에 거부한다(KisProperties 와 같은 역할).
 * 피드백 기능(보정 표·교훈·가중치 갱신)은 코드로는 전부 갖추되 표본 수 게이트(lesson.min-picks, ic.min-n-eff)로 잠근다 — 4주 관측으로
 * 가중치를 바꾸는 것은 노이즈 추종이라는 2026-09-13 설계 검토 결론.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "advisor")
public class AdvisorProperties {

  static final String OPENAI_API_KEY_PROPERTY = "spring.ai.openai.api-key";

  private final Environment environment;

  /** 전체 on/off. false 면 ChatClient 빈·잡이 등록되지 않는다 */
  private boolean enabled = false;

  /**
   * DAILY(·MORNING·ADHOC) 결정 호라이즌(거래일). {@link #horizons} 에 이 키가 DAILY 로 있어야 한다(기동 검증). 다른 종류의 호라이즌은
   * {@link #decisionHorizon(AdviceKind)} 로 얻는다 — 이 값을 직접 읽는 곳은 전부 "DAILY 결정 호라이즌" 의 뜻이다(M5 전수 점검, 2026-09-25).
   */
  private int horizonDays = 5;

  /** DAILY·MORNING 판단에만 붙는 진단용 보조 호라이즌 — 학습·KPI 에 넣지 않는다. H20·H60·H180 은 자기 결정 호라이즌 하나로만 채점한다 */
  private List<Integer> diagnosticHorizons = List.of(1, 20);

  /**
   * 호라이즌(거래일) → 판단 종류·가중치 학습 여부·IC 창(M5 멀티 호라이즌, 2026-09-25). 설정 파일의 키는 이 기본 맵에 병합된다(Spring MapBinder).
   * <ul>
   *   <li>IC 는 맵의 모든 호라이즌에 저장한다 — learn=false(60·180)는 모니터링 전용이라 가중치 학습에는 쓰지 않는다(겹치는 코호트로 n_eff 가 60일 ≈23·180일 ≈7)</li>
   *   <li>learn=true 호라이즌마다 활성 가중치 세트가 하나씩 있다(uk_advisor_weight_set_active_horizon). n_eff = icWindow / h 로 같은 게이트(ic.min-n-eff)를 적용한다</li>
   *   <li>icWindow 가 비면 ic.window-days — 5일은 기존 창(120)을 그대로 따라가 하위 호환이고, 20일은 480(n_eff 24)</li>
   * </ul>
   */
  private Map<Integer, Horizon> horizons = defaultHorizons();

  /** LLM 에 넘기는 후보 종목 수 상한 (30 × 14필드 ≈ 2,500 토큰) */
  private int candidateLimit = 30;

  /** 후보 안 섹터당 최대 종목 수 (단일 테마 쏠림 방지) */
  private int maxPerSector = 4;

  /**
   * 스크리닝·rank-IC 유니버스의 시장({@link MarketType} 코드: KOSPI·KOSDAQ). 기본 KOSPI 만 — 종목 픽을 KOSPI 로 한정한 2026-09-13 결정(advice-v5).
   * 백분위 점수·IC·가중치가 같은 유니버스 CTE(FeatureSql.featureCtes)를 쓰므로 값을 바꾸면 IC_BACKFILL 을 baseDate 없이 다시 돌려 IC 를 재기준화한다.
   * 시장 국면·추세 전망(KOSPI·KOSDAQ 지수)과 섹터 지표(양시장 전체)는 이 값과 무관하다.
   */
  private List<String> markets = List.of("KOSPI");

  /**
   * 종목 픽 후보 유니버스(advice-v7, 2026-09-25). 기본 KOSPI200 — 스크리닝 백분위를 {@link #markets} 유니버스 전체로 매긴 뒤 후보에만 거른다.
   * IC·가중치·백분위 모집단은 그대로라 값을 바꿔도 IC_BACKFILL 은 필요 없다. KOSPI200 구성 이력(tb_stock_master_history)이 수집 시작일부터만 있어
   * IC 유니버스까지 좁히면 과거 구간이 현재 구성종목으로 걸러지는 룩어헤드가 된다. ALL 이면 QUANT_TOPN_BROAD 섀도는 QUANT_TOPN 과 같아 돌리지 않는다.
   */
  private PickUniverse pickUniverse = PickUniverse.KOSPI200;

  /** 가드 통과 후 픽이 이 수 미만이면 run FAILED (발행 안 함) */
  private int pickMin = 3;

  /** 픽 상한 — 초과분은 확신 내림차순으로 자른다. Slack 은 픽마다 section 1개라 36 을 넘기면 메시지 블록 50 상한에 걸린다 */
  private int pickMax = 10;

  private Advise advise = new Advise();
  private Prompt prompt = new Prompt();
  private Trend trend = new Trend();
  private Morning morning = new Morning();
  private News news = new News();
  private Scoring scoring = new Scoring();
  private Ic ic = new Ic();
  private Lesson lesson = new Lesson();
  private Shadow shadow = new Shadow();
  private Model model = new Model();
  private Cost cost = new Cost();
  private Note note = new Note();
  private Regime regime = new Regime();
  private Theme theme = new Theme();
  private H20 h20 = new H20();
  private LongTerm longTerm = new LongTerm();

  /** 프롬프트 입력 스냅샷·원본 출력 보존 일수 */
  private int retentionDays = 180;

  /**
   * OpenAI 키와 모델 ID 2종이 모두 있어야 실행 가능하다.
   */
  public boolean isConfigured() {
    return StringUtils.isNotBlank(openAiApiKey())
        && StringUtils.isNotBlank(model.getJudge())
        && StringUtils.isNotBlank(model.getAssist());
  }

  /**
   * Spring AI 규약 키(spring.ai.openai.api-key)에 실린 OpenAI 키. 없으면 빈 문자열.
   */
  public String openAiApiKey() {
    return StringUtils.defaultString(environment.getProperty(OPENAI_API_KEY_PROPERTY));
  }

  /**
   * 기동 시 설정 상태를 남긴다. 키 값은 출력하지 않는다. 시장 목록 검증은 비활성이어도 돌린다 — 잘못된 설정은 켜기 전에 드러나는 편이 낫다.
   */
  @PostConstruct
  void logStatus() {
    validateMarkets();
    validateHorizons();
    validateRegime();
    validateH20();
    validateLongTerm();
    if (!enabled) {
      log.info("advisor 비활성(advisor.enabled=false) — AI 판단 잡·ChatClient 미등록");
      return;
    }
    if (!diagnosticHorizons.contains(trend.getScoreHorizonDays())) {
      log.warn("advisor.trend.score-horizon-days={} 가 diagnostic-horizons={} 에 없어 추세 전망(TREND) 채점이 영원히 돌지 않습니다",
          trend.getScoreHorizonDays(), diagnosticHorizons);
    }
    if (isConfigured()) {
      log.info("advisor 설정 확인: judge={}, assist={}, horizon={}일(IC {}·학습 {}), markets={}, pickUniverse={}, candidates={}, picks={}~{}, trend=[{}..{}] confirm {}일",
          model.getJudge(), model.getAssist(), horizonDays, icHorizons(), learnHorizons(), markets, pickUniverse, candidateLimit, pickMin, pickMax, trend.getBearThreshold(),
          trend.getBullThreshold(), trend.getConfirmDays());
    } else {
      log.warn("advisor 가 켜져 있으나 OpenAI 키(OPENAI_API_KEY) 또는 모델 ID(ADVISOR_JUDGE_MODEL/ADVISOR_ASSIST_MODEL)가 비어 있어 잡 실행 시 거부됩니다");
    }
  }

  /**
   * advisor.markets 검증·정규화(trim·대문자). 비어 있으면 SQL `IN ()` 문법 오류로, 모르는 코드면 유니버스 0 → 판단 FAILED 로 런타임에야 드러나므로
   * 기동 시점에 거부한다.
   */
  void validateMarkets() {
    if (markets == null || markets.isEmpty()) {
      throw new IllegalStateException("advisor.markets 가 비어 있습니다 — KOSPI, KOSDAQ 중 하나 이상을 지정하세요");
    }
    List<String> normalized = markets.stream().map(m -> m == null ? "" : m.trim().toUpperCase()).toList();
    for (String code : normalized) {
      if (Arrays.stream(MarketType.values()).noneMatch(t -> t.getCode().equals(code))) {
        throw new IllegalStateException("advisor.markets 에 모르는 시장 코드 '" + code + "' — 허용: KOSPI, KOSDAQ");
      }
    }
    markets = normalized;
  }

  /**
   * 기본 호라이즌 맵: 5 DAILY 학습(창 = ic.window-days), 20 H20 학습(창 480), 60 H60·180 H180 모니터링.
   */
  static Map<Integer, Horizon> defaultHorizons() {
    Map<Integer, Horizon> map = new LinkedHashMap<>();
    map.put(5, new Horizon(AdviceKind.DAILY, true, null));
    map.put(20, new Horizon(AdviceKind.H20, true, 480));
    map.put(60, new Horizon(AdviceKind.H60, false, null));
    map.put(180, new Horizon(AdviceKind.H180, false, null));
    return map;
  }

  /**
   * advisor.horizons 검증. horizon-days 키가 DAILY 로 있어야 하고, 종류는 호라이즌마다 달라야 하며(MORNING·ADHOC 는 DAILY 창을 공유하므로 맵에 두지 않는다),
   * icWindow 는 호라이즌 이상이어야 한다(n_eff ≥ 1). 잘못된 맵은 채점·IC 가 조용히 어긋나므로 기동 시점에 거부한다.
   */
  void validateHorizons() {
    if (horizons == null || horizons.isEmpty()) {
      throw new IllegalStateException("advisor.horizons 가 비어 있습니다 — 최소 " + horizonDays + ": {kind: DAILY} 가 필요합니다");
    }
    Horizon daily = horizons.get(horizonDays);
    if (daily == null || daily.getKind() != AdviceKind.DAILY) {
      throw new IllegalStateException("advisor.horizons 에 결정 호라이즌 " + horizonDays + " 이 DAILY 로 있어야 합니다 (advisor.horizon-days 와 같은 키)");
    }
    Set<AdviceKind> seen = EnumSet.noneOf(AdviceKind.class);
    for (Map.Entry<Integer, Horizon> e : horizons.entrySet()) {
      Integer h = e.getKey();
      Horizon spec = e.getValue();
      if (h == null || h <= 0) {
        throw new IllegalStateException("advisor.horizons 의 키는 양의 거래일이어야 합니다: " + h);
      }
      if (spec == null || spec.getKind() == null) {
        throw new IllegalStateException("advisor.horizons." + h + ".kind 가 비어 있습니다");
      }
      if (spec.getKind() == AdviceKind.MORNING || spec.getKind() == AdviceKind.ADHOC) {
        throw new IllegalStateException("advisor.horizons." + h + ".kind=" + spec.getKind() + " 는 허용되지 않습니다 — MORNING·ADHOC 는 DAILY 호라이즌을 공유합니다");
      }
      if (!seen.add(spec.getKind())) {
        throw new IllegalStateException("advisor.horizons 에 종류 " + spec.getKind() + " 가 두 번 이상 있습니다");
      }
      if (spec.getIcWindow() != null && spec.getIcWindow() < h) {
        throw new IllegalStateException("advisor.horizons." + h + ".ic-window=" + spec.getIcWindow() + " 가 호라이즌보다 작습니다 (n_eff < 1)");
      }
    }
  }

  /**
   * advisor.regime 검증(M6). 백분위 임계는 0 ≤ low &lt; high ≤ 1, 정책 표의 확신 상한은 허용 이산값 범위 [0.55, 0.90], 감산·가산은 음수가 아니어야 한다.
   * 정책 표는 사전 등록 대상이라 잘못된 값이 조용히 적용되느니 기동을 거부한다.
   */
  void validateRegime() {
    if (regime.getVolLowPct() < 0 || regime.getVolHighPct() > 1 || regime.getVolLowPct() >= regime.getVolHighPct()) {
      throw new IllegalStateException("advisor.regime.vol-low-pct(" + regime.getVolLowPct() + ") < vol-high-pct(" + regime.getVolHighPct()
          + ") 이고 둘 다 [0, 1] 이어야 합니다");
    }
    if (regime.getVolWindowDays() < 2 || regime.getVolLookbackYears() <= 0) {
      throw new IllegalStateException("advisor.regime.vol-window-days ≥ 2, vol-lookback-years > 0 이어야 합니다");
    }
    RegimePolicyTable policy = regime.getPolicy();
    if (StringUtils.isBlank(policy.getVersion())) {
      throw new IllegalStateException("advisor.regime.policy.version 이 비어 있습니다 — 정책 표 수치는 버전과 함께 사전 등록한다");
    }
    if (policy.getVolHighConvictionPenalty() < 0) {
      throw new IllegalStateException("advisor.regime.policy.vol-high-conviction-penalty 는 음수일 수 없습니다");
    }
    for (Map.Entry<String, RegimeRule> e : Map.of("bull", policy.getBull(), "sideways", policy.getSideways(), "bear", policy.getBear()).entrySet()) {
      RegimeRule rule = e.getValue();
      if (rule == null) {
        throw new IllegalStateException("advisor.regime.policy." + e.getKey() + " 가 비어 있습니다");
      }
      if (rule.getLongMaxReduction() < 0) {
        throw new IllegalStateException("advisor.regime.policy." + e.getKey() + ".long-max-reduction 은 음수일 수 없습니다");
      }
      if (rule.getConvictionCap() != null && (rule.getConvictionCap() < 0.55 || rule.getConvictionCap() > 0.90)) {
        throw new IllegalStateException("advisor.regime.policy." + e.getKey() + ".conviction-cap 은 [0.55, 0.90] 이어야 합니다: " + rule.getConvictionCap());
      }
      if (rule.getAvoidMax() != null && rule.getAvoidMax() < 0) {
        throw new IllegalStateException("advisor.regime.policy." + e.getKey() + ".avoid-max 는 음수일 수 없습니다");
      }
    }
  }

  /**
   * advisor.h20 검증(M7). 1 ≤ pick-min ≤ pick-max ≤ 36 — 36 을 넘으면 Slack 픽 section 이 메시지 블록 50 상한에 걸린다(pick-max 와 같은 한계).
   */
  void validateH20() {
    if (h20.getPickMin() < 1 || h20.getPickMin() > h20.getPickMax() || h20.getPickMax() > 36) {
      throw new IllegalStateException("advisor.h20 은 1 ≤ pick-min(" + h20.getPickMin() + ") ≤ pick-max(" + h20.getPickMax() + ") ≤ 36 이어야 합니다");
    }
  }

  /**
   * advisor.long-term 검증(M8). 가중치 키는 장기 팩터(SignalCode.longTerm)만, 값은 0 이상이고 합이 양수여야 한다 — 사전 고정 가중치라 오타가 조용히
   * "팩터 하나 빠진 점수" 가 되느니 기동을 거부한다. 창은 skip < lookback, 변동성 최소 표본 ≤ 창, 커버리지는 (0, 1], 픽 수 ≤ 후보 수 ≤ 36(Slack).
   */
  void validateLongTerm() {
    LongTerm lt = longTerm;
    if (lt.getWeights() == null || lt.getWeights().isEmpty()) {
      throw new IllegalStateException("advisor.long-term.weights 가 비어 있습니다");
    }
    Set<String> allowed = new java.util.HashSet<>();
    kr.hvy.blog.modules.advisor.domain.code.SignalCode.longTerm().forEach(c -> allowed.add(c.getCode()));
    double sum = 0;
    for (Map.Entry<String, Double> e : lt.getWeights().entrySet()) {
      if (!allowed.contains(e.getKey())) {
        throw new IllegalStateException("advisor.long-term.weights 에 장기 팩터가 아닌 키 '" + e.getKey() + "' — 허용: " + allowed);
      }
      if (e.getValue() == null || e.getValue() < 0) {
        throw new IllegalStateException("advisor.long-term.weights." + e.getKey() + " 는 0 이상이어야 합니다");
      }
      sum += e.getValue();
    }
    if (sum <= 0) {
      throw new IllegalStateException("advisor.long-term.weights 의 합이 0 입니다");
    }
    if (lt.getMomSkipDays() < 1 || lt.getMomSkipDays() >= lt.getMomLookbackDays()) {
      throw new IllegalStateException("advisor.long-term 은 1 ≤ mom-skip-days < mom-lookback-days 여야 합니다");
    }
    if (lt.getVolMinDays() < 2 || lt.getVolMinDays() > lt.getVolWindowDays()) {
      throw new IllegalStateException("advisor.long-term 은 2 ≤ vol-min-days ≤ vol-window-days 여야 합니다");
    }
    if (lt.getMinCoverage() <= 0 || lt.getMinCoverage() > 1) {
      throw new IllegalStateException("advisor.long-term.min-coverage 는 (0, 1] 이어야 합니다: " + lt.getMinCoverage());
    }
    if (lt.getPickMin() < 1 || lt.getPickMin() > lt.getPickCount() || lt.getPickCount() > lt.getCandidateLimit() || lt.getPickCount() > 36
        || lt.getMaxPerSector() < 1) {
      throw new IllegalStateException("advisor.long-term 은 1 ≤ pick-min ≤ pick-count ≤ candidate-limit, pick-count ≤ 36, max-per-sector ≥ 1 이어야 합니다");
    }
    if (!"Y".equals(lt.getFinancialPeriodType()) && !"Q".equals(lt.getFinancialPeriodType())) {
      throw new IllegalStateException("advisor.long-term.financial-period-type 은 Y 또는 Q: " + lt.getFinancialPeriodType());
    }
  }

  /**
   * 판단 종류의 결정 호라이즌. MORNING(저녁과 같은 창)·ADHOC(일일 판단과 같은 창)은 DAILY 와 같다. 맵에 없는 종류는 empty.
   */
  public OptionalInt horizonOf(AdviceKind kind) {
    if (kind == AdviceKind.DAILY || kind == AdviceKind.MORNING || kind == AdviceKind.ADHOC) {
      return OptionalInt.of(horizonDays);
    }
    return horizons.entrySet().stream()
        .filter(e -> e.getValue() != null && e.getValue().getKind() == kind)
        .mapToInt(Map.Entry::getKey)
        .findFirst();
  }

  /**
   * {@link #horizonOf} 의 필수 버전 — 맵에 없는 종류면 IllegalStateException.
   */
  public int decisionHorizon(AdviceKind kind) {
    return horizonOf(kind).orElseThrow(() -> new IllegalStateException("advisor.horizons 에 " + kind + " 호라이즌이 없습니다"));
  }

  /** 가중치를 학습하는 호라이즌 (오름차순, 결정 호라이즌 5 포함) */
  public List<Integer> learnHorizons() {
    return horizons.entrySet().stream().filter(e -> e.getValue().isLearn()).map(Map.Entry::getKey).sorted().toList();
  }

  /** IC 를 저장하는 호라이즌 = 맵 전체 (오름차순). learn=false 는 모니터링 전용 */
  public List<Integer> icHorizons() {
    return horizons.keySet().stream().sorted().toList();
  }

  /** 가중치 학습 대상 호라이즌인지 */
  public boolean isLearnHorizon(int h) {
    Horizon spec = horizons.get(h);
    return spec != null && spec.isLearn();
  }

  /**
   * 호라이즌 h 의 IC 집계 창(영업일). 맵의 icWindow 가 비면 ic.window-days.
   */
  public int icWindowDays(int h) {
    Horizon spec = horizons.get(h);
    return spec != null && spec.getIcWindow() != null ? spec.getIcWindow() : ic.getWindowDays();
  }

  /**
   * 판단 종류가 채점되는 호라이즌 목록. DAILY·MORNING 은 결정 + 진단(1·20), H20·H60·H180 은 자기 결정 호라이즌 하나(맵에 없으면 없음),
   * ADHOC 는 평가 루프 밖이라 없다. ScoreJob 이 종류마다 자기 호라이즌 행만 만들게 하는 단일 규칙이다.
   */
  public List<Integer> scoreHorizons(AdviceKind kind) {
    if (kind == AdviceKind.ADHOC) {
      return List.of();
    }
    if (kind == AdviceKind.DAILY || kind == AdviceKind.MORNING) {
      List<Integer> result = new ArrayList<>();
      result.add(horizonDays);
      diagnosticHorizons.stream().filter(h -> !result.contains(h)).forEach(result::add);
      return result;
    }
    OptionalInt h = horizonOf(kind);
    return h.isPresent() ? List.of(h.getAsInt()) : List.of();
  }

  /**
   * 호라이즌 1개의 설정. kind 는 그 호라이즌으로 발행하는 판단 종류, learn 은 가중치 학습 여부, icWindow 는 IC 집계 창(비면 ic.window-days).
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Horizon {

    private AdviceKind kind;

    /** true 면 WEEKLY_REVIEW·IC_BACKFILL 이 이 호라이즌의 가중치 세트를 갱신한다 */
    private boolean learn;

    /** IC 집계 창(영업일). n_eff = icWindow / h */
    private Integer icWindow;
  }

  @Data
  public static class Advise {

    /** DAILY 수집 완료를 기다리는 마감 시각(KST). 이후에도 미완료면 SKIPPED + #hvy-error */
    private LocalTime deadline = LocalTime.of(19, 55);

    /**
     * advice-v6 섹터 규칙의 기계 클램프: 후보 secCons=0(소속 업종 지수가 1주·1개월·3개월 중 하나라도 시장에 미달) 또는 overheated 섹터의 LONG 픽 확신 상한.
     * 프롬프트 문구만으로는 준수가 보장되지 않아 AdviceGuard 가 내리고 stats capNonConsistent/capOverheated 로 준수율을 관찰한다. CONVICTIONS 값 중 하나여야 한다
     */
    private double nonConsistentConvictionCap = 0.70;

    /** 섹터 과열(overheated) 판정: 업종 지수 5일 수익률 > 이 배수 × σ_5d(KOSPI, market.sigma5d) — 모멘텀 crash·평균회귀 경고 플래그 */
    private double overheatedSigma = 2.0;
  }

  @Data
  public static class Prompt {

    /** 프롬프트 리소스 버전. 파일을 고치면 같이 올린다 (advice·run 에 저장) */
    private String version = "advice-v1";

    /** 사용자 메시지(JSON) 길이 상한. 넘으면 후보를 뒤에서 잘라내고 run 메타에 경고 (≈8k 토큰) */
    private int maxInputChars = 26_000;
  }

  /**
   * 규칙 기반 중기 추세(강세·보합·약세) 판정 손잡이. 성분 5개(종가/MA20, MA20/MA60, MA60/MA120, 60일 수익률, MA20 상회 비율) 각 -1/0/+1 의 합으로
   * 판정하며 임계는 전부 여기서 온다. 배포 후 10년 라벨 분포(목표 강세≈40/보합≈35/약세≈25%)를 보고 조정한다.
   */
  @Data
  public static class Trend {

    /** 성분 합이 이 값 이상이면 BULL */
    private int bullThreshold = 2;

    /** 성분 합이 이 값 이하면 BEAR */
    private int bearThreshold = -2;

    /** 60일 수익률 성분 컷 (±) */
    private double ret60Threshold = 0.05;

    /** MA20 상회 종목 비율이 이 값 이상이면 +1 */
    private double breadthHigh = 0.60;

    /** 이 값 이하면 -1 */
    private double breadthLow = 0.40;

    /** 전환 확인에 필요한 연속 거래일 수 (휩소 방지) */
    private int confirmDays = 2;

    /** 추세 전망(TREND·TREND_INV) 채점 호라이즌. diagnostic-horizons 에 포함돼야 한다 (기동 시 검증) */
    private int scoreHorizonDays = 20;

    /** TREND_INV 적중 판정: 무효화 발동일과 전환일의 허용 거리(거래일) */
    private int invalidationToleranceDays = 2;
  }

  /**
   * 미국 연동(advice-v3): 판단 입력 market.link 의 β·상관 쌍과 07:30 아침 점검(MORNING_CHECK) 판정 손잡이.
   */
  @Data
  public static class Morning {

    /** 국내 지수:미국 심볼 쌍. 지수별 첫 쌍이 아침 점검의 예상 갭에 쓰는 주 심볼이다 */
    private List<String> linkPairs = List.of("0001:SPX", "0001:SOX", "1001:COMP", "1001:SOX");

    /** β·상관 추정 창(국내 거래일) */
    private int linkWindowDays = 60;

    /** 판정 임계 = 이 배수 × σ_1d(직전 sigma-lookback-days). |예상 갭| 이 미만이면 HOLD */
    private double sigmaMultiple = 1.0;

    /** 미국 데이터가 판단 기준일보다 이 캘린더일 이상 오래됐으면(휴장·수집 실패) 점검을 SKIPPED 로 닫는다 */
    private int maxUsLagDays = 4;

    /**
     * 아침 재판정(MORNING_ADVISE) 발행 마감(KST). 이 시각 이후에 돌면 SKIPPED — 개장(09:00) 뒤 발행은 D+1 시가 진입 규약과 모순되고,
     * 동시호가(08:30~)에 주문을 낼 여유도 필요하다
     */
    private LocalTime adviseDeadline = LocalTime.of(8, 50);

    /**
     * 아침 재판정 트리거 플래그의 섹터 연동 심볼 임계(σ 배수). 심볼 자신의 직전 sigma-lookback-days 1일 수익률 σ 대비 |r1| 이 이 배수 이상이면 플래그.
     * 플래그는 LLM 호출 여부를 가르지 않고 diff_json 에만 남는다(트리거일/비트리거일 사후 분석)
     */
    private double sectorSigmaMultiple = 2.0;

    /**
     * 지수 코드 → 주 심볼 (linkPairs 의 첫 쌍).
     */
    public Map<String, String> primarySymbols() {
      Map<String, String> primary = new java.util.LinkedHashMap<>();
      for (String pair : linkPairs) {
        String[] parts = pair.split(":", 2);
        if (parts.length == 2) {
          primary.putIfAbsent(parts[0].trim(), parts[1].trim());
        }
      }
      return primary;
    }
  }

  /**
   * 뉴스 입력(advice-v4): tb_stock_news 의 제목을 판단 시각 이전 창에서 골라 프롬프트 news 블록으로 넣는다. 기본 off — 원천은 GDELT 헤드라인(2026-09-20, KIS 제거)이며
   * 켤지는 계획 Phase 1d(구조화 사건 주입)에서 결정한다.
   */
  @Data
  public static class News {

    /** false 면 news 블록·citedNews 스키마·LLM_NONEWS 섀도 전부 없음(AdviseJob 호출 수 그대로) */
    private boolean enabled = false;

    /** 판단 시각부터 거슬러 올라가는 창(시간). 36 = 전일 마감 후 기사 포함, 이미 소화된 48h 는 제외 */
    private int windowHours = 36;

    /** 종목 태그 없는 시장 헤드라인 상한 */
    private int marketLimit = 12;

    /** 후보 종목당 헤드라인 상한 */
    private int perTickerLimit = 3;

    /** 전체 헤드라인 상한 */
    private int totalLimit = 40;

    /** news 블록 JSON 자 상한. 넘으면 후보별 → 시장 순으로 줄인다(후보 행을 잘라내기 전에) */
    private int maxChars = 7_000;

    /** 프롬프트에 넣는 제목 최대 길이(자) */
    private int titleChars = 120;

    /** 판단 마감 시각(KST) — 사후 재실행(baseDate=)에서 이 시각 이후 기사가 새어 들지 않게 상한으로 쓴다 */
    private LocalTime cutoff = LocalTime.of(20, 0);
  }

  @Data
  public static class Scoring {

    /** 왕복 거래비용(bp). 보고 전용 — 학습·IC 는 총수익을 쓴다 */
    private int costBps = 30;

    /** 국면 적중 밴드 = bandSigma × σ_5d (직전 60일 ret_1d 표준편차 × √5). KOSPI 기준 ≈ ±1% */
    private double bandSigma = 0.5;

    /** σ 추정에 쓰는 직전 영업일 수 */
    private int sigmaLookbackDays = 60;
  }

  @Data
  public static class Ic {

    /** rank-IC 집계 창(영업일). n_eff = window / horizon */
    private int windowDays = 120;

    /** 가중치 배수의 기준 IC (raw = ĪC / ref). 사전 추정 후 시그널 평균 IC 로 교체 */
    private double referenceIc = 0.03;

    /** 축소 추정의 prior 표본 수: m̂ = 1 + n_eff/(n_eff+prior)·(raw−1) */
    private int priorNEff = 24;

    /** 이보다 n_eff 가 작으면 가중치 세트를 갱신하지 않는다 */
    private int minNEff = 24;

    /** 배수 하한·상한 */
    private double multiplierMin = 0.5;
    private double multiplierMax = 2.0;

    /** 사전 추정(IC_BACKFILL) 시작일 */
    private String backfillFrom = "2020-01-01";

    /**
     * 증분 계산(ADVISE·WEEKLY_REVIEW)이 감당할 최대 공백(캘린더일). 마지막 IC 행부터의 공백이 이보다 길면 최근 이 일수만 계산하고
     * 나머지는 경고로 남긴다(IC_BACKFILL?baseDate= 로 보충). IC 행이 없는 첫 ADVISE 가 2020 년부터 6년치를 SQL 한 번에 돌던 2026-09-13 결함 방지.
     */
    private int incrementalMaxDays = 45;
  }

  @Data
  public static class Lesson {

    /** 프롬프트에 넣는 활성 교훈 상한 */
    private int activeLimit = 8;

    /** 실적 블록·보정 표·교훈을 켜는 누적 픽 수 게이트 */
    private int minPicks = 300;

    /** 교훈 후보의 셀 최소 표본 */
    private int minCellSamples = 20;

    /** 교훈 활성화에 필요한 |t| */
    private double minTStat = 2.0;

    /** 활성 후 이 주 수(또는 적용 픽 20건) 뒤 적용−비적용 ≤ 0 이면 폐기 */
    private int reviewWeeks = 4;

    /** 폐기 판정에 쓰는 적용 픽 수 */
    private int reviewPicks = 20;
  }

  @Data
  public static class Shadow {

    /** 정량 top-N 섀도(LLM 없음)의 N */
    private int quantTopN = 7;

    /** 메모리 활성 후 메모리 없는 LLM 호출을 병행하는 주 수 */
    private int nomemWeeks = 8;

    /** 뉴스 입력이 켜진 첫 판단부터 뉴스 없는 LLM 섀도(LLM_NONEWS)를 병행하는 주 수 — 뉴스는 백테스트가 불가능해 이 섀도가 유일한 측정이다 */
    private int nonewsWeeks = 8;

    /** 주간 재현성 측정: 직전 LIVE 입력을 동결한 채 재실행하는 횟수 (0 이면 끔). 픽 집합 Jaccard < 0.7 이면 보고에 경고 */
    private int reproducibilityRuns = 3;
  }

  @Data
  public static class Model {

    /** OpenAiResponsesClient 재시도 횟수(429·408·409·5xx·네트워크, Retry-After ≤30s). 소진되면 그날 판단을 건너뛴다(부분 추천 금지) */
    private int maxRetries = 3;

    /** 호출 1건 응답 타임아웃(초, openAiRestClient). 추론 모델은 수십 초가 걸릴 수 있다 */
    private int timeoutSeconds = 120;

    /** 판단용 상위 모델 ID (env ADVISOR_JUDGE_MODEL) */
    private String judge;

    /** 판단 모델 출력 상한 = Responses max_output_tokens(추론 토큰 포함). 잘리면 MarketJudgeClient 가 LENGTH 로 예외 */
    private int judgeMaxCompletionTokens = 8_000;

    /** 판단 모델 temperature. 추론 모델은 받지 않으므로 null 이면 설정하지 않는다 */
    private Double judgeTemperature;

    /** 채점 요약·교훈 생성용 저가 모델 ID (env ADVISOR_ASSIST_MODEL) */
    private String assist;

    /** 보조 모델 출력 상한 = Responses max_output_tokens(추론 토큰 포함) */
    private int assistMaxCompletionTokens = 2_000;

    private Double assistTemperature;
  }

  @Data
  public static class Cost {

    /** 입력 100만 토큰당 USD (0 이면 비용 미계산) */
    private BigDecimal inputPer1mUsd = BigDecimal.ZERO;

    /** 출력 100만 토큰당 USD (추론 토큰 포함) */
    private BigDecimal outputPer1mUsd = BigDecimal.ZERO;
  }

  /**
   * 12:00 픽 노트(오답노트, note-v1 2026-09-21). INTRADAY 가 픽별 편차를 정량·분류하고 FLAT 이 아닌 픽만 보조 모델에 회고를 물어 tb_advisor_pick_note 에 남긴다.
   * 다음 판단(ADVISE)에는 T+5 채점으로 확정된 결정론 빈도표(recentOutcomes)만 들어가고 LLM 회고 문장은 기록·보고용이다(사용자 결정 ①).
   */
  @Data
  public static class Note {

    /** false 면 회고 LLM 호출(REFLECT)을 건너뛴다. 정량·분류 노트는 그대로 저장된다(무료·결정론) */
    private boolean enabled = true;

    /** 분류 임계 |z|. z = 지수 대비 초과 / (vol20 × √(3/6.5)) — 미만이면 FLAT 로 회고를 생략한다 */
    private double zThreshold = 1.0;

    /** 회고 호출 1건에 넣는 픽 상한 (|z| 큰 순) */
    private int maxReflectPicks = 10;

    /** recentOutcomes 집계 창(거래일) — 이 안의 base_date 확정 노트만 */
    private int windowTradingDays = 20;

    /** 확정 노트가 이보다 적으면 recentOutcomes 블록을 프롬프트에 넣지 않는다 */
    private int minFinalized = 10;

    /** recentOutcomes 빈도표 최대 행 수 (class × secCons 중 n 상위) */
    private int maxRows = 5;

    /**
     * recentOutcomes 확정 마감 시각(KST) — 기준일의 이 시각 이후에 확정(finalized_at)·관측(noted_at)된 노트는 넣지 않는다.
     * 사후 재실행(?baseDate=)에서 미래 확정이 새어 들지 않게 하는 상한으로, advisor.news.cutoff 와 같은 뜻이다(bitemporal)
     */
    private LocalTime cutoff = LocalTime.of(20, 0);
  }

  /**
   * 합성 국면(M6, 2026-09-25): 규칙 추세 × 변동성 국면(지수 σ20 의 과거 분포 백분위) → 사전 등록 정책 표. 결과는 tb_advisor_advice.regime_json 과
   * 프롬프트 regime 블록에 실리고, 정책 표 한도는 AdviceGuard·MorningAdviceGuard 가 기계적으로 강제한다.
   */
  @Data
  public static class Regime {

    /** 국면 기준 지수 — 픽 유니버스(KOSPI)의 벤치 */
    private String indexCode = "0001";

    /** σ 창(거래일). σ20 = 직전 20거래일 ret_1d 표본 표준편차 */
    private int volWindowDays = 20;

    /** 백분위 분포의 과거 창(년). 기준일 이전 σ 값만 쓴다(룩어헤드 없음). 이력이 짧으면 가능한 범위 전부 */
    private int volLookbackYears = 5;

    /** 분포 표본이 이보다 적으면 변동성 국면 UNKNOWN (정책의 고변동 가산 없음) */
    private int volMinHistoryDays = 250;

    /** 백분위가 이 값 미만이면 LOW */
    private double volLowPct = 0.30;

    /** 백분위가 이 값 이상이면 HIGH */
    private double volHighPct = 0.80;

    private RegimePolicyTable policy = new RegimePolicyTable();
  }

  /**
   * 사전 등록 정책 표(regime-policy-v1). **수치를 바꾸면 version 을 올린다(새 버전)** — 판단마다 regime_json.policy.version 이 남아 사후에 표 버전별로 분리한다.
   * 근거는 M0 측정 전이라 보수 규칙만 둔다: 약세장에서 LONG 을 줄이고 확신을 낮추며 AVOID 여지를 넓힌다. 국면별 가중치(계획 M6 (b))는 M0 검정 뒤로 미룬다.
   */
  @Data
  public static class RegimePolicyTable {

    /** 정책 표 버전 (regime_json·guard_json 에 기록) */
    private String version = "regime-policy-v1";

    /** 강세: 기존 상한 그대로 */
    private RegimeRule bull = new RegimeRule(0, null, null);

    /** 보합: LONG 확신 0.80 상한 */
    private RegimeRule sideways = new RegimeRule(0, 0.80, null);

    /** 약세: LONG 상한 −2(pick-min 존중), 확신 0.70 상한, AVOID 최대 4 */
    private RegimeRule bear = new RegimeRule(2, 0.70, 4);

    /** 변동성 HIGH 면 확신 상한을 이만큼 더 낮춘다(상한이 없던 국면은 허용 최댓값에서 뺀다) */
    private double volHighConvictionPenalty = 0.05;
  }

  /**
   * 추세 라벨 1개의 정책 행. longMaxReduction 은 pick-max 에서 빼는 수(결과는 pick-min 이상), convictionCap 이 null 이면 상한 없음, avoidMax 가 null 이면 기존 AVOID 상한(2).
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RegimeRule {

    private int longMaxReduction;

    private Double convictionCap;

    private Integer avoidMax;
  }

  /**
   * 테마 블록(M6): 기준일에 KOSPI200 구성(PIT, tb_stock_master_history)인 종목을 kospi200_sector 대분류별로 집계한다. CUSTOM 매핑은 보류(2026-09-25 결정).
   */
  @Data
  public static class Theme {

    /** 강약 판정 임계: rs20(중앙값 수익률 − KOSPI) 이 +이 값 이상이고 rs60 &gt; 0 이면 STRONG, −이 값 이하이고 rs60 &lt; 0 이면 WEAK */
    private double strongRs20 = 0.02;

    /** 지표가 있는 구성 종목이 이보다 적은 대분류는 뺀다(중앙값이 한두 종목에 좌우) */
    private int minMembers = 3;

    /** 대분류마다 싣는 대표 종목명 수(60일 평균 거래대금 상위) — 코드 의미를 LLM 이 추정하는 단서 */
    private int leaders = 3;
  }

  /**
   * 주간 20거래일 판단(M7, advice-h20-v1). 입력은 추세·섹터·테마·국면 중심이고 뉴스·오답노트·recentOutcomes 메모리는 싣지 않는다(20일 보유에 36시간 헤드라인·T+5 빈도표는
   * 창이 맞지 않는다). 가중치는 H20 활성 세트만 — 없으면 SKIP(DAILY 세트 폴백 금지). 정책 표(RegimePolicy)는 아래 픽 범위로 다시 계산해 같은 표를 적용한다.
   * <p>
   * 사전 등록 판정(26주 뒤): 같은 기준일 H20 LIVE − QUANT_TOPN 픽 평균 초과수익(h=20)의 날짜 대응 차이 평균이 t ≥ 2 이면 LLM 선택을 유지, 아니면 QUANT_TOPN 규칙으로 대체를 검토한다.
   * 주 1회 × 26주 = 대응 26쌍이고 창이 겹치므로(20거래일 보유, 5거래일 간격 → 겹침 배수 4) se 는 표본 sd / √(n/4) 로 본다(운영 문서 stock-advisor.md §H20).
   */
  @Data
  public static class H20 {

    /** 가드 통과 픽이 이보다 적으면 FAILED(미발행) */
    private int pickMin = 3;

    /** 픽 상한 — 초과분은 확신 내림차순으로 자른다. 주 1회 20일 보유라 DAILY(10) 보다 좁게 둔다 */
    private int pickMax = 8;
  }

  /**
   * 장기(H60·H180) 규칙 선택(M8). 60·180거래일은 겹치는 코호트로 n_eff 가 ≈23·≈7 이라 IC 학습이 불가능하므로 **사전 고정 가중치**로 장기 팩터의 횡단면 백분위를 가중합한다.
   * LLM(advice-longterm-v1)은 확정된 상위 N 의 종목별 thesis/risk 서술만 쓰고 선택·순위는 바꿀 수 없다(LongTermNarrativeGuard 가 강제).
   * <ul>
   *   <li>백분위 모집단: advisor.markets 유니버스 전체(스크리닝과 같은 관례) → 후보 필터: advisor.pick-universe(KOSPI200 PIT) → 섹터당 max-per-sector</li>
   *   <li>결측: 팩터 값이 없으면 중립(기여 0). 값이 있는 팩터의 가중치 합 / 전체 가중치 합(커버리지)이 min-coverage 미만이면 후보에서 뺀다</li>
   *   <li>재무 팩터: tb_stock_financial 의 financial-period-type(기본 Y 연간) 최신 결산기, available_from ≤ 기준일 AND first_seen_at(KST 날짜) ≤ 기준일 — bitemporal PIT</li>
   *   <li>판정: 60일 격주·180일 월간 표본으로는 2년 안에 판정할 수 없다 — Slack·KPI 에 "판정 불가: 표본 부족, 2년 이상 필요" 를 붙인다</li>
   * </ul>
   * 가중치·창을 바꾸면 사후 분리를 위해 prompt 버전과 별개로 guard_json.weights 스냅샷이 판단마다 남는다.
   */
  @Data
  public static class LongTerm {

    /** 팩터 코드 → 사전 고정 가중치 (합으로 정규화). 키는 SignalCode.longTerm() 만 */
    private Map<String, Double> weights = defaultLongTermWeights();

    /** 12-1 모멘텀의 긴 창(거래일): ret_lookback − ret_skip */
    private int momLookbackDays = 250;

    /** 12-1 모멘텀에서 빼는 최근 창(거래일) — 단기 반전 효과 제거 */
    private int momSkipDays = 20;

    /** 저변동 팩터의 창(거래일): 직전 창 ret_1d 표본 표준편차 */
    private int volWindowDays = 60;

    /** 저변동 팩터 최소 표본(거래일). 미만이면 결측 */
    private int volMinDays = 45;

    /** 재무 팩터 결산 구분: Y 연간(기본, 종목 간 비교 가능) | Q 분기 */
    private String financialPeriodType = "Y";

    /** 값이 있는 팩터의 가중치 비율 하한 — 미만이면 후보 제외 */
    private double minCoverage = 0.6;

    /** 후보(프롬프트·후보 스냅샷) 상한 — 픽−후보군 평가의 모집단 */
    private int candidateLimit = 30;

    /** 픽 수 = 점수 상위 N */
    private int pickCount = 10;

    /** 후보가 이보다 적으면 FAILED(미발행) */
    private int pickMin = 3;

    /** 후보 안 섹터당 최대 종목 수 */
    private int maxPerSector = 3;

    /** 기본 가중치: MOM_12_1 0.30, QUALITY_ROE 0.20, QUALITY_DEBT 0.15, OP_GROWTH 0.15, LOW_VOL_60 0.20 */
    static Map<String, Double> defaultLongTermWeights() {
      Map<String, Double> w = new LinkedHashMap<>();
      w.put("MOM_12_1", 0.30);
      w.put("QUALITY_ROE", 0.20);
      w.put("QUALITY_DEBT", 0.15);
      w.put("OP_GROWTH", 0.15);
      w.put("LOW_VOL_60", 0.20);
      return w;
    }
  }
}
