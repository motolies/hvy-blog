package kr.hvy.blog.modules.advisor.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

/**
 * AdvisorStatus ↔ code 컬럼 변환 (stock 모듈 CollectJobTypeConverter 와 같은 관례).
 */
@Converter(autoApply = true)
public class AdvisorStatusConverter extends AbstractEnumCodeConverter<AdvisorStatus, String> {

  protected AdvisorStatusConverter() {
    super(AdvisorStatus.class);
  }
}
