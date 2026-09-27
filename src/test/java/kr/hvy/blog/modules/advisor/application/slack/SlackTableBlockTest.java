package kr.hvy.blog.modules.advisor.application.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slack.api.methods.RequestFormBuilder;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.model.block.LayoutBlock;
import java.util.Collections;
import java.util.List;
import okhttp3.FormBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * table 블록 직렬화 규약: SDK 가 실제로 쓰는 요청 폼 경로(RequestFormBuilder → snake_case Gson)로 type·column_settings·rows·셀 type/text 가 나온다.
 */
class SlackTableBlockTest {

  /** hvy-common SlackClient 와 같은 방식으로 요청을 만들고 SDK 폼의 blocks 값을 꺼낸다 */
  static String blocksJson(List<LayoutBlock> blocks) {
    ChatPostMessageRequest request = ChatPostMessageRequest.builder().channel("#test").text("fallback").blocks(blocks).build();
    FormBody form = RequestFormBuilder.toForm(request).build();
    for (int i = 0; i < form.size(); i++) {
      if ("blocks".equals(form.name(i))) {
        return form.value(i);
      }
    }
    throw new AssertionError("요청 폼에 blocks 가 없습니다");
  }

  @Test
  @DisplayName("SDK 요청 폼 직렬화: type=table, column_settings(align·is_wrapped), rows 의 raw_text 셀")
  void serializesThroughSdkForm() {
    SlackTableBlock table = SlackTableBlock.of(
        List.of(SlackTableBlock.Column.right("순위"), SlackTableBlock.Column.wrapped("종목명"), SlackTableBlock.Column.right("확신")),
        List.of(List.of("1", "삼성전자", "0.80"), List.of("2", "SK하이닉스", "0.72")));

    String json = blocksJson(List.of(table));
    JsonObject block = JsonParser.parseString(json).getAsJsonArray().get(0).getAsJsonObject();

    assertThat(block.get("type").getAsString()).isEqualTo("table");
    assertThat(block.has("block_id")).isFalse();
    assertThat(block.has("columnSettings")).isFalse();
    JsonArray settings = block.getAsJsonArray("column_settings");
    assertThat(settings).hasSize(3);
    assertThat(settings.get(0).getAsJsonObject().get("align").getAsString()).isEqualTo("right");
    assertThat(settings.get(0).getAsJsonObject().has("is_wrapped")).isFalse();
    assertThat(settings.get(1).getAsJsonObject().get("is_wrapped").getAsBoolean()).isTrue();
    JsonArray rows = block.getAsJsonArray("rows");
    assertThat(rows).hasSize(3);
    JsonObject cell = rows.get(1).getAsJsonArray().get(1).getAsJsonObject();
    assertThat(cell.get("type").getAsString()).isEqualTo("raw_text");
    assertThat(cell.get("text").getAsString()).isEqualTo("삼성전자");
    assertThat(cell.keySet()).containsExactlyInAnyOrder("type", "text");
    assertThat(json).startsWith("[{\"type\":\"table\",\"column_settings\":[{\"align\":\"right\"}");
  }

  @Test
  @DisplayName("다른 블록과 섞여도 런타임 타입으로 직렬화된다(인터페이스 필드만 나오지 않는다)")
  void serializesAmongOtherBlocks() {
    SlackTableBlock table = SlackTableBlock.of(List.of(SlackTableBlock.Column.left("a")), List.of(List.of("x")));
    List<LayoutBlock> blocks = List.of(com.slack.api.model.block.Blocks.divider(), table);

    JsonArray arr = JsonParser.parseString(blocksJson(blocks)).getAsJsonArray();

    assertThat(arr.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("divider");
    assertThat(arr.get(1).getAsJsonObject().getAsJsonArray("rows")).hasSize(2);
  }

  @Test
  @DisplayName("빈 셀은 \"-\" 로 채운다")
  void blankCellBecomesDash() {
    SlackTableBlock table = SlackTableBlock.of(List.of(SlackTableBlock.Column.left("a")), List.of(Collections.singletonList(null)));

    assertThat(table.getRows().get(1).getFirst().text()).isEqualTo("-");
  }

  @Test
  @DisplayName("Slack 제한 위반(열 수 불일치·글자 합 초과·행 초과)은 즉시 예외")
  void rejectsLimitViolations() {
    List<SlackTableBlock.Column> one = List.of(SlackTableBlock.Column.left("a"));
    assertThatThrownBy(() -> SlackTableBlock.of(one, List.of(List.of("x", "y")))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SlackTableBlock.of(one, List.of(List.of("x".repeat(10_000))))).isInstanceOf(IllegalArgumentException.class);
    List<List<String>> tooMany = Collections.nCopies(SlackTableBlock.MAX_ROWS, List.of("x"));
    assertThatThrownBy(() -> SlackTableBlock.of(one, tooMany)).isInstanceOf(IllegalArgumentException.class);
  }
}
