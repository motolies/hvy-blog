package kr.hvy.blog.modules.stock.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

/**
 * MarketType ↔ code 컬럼 변환 (PostStatusConverter 와 같은 hvy 관례).
 */
@Converter(autoApply = true)
public class MarketTypeConverter extends AbstractEnumCodeConverter<MarketType, String> {

  protected MarketTypeConverter() {
    super(MarketType.class);
  }
}
