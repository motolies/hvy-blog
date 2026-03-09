package kr.hvy.blog.modules.admin.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 마스터코드 노드 이동 요청 DTO
 */
@Value
@Builder
@Jacksonized
public class MasterCodeMoveRequest {

  /**
   * 새로운 부모 노드 ID (NULL이면 루트로 이동)
   */
  Long newParentId;
}
