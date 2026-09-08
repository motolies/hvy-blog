package kr.hvy.blog.modules.stock.client;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.stereotype.Component;

/**
 * 한국투자증권(KIS) Open API 호출 설정.
 * <p>
 * 앱키/시크릿은 환경변수(KIS_APP_KEY, KIS_APP_SECRET)로만 주입한다. 값이 없어도 앱은 기동되며,
 * 수집 잡이 실행될 때 {@link #isConfigured()} 로 실패를 조기에 알린다.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "kis")
public class KisProperties {

  /** 실전 REST 도메인 */
  private String baseUrl = "https://openapi.koreainvestment.com:9443";

  /** 앱키 (실전 계좌) */
  private String appKey;

  /** 앱시크릿 (실전 계좌) */
  private String appSecret;

  /** 고객 구분: P 개인, B 법인 */
  private String custType = "P";

  private Http http = new Http();
  private RateLimit rateLimit = new RateLimit();
  private Token token = new Token();
  private Backfill backfill = new Backfill();
  private MasterFile masterFile = new MasterFile();
  private Run run = new Run();
  private Calendar calendar = new Calendar();
  private Overseas overseas = new Overseas();
  private Stats stats = new Stats();
  private Derived derived = new Derived();

  /**
   * 앱키와 시크릿이 모두 설정되어 있는지 확인한다.
   */
  public boolean isConfigured() {
    return StringUtils.isNotBlank(appKey) && StringUtils.isNotBlank(appSecret);
  }

  /**
   * 기동 시 설정 상태를 로그로 남긴다. 시크릿 값은 절대 출력하지 않는다.
   */
  @PostConstruct
  void logStatus() {
    if (isConfigured()) {
      log.info("KIS Open API 설정 확인: baseUrl={}, rateLimit={}건/{}ms", baseUrl, rateLimit.permits, rateLimit.windowMs);
    } else {
      log.warn("KIS Open API 앱키가 설정되지 않았습니다(KIS_APP_KEY/KIS_APP_SECRET). 주식 수집 잡은 실행 시 실패합니다");
    }
  }

  @Data
  public static class Http {

    /** TCP 연결 타임아웃 */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 응답 대기 타임아웃 */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration responseTimeout = Duration.ofSeconds(15);

    /** 커넥션 풀 크기 (동시 호출 수 이상이면 충분) */
    private int maxConnections = 16;
  }

  @Data
  public static class RateLimit {

    /**
     * 고정 윈도우 길이(ms). 1초 창 하나로 잡으면 창 경계에서 순간 2배가 나가 KIS 한도(20건/초)를 넘길 수 있어
     * 200ms 로 잘게 쪼갠다. 200ms × 3건이면 임의의 1초 슬라이딩 창 최댓값이 18건으로 억제된다.
     */
    private int windowMs = 200;

    /** 윈도우당 허용 호출 수 */
    private int permits = 3;

    /** EGW00201 로 낮춘 한도를 원복하기까지 필요한 연속 성공 횟수 */
    private int recoveryStreak = 200;
  }

  @Data
  public static class Token {

    /** 만료 전 이 시간 안에 들어오면 갱신한다 (24h 토큰, 3시간 백필 도중 만료 방지) */
    @DurationUnit(ChronoUnit.MINUTES)
    private Duration refreshBuffer = Duration.ofMinutes(10);

    /** KIS 토큰 발급 최소 간격 (공식: 1분당 1회) */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration issueInterval = Duration.ofSeconds(60);
  }

  @Data
  public static class Backfill {

    /** 백필 시작일 */
    private LocalDate startDate = LocalDate.of(2015, 1, 1);

    /** 일봉 100건 ≈ 캘린더 140일. 응답이 100건 미만이면 그 구간에 휴장이 많았던 것뿐이다 */
    private int windowDays = 140;

    /** 종목당 최대 윈도우 수 (무한 루프 안전장치) */
    private int maxWindows = 120;

    /** 종목 병렬 호출 수. 레이트 리미터가 전역 게이트라 한도는 넘지 않는다 */
    private int concurrency = 3;

    /** 체크포인트 attempt_count 가 이 값을 넘으면 FAILED 로 확정하고 건너뛴다 */
    private int maxAttempts = 5;

    /** 백필 대상 증권그룹 (ST 주권). ETF 를 넣으려면 EF 추가 */
    private List<String> securityGroups = new ArrayList<>(List.of("ST"));

    /** 재개 대상을 한 번에 집어 오는 묶음 크기 */
    private int batchSize = 500;
  }

  @Data
  public static class Overseas {

    /**
     * 해외 참조 지표 심볼. 형식 "구분:거래소:심볼" — 구분 N 지수 / X 환율(FHKST03030100, 거래소 공백),
     * EQ 개별주·ETF(HHDFS76240000, 거래소 NAS/NYS/AMS). 소스 추가는 yml 행 추가로 끝난다.
     */
    private List<String> symbols = new ArrayList<>(List.of(
        "N::.DJI", "N::COMP", "N::SPX", "N::SOX", "X::FX@KRW",
        "EQ:NAS:SOXX", "EQ:NAS:SMH", "EQ:NAS:NVDA", "EQ:NAS:AMD", "EQ:NAS:MU", "EQ:NYS:TSM", "EQ:NAS:ASML",
        "EQ:NAS:TSLA", "EQ:NAS:MSFT", "EQ:NAS:GOOGL", "EQ:NAS:AMZN", "EQ:NYS:ALB", "EQ:AMS:LIT",
        "EQ:AMS:XBI", "EQ:NAS:IBB", "EQ:AMS:ITA", "EQ:NYS:LMT", "EQ:NYS:RTX", "EQ:NYS:NOC",
        "EQ:AMS:XLF", "EQ:AMS:KRE", "EQ:AMS:XLE", "EQ:NAS:BOTZ", "EQ:AMS:ROBO"));
  }

  @Data
  public static class Derived {

    /**
     * MV REFRESH 세션의 work_mem. 서버 기본 4MB 면 일봉 618만 행의 창 함수 정렬이 디스크로 넘쳐
     * mv_stock_daily_metric 이 100분을 넘겼다(2026-09-08 실측 6,294,938ms). 서버 메모리에 맞춰 조정한다.
     */
    private String workMem = "512MB";

    /**
     * true 면 REFRESH … CONCURRENTLY (갱신 중 조회 가능하지만 기존 MV 와 전체 대조라 2배 이상 느림).
     * 야간 DAILY·백필은 읽는 쪽이 없어 false 가 기본. 낮에 수동 실행하며 조회를 막고 싶지 않으면 true.
     */
    private boolean concurrently = false;

    /**
     * REFRESH 세션의 max_parallel_workers_per_gather. 병렬 해시 조인은 /dev/shm 에 동적 공유 메모리를 잡는데 Docker 기본 shm 64MB 에서는
     * "could not resize shared memory segment … No space left on device" 로 실패한다(2026-09-08 실측). 0 이면 직렬 실행(창 함수는 원래 직렬).
     * 컨테이너 shm_size 를 1GB 이상으로 올린 뒤에는 2~4 로 올려도 된다.
     */
    private int maxParallelWorkers = 0;
  }

  @Data
  public static class Stats {

    /** DAILY 파이프라인에서 P1 통계(공매도·신용·프로그램) 단계를 돌릴지. 종목당 3호출이 추가된다 */
    private boolean enabled = true;
  }

  @Data
  public static class Calendar {

    /** 휴장일 API 연속조회 페이지 상한. 한 페이지가 약 1개월 분량이라 12~15 면 1년이다 */
    private int maxPages = 15;
  }

  @Data
  public static class MasterFile {

    /** 종목 마스터 파일(kospi_code.mst.zip 등) 다운로드 기본 URL */
    private String baseUrl = "https://new.real.download.dws.co.kr/common/master";
  }

  @Data
  public static class Run {

    /**
     * 기동 시 RUNNING run 을 나이와 무관하게 전부 FAILED 로 정리한다. 단일 인스턴스에서는 기동 시점에 살아 있는 실행이
     * 있을 수 없으므로 true 가 맞다. 다중 인스턴스로 가면 false 로 두고 staleAfter 로만 정리한다.
     */
    private boolean reconcileAllOnStartup = true;

    /** reconcileAllOnStartup=false 일 때, 기동 시 이 시간보다 오래된 RUNNING run 만 FAILED 로 정리한다 */
    @DurationUnit(ChronoUnit.HOURS)
    private Duration staleAfter = Duration.ofHours(6);
  }
}
