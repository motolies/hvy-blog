package kr.hvy.blog.modules.advisor.application;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
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

  /** 가드 통과 후 픽이 이 수 미만이면 run FAILED (발행 안 함) */
  private int pickMin = 3;

  /** 픽 상한 — 초과분은 확신 내림차순으로 자른다 */
  private int pickMax = 10;

  private Advise advise = new Advise();
  private Prompt prompt = new Prompt();
  private Scoring scoring = new Scoring();
  private Ic ic = new Ic();
  private Lesson lesson = new Lesson();
  private Shadow shadow = new Shadow();
  private Model model = new Model();
  private Cost cost = new Cost();

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
   * 기동 시 설정 상태를 남긴다. 키 값은 출력하지 않는다.
   */
  @PostConstruct
  void logStatus() {
    if (!enabled) {
      log.info("advisor 비활성(advisor.enabled=false) — AI 판단 잡·ChatClient 미등록");
      return;
    }
    if (isConfigured()) {
      log.info("advisor 설정 확인: judge={}, assist={}, horizon={}일, candidates={}, picks={}~{}",
          model.getJudge(), model.getAssist(), horizonDays, candidateLimit, pickMin, pickMax);
    } else {
      log.warn("advisor 가 켜져 있으나 OpenAI 키(OPENAI_API_KEY) 또는 모델 ID(ADVISOR_JUDGE_MODEL/ADVISOR_ASSIST_MODEL)가 비어 있어 잡 실행 시 거부됩니다");
    }
  }

  @Data
  public static class Advise {

    /** DAILY 수집 완료를 기다리는 마감 시각(KST). 이후에도 미완료면 SKIPPED + #hvy-error */
    private LocalTime deadline = LocalTime.of(19, 55);
  }

  @Data
  public static class Prompt {

    /** 프롬프트 리소스 버전. 파일을 고치면 같이 올린다 (advice·run 에 저장) */
    private String version = "advice-v1";

    /** 사용자 메시지(JSON) 길이 상한. 넘으면 후보를 뒤에서 잘라내고 run 메타에 경고 (≈8k 토큰) */
    private int maxInputChars = 26_000;
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
  }

  @Data
  public static class Model {

    /** OpenAI SDK 재시도 횟수(429·5xx·타임아웃). 소진되면 그날 판단을 건너뛴다(부분 추천 금지) */
    private int maxRetries = 3;

    /** 호출 1건 타임아웃(초). 추론 모델은 수십 초가 걸릴 수 있다 */
    private int timeoutSeconds = 120;

    /** 판단용 상위 모델 ID (env ADVISOR_JUDGE_MODEL) */
    private String judge;

    /** 판단 모델 출력 상한(추론 토큰 포함) */
    private int judgeMaxCompletionTokens = 8_000;

    /** 판단 모델 temperature. 추론 모델은 받지 않으므로 null 이면 설정하지 않는다 */
    private Double judgeTemperature;

    /** 채점 요약·교훈 생성용 저가 모델 ID (env ADVISOR_ASSIST_MODEL) */
    private String assist;

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
}
