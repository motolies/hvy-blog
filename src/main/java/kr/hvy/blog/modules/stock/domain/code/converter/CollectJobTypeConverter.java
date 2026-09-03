package kr.hvy.blog.modules.stock.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

/**
 * CollectJobType ↔ code 컬럼 변환 (PostStatusConverter 와 같은 hvy 관례).
 */
@Converter(autoApply = true)
public class CollectJobTypeConverter extends AbstractEnumCodeConverter<CollectJobType, String> {

  protected CollectJobTypeConverter() {
    super(CollectJobType.class);
  }
}
