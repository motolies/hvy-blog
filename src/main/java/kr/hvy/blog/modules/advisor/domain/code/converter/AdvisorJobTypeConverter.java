package kr.hvy.blog.modules.advisor.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

/**
 * AdvisorJobType ↔ code 컬럼 변환 (stock 모듈 CollectJobTypeConverter 와 같은 관례).
 */
@Converter(autoApply = true)
public class AdvisorJobTypeConverter extends AbstractEnumCodeConverter<AdvisorJobType, String> {

  protected AdvisorJobTypeConverter() {
    super(AdvisorJobType.class);
  }
}
