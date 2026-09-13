package kr.hvy.blog.modules.advisor.application.chat.tool;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatProperties;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 도구 공통 규약의 구현체. 모든 {@code @Tool} 본문은 {@link #run(String, ToolContext, Supplier)} 안에서 돈다.
 * <ul>
 *   <li>호출 이름을 {@link ChatRequestScope} 에 기록하고, 소프트 마감을 넘겼으면 SQL 없이 deadline 오류를 돌려준다(모델이 마무리하게).</li>
 *   <li>읽기 전용 트랜잭션 + {@code SET LOCAL statement_timeout} — 같은 커넥션을 쓰는 NamedParameterJdbcTemplate 서비스에도 적용된다.</li>
 *   <li>예외는 절대 밖으로 내지 않고 {@code {"error":…}} 로 바꾼다 — 예외 하나가 도구 루프를 죽여 사용자가 무응답을 받는다.</li>
 *   <li>기준일은 <b>지표 테이블의 실제 마지막 거래일</b>로 클램프한다(미래·미수집 날짜는 존재하지 않는 행이라 새어 나갈 수 없다 = 룩어헤드 불변식).</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class ToolSupport {

  static final String DEADLINE_MESSAGE = "처리 시간이 초과되어 더 이상 조회하지 않습니다. 지금까지 얻은 값으로 답하세요";

  private final TransactionTemplate readOnly;
  private final JdbcTemplate jdbc;
  private final AdvisorChatProperties properties;
  private final StockLookupReader reader;

  public ToolSupport(PlatformTransactionManager transactionManager, JdbcTemplate jdbc, AdvisorChatProperties properties, StockLookupReader reader) {
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
    this.jdbc = jdbc;
    this.properties = properties;
    this.reader = reader;
  }

  /**
   * 도구 본문 실행. 결과 맵을 그대로 돌려주고, 어떤 예외도 오류 맵으로 바꾼다.
   */
  public Map<String, Object> run(String tool, ToolContext context, Supplier<Map<String, Object>> body) {
    Optional<ChatRequestScope> scope = ChatRequestScope.from(context);
    if (scope.isPresent() && scope.get().enter(tool)) {
      log.warn("advisor chat 도구 마감 초과: tool={}", tool);
      return ToolJson.error(ToolJson.ERROR_DEADLINE, DEADLINE_MESSAGE, null);
    }
    long started = System.currentTimeMillis();
    try {
      Map<String, Object> result = readOnly.execute(status -> {
        jdbc.execute("SET LOCAL statement_timeout = " + Math.max(1, properties.getToolTimeoutSeconds()) * 1_000);
        return body.get();
      });
      log.debug("advisor chat 도구 {}: {}ms", tool, System.currentTimeMillis() - started);
      return result == null ? ToolJson.noData(null) : result;
    } catch (QueryTimeoutException e) {
      log.warn("advisor chat 도구 타임아웃: tool={}, {}ms", tool, System.currentTimeMillis() - started);
      return ToolJson.error(ToolJson.ERROR_TIMEOUT, "조회가 " + properties.getToolTimeoutSeconds() + "초를 넘겨 취소됐습니다", null);
    } catch (DataAccessException e) {
      String message = String.valueOf(e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
      if (message.contains("statement timeout")) {
        log.warn("advisor chat 도구 타임아웃: tool={}, {}ms", tool, System.currentTimeMillis() - started);
        return ToolJson.error(ToolJson.ERROR_TIMEOUT, "조회가 " + properties.getToolTimeoutSeconds() + "초를 넘겨 취소됐습니다", null);
      }
      log.warn("advisor chat 도구 DB 오류: tool={}, cause={}", tool, message);
      return ToolJson.error(ToolJson.ERROR_INTERNAL, "조회 중 오류가 났습니다", null);
    } catch (Exception e) {
      log.warn("advisor chat 도구 오류: tool={}, cause={}", tool, e.toString());
      return ToolJson.error(ToolJson.ERROR_INTERNAL, abbreviate(e.getMessage()), null);
    }
  }

  /**
   * 기준일 파라미터(yyyy-MM-dd, 생략 가능)를 지표 테이블의 마지막 거래일로 클램프한다. 미래는 오늘로 먼저 내린다. 지표가 아예 없으면 빈 Optional.
   */
  public Optional<LocalDate> asOf(String param) {
    LocalDate requested = parseDate(param).orElse(MarketClock.today());
    LocalDate today = MarketClock.today();
    if (requested.isAfter(today)) {
      requested = today;
    }
    return reader.latestMetricDate(requested);
  }

  /**
   * 도구가 참조한 기준일을 요청 범위에 남긴다(답변 data_as_of).
   */
  public void noteAsOf(ToolContext context, LocalDate asOf) {
    ChatRequestScope.from(context).ifPresent(s -> s.asOf(asOf));
  }

  /**
   * 요청 상한을 도구 고유 상한과 설정(tool-row-limit)으로 클램프한다.
   */
  public int clampLimit(Integer requested, int toolCap) {
    int cap = Math.max(1, Math.min(toolCap, properties.getToolRowLimit()));
    if (requested == null || requested <= 0) {
      return cap;
    }
    return Math.min(requested, cap);
  }

  /**
   * yyyy-MM-dd 파싱. 비었거나 형식이 틀리면 빈 Optional(호출자가 기본값을 쓴다).
   */
  public static Optional<LocalDate> parseDate(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(LocalDate.parse(value.trim()));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String abbreviate(String s) {
    if (s == null) {
      return null;
    }
    return s.length() <= 200 ? s : s.substring(0, 199) + "…";
  }
}
