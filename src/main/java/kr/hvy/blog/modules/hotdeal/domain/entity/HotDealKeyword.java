package kr.hvy.blog.modules.hotdeal.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import kr.hvy.common.core.security.SecurityUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * 핫딜 알림 키워드.
 *
 * <p>핫딜 제목이 이 키워드에 부분일치하면 추천/조회/댓글 임계값을 우회하여
 * 저장하고 Slack @channel 멘션 알림을 보낸다.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
    name = "uk_hot_deal_keyword_normalized", columnNames = "normalizedKeyword"))
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HotDealKeyword {

  /**
   * 제거 대상 공백 문자. ASCII 공백류에 더해 NBSP(U+00A0)와 전각 공백(U+3000)을 포함한다.
   * 스크래핑한 HTML 제목에는 &amp;nbsp;와 전각 공백이 섞여 들어오는 경우가 많다.
   */
  private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u3000]+");

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * 관리자가 입력한 원문. Slack 메시지에 "왜 알림이 왔는지" 표기할 때 사용한다.
   */
  @Column(nullable = false, length = 64)
  private String keyword;

  /**
   * 매칭 및 중복검사용 정규화 값. keyword에서만 파생되며 외부에서 직접 설정할 수 없다.
   */
  @Column(nullable = false, length = 64)
  private String normalizedKeyword;

  @Column(nullable = false)
  @Builder.Default
  private boolean enabled = true;

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
  })
  @Builder.Default
  private EventLogEntity updated = EventLogEntity.defaultValues();

  /*****************************************************************************
   * 비즈니스 로직
   *****************************************************************************/

  /**
   * 키워드를 생성한다. normalizedKeyword는 keyword에서만 파생되므로 이 팩토리를 거쳐야 한다.
   */
  public static HotDealKeyword create(String keyword, boolean enabled) {
    String trimmed = StringUtils.trimToEmpty(keyword);
    return HotDealKeyword.builder()
        .keyword(trimmed)
        .normalizedKeyword(normalize(trimmed))
        .enabled(enabled)
        .build();
  }

  public void update(String keyword, boolean enabled) {
    String trimmed = StringUtils.trimToEmpty(keyword);
    this.keyword = trimmed;
    this.normalizedKeyword = normalize(trimmed);
    this.enabled = enabled;
    // @EntityListeners를 쓰지 않으므로 수정 이력을 직접 갱신한다
    this.updated = EventLogEntity.builder()
        .at(Instant.now())
        .by(SecurityUtils.getUsername())
        .build();
  }

  /**
   * 매칭용 정규화. 소문자 변환 후 모든 공백을 제거한다.
   *
   * <p>키워드 저장 시와 제목 매칭 시가 반드시 같은 함수를 써야 하므로 이 메서드가 유일한 정규화 지점이다.
   * Locale.ROOT를 명시하는 이유는 기본 로케일이 터키어일 때 "I".toLowerCase()가 점 없는 "ı"가 되어
   * 매칭이 깨지기 때문이다.
   */
  public static String normalize(String raw) {
    if (StringUtils.isBlank(raw)) {
      return "";
    }
    return WHITESPACE.matcher(raw).replaceAll("").toLowerCase(Locale.ROOT);
  }
}
