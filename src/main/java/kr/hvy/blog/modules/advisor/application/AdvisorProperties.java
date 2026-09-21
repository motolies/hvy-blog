package kr.hvy.blog.modules.advisor.application;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import lombok.Data;
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

  /** 결정 호라이즌(거래일). 채점·KPI·학습은 이 값 하나만 쓴다 (T+1·T+20 은 진단 저장만) */
  private int horizonDays = 5;

  /** 진단용 보조 호라이즌 — 학습·KPI 에 넣지 않는다 */
  private List<Integer> diagnosticHorizons = List.of(1, 20);

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
    if (!enabled) {
      log.info("advisor 비활성(advisor.enabled=false) — AI 판단 잡·ChatClient 미등록");
      return;
    }
    if (!diagnosticHorizons.contains(trend.getScoreHorizonDays())) {
      log.warn("advisor.trend.score-horizon-days={} 가 diagnostic-horizons={} 에 없어 추세 전망(TREND) 채점이 영원히 돌지 않습니다",
          trend.getScoreHorizonDays(), diagnosticHorizons);
    }
    if (isConfigured()) {
      log.info("advisor 설정 확인: judge={}, assist={}, horizon={}일, markets={}, candidates={}, picks={}~{}, trend=[{}..{}] confirm {}일",
          model.getJudge(), model.getAssist(), horizonDays, markets, candidateLimit, pickMin, pickMax, trend.getBearThreshold(),
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
}
