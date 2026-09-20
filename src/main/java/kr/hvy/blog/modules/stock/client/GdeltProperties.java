package kr.hvy.blog.modules.stock.client;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.EventTheme;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GDELT DOC 2.0 API 수집 설정 (yml {@code gdelt.*}). 키 없음. 서버가 "요청 간격 5초" 를 강제하므로(429, 2026-09-20 실측) 호출 사이 최소 간격과
 * 429 백오프가 필수다.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "gdelt")
public class GdeltProperties {

  /** DOC 2.0 엔드포인트 */
  private String baseUrl = "https://api.gdeltproject.org/api/v2/doc/doc";

  /** 연결 타임아웃(초) */
  private int connectTimeoutSeconds = 10;

  /** 응답 타임아웃(초) */
  private int timeoutSeconds = 40;

  /** artlist 테마당 최대 기사 수 (API 상한 250) */
  private int maxRecords = 60;

  /** 증분 수집 1회가 다시 받는 시계열 일수 (완결 UTC 일자 기준, LIVE 행은 덮어쓰지 않으므로 중복 요청은 무해) */
  private int timelineDays = 30;

  /** 백필 1요청 당 일수 (긴 창은 일 단위로 오지만 안전하게 쪼갠다) */
  private int backfillChunkDays = 90;

  /** 헤드라인 수집 창(시간). advisor 판단 창(36h)과 같게 */
  private int windowHours = 36;

  /** 호출 사이 최소 간격(ms). GDELT 는 5초 미만이면 429 */
  private long minIntervalMs = 6_000L;

  /** 429 재시도 횟수 */
  private int maxRetries = 3;

  /** 429 뒤 대기(ms) */
  private long retryBackoffMs = 30_000L;

  /** User-Agent (GDELT 가 대량 사용자 식별에 쓴다) */
  private String userAgent = "hvy-blog-advisor/1.0";

  /** "코드:표시명:쿼리" 목록 */
  private List<String> themes = new ArrayList<>();

  /**
   * 파싱된 테마 목록. 형식 오류·코드 중복은 기동 시점에 실패시킨다.
   */
  public List<EventTheme> themeList() {
    return EventTheme.parseAll(themes);
  }

  @PostConstruct
  void validate() {
    List<EventTheme> list = themeList();
    if (list.isEmpty()) {
      log.warn("gdelt.themes 가 비어 있어 사건 피드 수집(NEWS)이 아무 테마도 받지 않습니다");
    } else {
      log.info("GDELT 테마 {}개: {}", list.size(), list.stream().map(EventTheme::code).toList());
    }
  }
}
