package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * strict 스키마 규약: 모든 객체 additionalProperties=false·전 속성 required, 후보 티커·섹터가 enum 으로 박힌다.
 */
class AdviceSchemaFactoryTest {

  @Test
  @DisplayName("후보 티커·섹터 코드가 enum 으로 들어가고 strict 규칙을 지킨다")
  @SuppressWarnings("unchecked")
  void schemaHasEnumsAndStrictShape() {
    Map<String, Object> schema = AdviceSchemaFactory.schema(List.of("005930", "000660"), List.of("G2510"));
    assertStrict(schema);
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    assertThat(props).containsKeys("regime", "sectors", "picks", "summary");
    Map<String, Object> pick = (Map<String, Object>) ((Map<String, Object>) props.get("picks")).get("items");
    Map<String, Object> pickProps = (Map<String, Object>) pick.get("properties");
    assertThat((List<String>) ((Map<String, Object>) pickProps.get("ticker")).get("enum")).containsExactly("005930", "000660");
    assertThat((List<String>) ((Map<String, Object>) pickProps.get("conviction")).get("enum")).isEqualTo(AdviceSchemaFactory.CONVICTIONS);
    Map<String, Object> sector = (Map<String, Object>) ((Map<String, Object>) props.get("sectors")).get("items");
    assertThat((List<String>) ((Map<String, Object>) ((Map<String, Object>) sector.get("properties")).get("code")).get("enum")).containsExactly("G2510");

    String json = AdviceSchemaFactory.schemaJson(List.of("005930"), List.of());
    assertThat(json).contains("\"additionalProperties\":false").contains("\"enum\":[\"005930\"]");
    Map<String, Object> noSectors = AdviceSchemaFactory.schema(List.of("005930"), List.of());
    Map<String, Object> sectorItems = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) noSectors.get("properties")).get("sectors")).get("items");
    Map<String, Object> sectorCode = (Map<String, Object>) ((Map<String, Object>) sectorItems.get("properties")).get("code");
    assertThat(sectorCode).as("섹터 코드가 없으면 자유 문자열").containsEntry("type", "string").doesNotContainKey("enum");
  }

  @SuppressWarnings("unchecked")
  private static void assertStrict(Map<String, Object> node) {
    if ("object".equals(node.get("type"))) {
      assertThat(node.get("additionalProperties")).isEqualTo(false);
      Map<String, Object> props = (Map<String, Object>) node.get("properties");
      assertThat((List<String>) node.get("required")).containsExactlyInAnyOrderElementsOf(props.keySet());
      props.values().forEach(v -> assertStrict((Map<String, Object>) v));
    } else if ("array".equals(node.get("type"))) {
      assertStrict((Map<String, Object>) node.get("items"));
    }
  }
}
