package kr.hvy.blog.modules.stock.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

/**
 * CheckpointStatus ↔ code 컬럼 변환 (PostStatusConverter 와 같은 hvy 관례).
 */
@Converter(autoApply = true)
public class CheckpointStatusConverter extends AbstractEnumCodeConverter<CheckpointStatus, String> {

  protected CheckpointStatusConverter() {
    super(CheckpointStatus.class);
  }
}
