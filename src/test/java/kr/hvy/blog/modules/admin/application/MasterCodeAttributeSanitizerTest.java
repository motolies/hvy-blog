package kr.hvy.blog.modules.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeTreeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MasterCodeAttributeSanitizer")
class MasterCodeAttributeSanitizerTest {

  private final MasterCodeAttributeSanitizer sanitizer = new MasterCodeAttributeSanitizer();

  @Test
  @DisplayName("스키마에 공개로 정의된 key만 남기고 sensitive/미정의 key는 제거한다 (자식 재귀 포함)")
  void sanitize_keepsPublicKeys_removesSensitiveAndUnknown() {
    // Given: 루트 스키마는 url(공개), secret(sensitive=true)을 정의
    List<Map<String, String>> schema = List.of(
        Map.of("key", "url", "label", "URL", "type", "text"),
        Map.of("key", "secret", "label", "Secret", "type", "text", "sensitive", "true"));

    MasterCodeTreeResponse child = MasterCodeTreeResponse.builder()
        .id(2L).code("CHILD").name("자식")
        .attributes(mutableMap("url", "child-url", "secret", "child-secret"))
        .attributeSchema(List.of())
        .children(List.of())
        .build();

    MasterCodeTreeResponse root = MasterCodeTreeResponse.builder()
        .id(1L).code("FAVORITE").name("즐겨찾기")
        .attributes(mutableMap("url", "root-url", "secret", "root-secret", "unknown", "x"))
        .attributeSchema(schema)
        .children(List.of(child))
        .build();

    // When
    List<MasterCodeTreeResponse> result = sanitizer.sanitizeTrees(List.of(root));

    // Then: 공개 key(url)만 유지, sensitive(secret)·미정의(unknown) 제거
    MasterCodeTreeResponse sanitizedRoot = result.get(0);
    assertThat(sanitizedRoot.getAttributes()).containsOnlyKeys("url");
    assertThat(sanitizedRoot.getAttributes()).containsEntry("url", "root-url");
    // attributeSchema는 공개 응답에서 제거
    assertThat(sanitizedRoot.getAttributeSchema()).isEmpty();
    // 자식도 동일 규칙 적용
    assertThat(sanitizedRoot.getChildren().get(0).getAttributes()).containsOnlyKeys("url");
    assertThat(sanitizedRoot.getChildren().get(0).getAttributes()).containsEntry("url", "child-url");
  }

  @Test
  @DisplayName("스키마가 없으면 모든 attribute를 제거한다 (fail-closed)")
  void sanitize_emptySchema_stripsAllAttributes() {
    // Given: 스키마 미정의(CLAUDE 루트처럼) + 토큰 attribute
    MasterCodeTreeResponse account = MasterCodeTreeResponse.builder()
        .id(20L).code("acc1").name("계정1")
        .attributes(mutableMap("refreshToken", "rt", "accessToken", "at"))
        .attributeSchema(List.of())
        .children(List.of())
        .build();

    MasterCodeTreeResponse root = MasterCodeTreeResponse.builder()
        .id(10L).code("CLAUDE").name("Claude")
        .attributes(mutableMap("refreshToken", "root-rt"))
        .attributeSchema(List.of()) // 공개 key 없음
        .children(List.of(account))
        .build();

    // When
    List<MasterCodeTreeResponse> result = sanitizer.sanitizeTrees(List.of(root));

    // Then: 토큰 등 모든 attribute 제거
    assertThat(result.get(0).getAttributes()).isEmpty();
    assertThat(result.get(0).getChildren().get(0).getAttributes()).isEmpty();
  }

  @Test
  @DisplayName("입력 DTO(캐시 객체)를 변경하지 않는다")
  void sanitize_doesNotMutateInput() {
    // Given
    List<Map<String, String>> schema = List.of(Map.of("key", "url"));
    Map<String, Object> originalAttrs = mutableMap("url", "u", "secret", "s");
    MasterCodeTreeResponse root = MasterCodeTreeResponse.builder()
        .id(1L).code("FAVORITE").name("즐겨찾기")
        .attributes(originalAttrs)
        .attributeSchema(schema)
        .children(List.of())
        .build();

    // When
    sanitizer.sanitizeTrees(List.of(root));

    // Then: 원본 attributes는 그대로(secret 유지)
    assertThat(originalAttrs).containsKeys("url", "secret");
    assertThat(root.getAttributes()).containsEntry("secret", "s");
  }

  @Test
  @DisplayName("null 입력은 빈 리스트를 반환한다")
  void sanitize_nullInput_returnsEmptyList() {
    assertThat(sanitizer.sanitizeTrees(null)).isEmpty();
  }

  private static Map<String, Object> mutableMap(String... kv) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put(kv[i], kv[i + 1]);
    }
    return map;
  }
}
