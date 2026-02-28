package kr.hvy.blog.modules.hotdeal.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

@Converter(autoApply = true)
public class DealSiteCodeConverter extends AbstractEnumCodeConverter<DealSiteCode, String> {

  protected DealSiteCodeConverter() {
    super(DealSiteCode.class);
  }
}
