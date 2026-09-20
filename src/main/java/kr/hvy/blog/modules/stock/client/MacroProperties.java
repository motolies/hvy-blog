package kr.hvy.blog.modules.stock.client;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.MacroSeriesSpec;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 거시 위험 지표(VIX·미국 국채 수익률) 수집 설정 (yml {@code macro.*}). 키가 필요 없는 공개 CSV 만 원천으로 두므로 시크릿이 없다.
 * <p>
 * 라이브 원천 = 백필 원천 불변식: 시리즈마다 원천 URL 이 하나이고, 증분과 백필이 같은 URL 을 쓴다.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "macro")
public class MacroProperties {

  /** 연결 타임아웃(초) */
  private int connectTimeoutSeconds = 10;

  /** 응답 타임아웃(초). CBOE 전체 이력 CSV 가 500KB 안팎이다 */
  private int timeoutSeconds = 30;

  /** 증분 수집이 다시 받는 최근 캘린더일 수 (정정·지연 게시 흡수) */
  private int lookbackDays = 10;

  /** 백필 기본 시작일 (startDate 없이 백필을 부를 때) */
  private LocalDate backfillFrom = LocalDate.of(2015, 1, 1);

  /**
   * 관측일 → 알 수 있었던 날(available_from) 지연(캘린더일). CBOE·재무부는 마감 당일 게시라 다음 날부터 안 것으로 본다.
   * 원천이 익영업일 게시(FRED 등)면 더 크게 둬야 하며, 그런 원천은 라이브 원천으로 쓰지 않는다.
   */
  private int availableLagDays = 1;

  /** 요청 User-Agent (공개 CSV 서버가 빈 UA 를 거부하는 경우 대비) */
  private String userAgent = "hvy-blog-advisor/1.0";

  /** "시리즈:원천:URL" 목록. 재무부 URL 은 {year} 를 연도로 치환한다 */
  private List<String> series = new ArrayList<>();

  /**
   * 파싱된 시리즈 목록. 형식 오류는 기동 시점에 실패시킨다.
   */
  public List<MacroSeriesSpec> specs() {
    return MacroSeriesSpec.parseAll(series);
  }

  /**
   * 기동 시 형식 검증·로그. 시리즈가 비어 있으면 잡은 아무것도 하지 않는다(경고만).
   */
  @PostConstruct
  void validate() {
    List<MacroSeriesSpec> specs = specs();
    if (specs.isEmpty()) {
      log.warn("macro.series 가 비어 있어 거시 지표 수집(MACRO)이 아무 시리즈도 받지 않습니다");
    } else {
      log.info("거시 지표 시리즈 {}개: {}", specs.size(), specs.stream().map(s -> s.series().getCode() + "@" + s.source().getCode()).toList());
    }
  }
}
