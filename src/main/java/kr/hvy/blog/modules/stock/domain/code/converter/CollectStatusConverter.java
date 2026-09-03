package kr.hvy.blog.modules.stock.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

/**
 * CollectStatus ↔ code 컬럼 변환 (PostStatusConverter 와 같은 hvy 관례).
 */
@Converter(autoApply = true)
public class CollectStatusConverter extends AbstractEnumCodeConverter<CollectStatus, String> {

  protected CollectStatusConverter() {
    super(CollectStatus.class);
  }
}
