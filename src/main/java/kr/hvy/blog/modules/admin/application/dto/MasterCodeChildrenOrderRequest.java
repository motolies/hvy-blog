package kr.hvy.blog.modules.admin.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 자식 노드 정렬순서 일괄 변경 요청 DTO.
 * <p>
 * <b>배열의 위치가 곧 sort 다</b>(0번째 → sort 1). 클라이언트가 sort 숫자를 계산해 보내지 않는 이유는,
 * 그렇게 하면 형제마다 PUT 이 하나씩 필요해져 중간에 실패했을 때 순서가 반쯤 어긋난 상태로 남기 때문이다.
 */
@Value
@Builder
@Jacksonized
public class MasterCodeChildrenOrderRequest {

  /**
   * 새 순서대로 나열한 자식 노드 ID 목록
   */
  @NotEmpty(message = "정렬 대상 ID 목록은 비어 있을 수 없습니다")
  @Size(max = 500, message = "한 번에 정렬할 수 있는 노드는 500개까지입니다")
  List<@NotBlank(message = "정렬 대상 ID 는 공백일 수 없습니다") String> orderedIds;
}
