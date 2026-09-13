package kr.hvy.blog.modules.advisor.application.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.MetricColumn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricColumnAndToolJsonTest {

  @Test
  @DisplayName("MetricColumn.parse 는 상수명·컬럼명만 받고 그 외(인젝션 포함)는 빈 Optional — SQL 에 닿는 문자열은 enum 의 column 뿐이다")
  void parseOnlyAcceptsKnownColumns() {
    assertThat(MetricColumn.parse("ret_20d")).contains(MetricColumn.RET_20D);
    assertThat(MetricColumn.parse("RET_20D")).contains(MetricColumn.RET_20D);
    assertThat(MetricColumn.parse(" Dist_Ma20 ")).contains(MetricColumn.DIST_MA20);
    assertThat(MetricColumn.parse("ret_20d; DROP TABLE tb_stock_master")).isEmpty();
    assertThat(MetricColumn.parse("close")).isEmpty();
    assertThat(MetricColumn.parse(null)).isEmpty();
    for (MetricColumn c : MetricColumn.values()) {
      assertThat(c.getColumn()).matches("[a-z0-9_]+");
      assertThat(c.getCode()).isEqualTo(c.name());
      assertThat(c.getDesc()).isNotBlank();
    }
    assertThat(MetricColumn.allowedList()).contains("ret_20d", "tv_ratio_5_60");
  }

  @Test
  @DisplayName("ToolJson — null 은 키를 생략하고 소수는 4자리, 오류는 error/asOf 형태")
  void toolJsonShapes() {
    Map<String, Object> m = ToolJson.obj();
    ToolJson.put(m, "a", 1);
    ToolJson.put(m, "b", null);
    ToolJson.put(m, "r", ToolJson.r4(0.123456789));
    ToolJson.put(m, "p", ToolJson.r0(71250.4));
    assertThat(ToolJson.write(m)).isEqualTo("{\"a\":1,\"r\":0.1235,\"p\":71250}");
    assertThat(ToolJson.r4(Double.NaN)).isNull();
    assertThat(ToolJson.r0(null)).isNull();
    assertThat(ToolJson.write(ToolJson.noData(LocalDate.of(2026, 9, 12)))).isEqualTo("{\"error\":\"no_data\",\"asOf\":\"2026-09-12\"}");
    assertThat(ToolJson.write(ToolJson.error(ToolJson.ERROR_BAD_ARGUMENT, "모르는 컬럼", null))).isEqualTo("{\"error\":\"bad_argument\",\"message\":\"모르는 컬럼\"}");
  }
}
