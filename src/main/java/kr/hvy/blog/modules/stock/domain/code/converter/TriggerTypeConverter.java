package kr.hvy.blog.modules.stock.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

/**
 * TriggerType ↔ code 컬럼 변환 (PostStatusConverter 와 같은 hvy 관례).
 */
@Converter(autoApply = true)
public class TriggerTypeConverter extends AbstractEnumCodeConverter<TriggerType, String> {

  protected TriggerTypeConverter() {
    super(TriggerType.class);
  }
}
