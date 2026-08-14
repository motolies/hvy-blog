package kr.hvy.blog.modules.hotdeal.application.specification;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealKeyword;
import kr.hvy.common.core.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 키워드 형식 검증.
 *
 * <p>중복 검사는 Repository 접근이 필요하므로 Service에서 별도로 수행한다.
 */
public class HotDealKeywordSpecification implements Specification<String> {

  /**
   * 정규화 후 최소 길이. 1자 키워드는 부분일치 특성상 무관한 제목까지 걸려 @channel 알림이 폭증한다.
   */
  private static final int MIN_NORMALIZED_LENGTH = 2;
  private static final int MAX_NORMALIZED_LENGTH = 64;

  private final List<String> errorMessages = new ArrayList<>();

  @Override
  public boolean isSatisfiedBy(String keyword) {
    errorMessages.clear();

    String normalized = HotDealKeyword.normalize(keyword);

    if (StringUtils.isBlank(normalized)) {
      errorMessages.add("키워드는 공백만으로 구성될 수 없습니다.");
    } else if (normalized.length() < MIN_NORMALIZED_LENGTH) {
      errorMessages.add("키워드는 공백 제거 기준 2자 이상이어야 합니다.");
    } else if (normalized.length() > MAX_NORMALIZED_LENGTH) {
      errorMessages.add("키워드는 64자를 초과할 수 없습니다.");
    }

    return CollectionUtils.isEmpty(errorMessages);
  }

  @Override
  public String getErrorMessage() {
    return String.join(", ", errorMessages);
  }
}
