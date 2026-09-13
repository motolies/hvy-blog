package kr.hvy.blog.modules.advisor.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

/**
 * AdvisorTriggerType ↔ code 컬럼 변환 (stock 모듈 CollectJobTypeConverter 와 같은 관례).
 */
@Converter(autoApply = true)
public class AdvisorTriggerTypeConverter extends AbstractEnumCodeConverter<AdvisorTriggerType, String> {

  protected AdvisorTriggerTypeConverter() {
    super(AdvisorTriggerType.class);
  }
}
