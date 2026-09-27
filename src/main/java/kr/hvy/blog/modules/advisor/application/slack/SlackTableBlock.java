package kr.hvy.blog.modules.advisor.application.slack;

import com.slack.api.model.block.LayoutBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import kr.hvy.common.core.code.base.EnumCode;

/**
 * Block Kit {@code table} 블록. slack-api-model 1.50.0 에 TableBlock 이 없어 직접 둔다.
 * <p>
 * SDK 는 {@code chat.postMessage} 의 blocks 를 {@code GsonFactory.createSnakeCase()} 로 직렬화하고, {@code GsonLayoutBlockFactory.serialize} 가
 * 런타임 타입으로 위임하므로 이 클래스의 필드가 그대로 snake_case JSON 이 된다({@code columnSettings → column_settings},
 * {@code isWrapped → is_wrapped}). 필드명을 바꾸면 Slack 이 {@code invalid_blocks} 로 거부하는데 hvy-common {@code SlackClient} 는 응답 {@code ok}
 * 를 보지 않아 조용히 사라진다 — {@code SlackTableBlockTest} 가 SDK 요청 폼으로 JSON 을 고정한다.
 * <p>
 * 셀은 {@code raw_text} 만 쓴다. {@code raw_number} 는 문서에 필드 형식이 없어(2026-09 확인) 숫자도 문자열로 넣고 정렬만 오른쪽으로 둔다.
 * 제한: 표당 행 100·행당 셀 20, 한 메시지 모든 셀 글자 합 10,000자, 메시지당 표 1개.
 */
public final class SlackTableBlock implements LayoutBlock {

  public static final String TYPE = "table";
  /** Slack 제한: 표당 최대 행 수(머리 행 포함) */
  public static final int MAX_ROWS = 100;
  /** Slack 제한: 행당 최대 셀 수 */
  public static final int MAX_COLUMNS = 20;
  /** Slack 제한: 한 메시지의 모든 셀 글자 합 */
  public static final int MAX_TOTAL_CHARS = 10_000;

  private final String type = TYPE;
  private final String blockId;
  private final List<ColumnSetting> columnSettings;
  private final List<List<Cell>> rows;

  private SlackTableBlock(List<ColumnSetting> columnSettings, List<List<Cell>> rows) {
    this.blockId = null;
    this.columnSettings = List.copyOf(columnSettings);
    this.rows = rows.stream().map(List::copyOf).toList();
  }

  /**
   * 머리 행과 본문 행으로 표를 만든다. 열 수는 {@code columns} 기준이며 모든 행이 같은 열 수여야 한다.
   * Slack 제한(행·열·글자 합)을 넘으면 발행 시 조용히 거부되므로 여기서 바로 예외로 막는다.
   */
  public static SlackTableBlock of(List<Column> columns, List<List<String>> body) {
    Objects.requireNonNull(columns, "columns");
    Objects.requireNonNull(body, "body");
    if (columns.isEmpty() || columns.size() > MAX_COLUMNS) {
      throw new IllegalArgumentException("표 열 수는 1~" + MAX_COLUMNS + " 이어야 합니다: " + columns.size());
    }
    if (body.size() + 1 > MAX_ROWS) {
      throw new IllegalArgumentException("표 행 수(머리 포함)는 " + MAX_ROWS + " 이하여야 합니다: " + (body.size() + 1));
    }
    List<List<Cell>> rows = new ArrayList<>(body.size() + 1);
    rows.add(columns.stream().map(c -> Cell.text(c.title())).toList());
    for (List<String> row : body) {
      if (row.size() != columns.size()) {
        throw new IllegalArgumentException("행의 셀 수(" + row.size() + ")가 열 수(" + columns.size() + ")와 다릅니다");
      }
      rows.add(row.stream().map(Cell::text).toList());
    }
    int chars = rows.stream().flatMap(List::stream).mapToInt(c -> c.text().length()).sum();
    if (chars > MAX_TOTAL_CHARS) {
      throw new IllegalArgumentException("표 셀 글자 합(" + chars + ")이 " + MAX_TOTAL_CHARS + " 를 넘습니다");
    }
    return new SlackTableBlock(columns.stream().map(Column::setting).toList(), rows);
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public String getBlockId() {
    return blockId;
  }

  public List<ColumnSetting> getColumnSettings() {
    return columnSettings;
  }

  public List<List<Cell>> getRows() {
    return rows;
  }

  /** 열 정의: 머리 글자, 정렬, 줄바꿈 여부 */
  public record Column(String title, Align align, boolean wrapped) {

    /** 왼쪽 정렬·줄바꿈 없음 열 */
    public static Column left(String title) {
      return new Column(title, Align.LEFT, false);
    }

    /** 오른쪽 정렬 열(숫자) */
    public static Column right(String title) {
      return new Column(title, Align.RIGHT, false);
    }

    /** 왼쪽 정렬·줄바꿈 열(긴 이름) */
    public static Column wrapped(String title) {
      return new Column(title, Align.LEFT, true);
    }

    ColumnSetting setting() {
      return new ColumnSetting(align.slackValue(), wrapped ? Boolean.TRUE : null);
    }
  }

  /** 정렬. advisor enum 규약(EnumCode, code == name)을 따르고 Slack 에는 소문자 값({@link #slackValue()})을 보낸다 */
  public enum Align implements EnumCode<String> {
    LEFT("왼쪽"), CENTER("가운데"), RIGHT("오른쪽");

    private final String desc;

    Align(String desc) {
      this.desc = desc;
    }

    @Override
    public String getCode() {
      return name();
    }

    @Override
    public String getDesc() {
      return desc;
    }

    /** column_settings.align 값 ("left" | "center" | "right") */
    public String slackValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** column_settings 원소. null 필드는 Gson 이 생략한다 */
  public record ColumnSetting(String align, Boolean isWrapped) {

  }

  /** 셀: {"type":"raw_text","text":…} */
  public record Cell(String type, String text) {

    /** raw_text 셀. null 은 "-" 로 — 빈 문자열 셀은 Slack 이 거부할 수 있다 */
    public static Cell text(String text) {
      return new Cell("raw_text", text == null || text.isEmpty() ? "-" : text);
    }
  }
}
