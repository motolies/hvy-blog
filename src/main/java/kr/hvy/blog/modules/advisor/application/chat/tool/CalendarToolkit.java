package kr.hvy.blog.modules.advisor.application.chat.tool;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.service.MarketFeatureService;
import kr.hvy.blog.modules.advisor.application.service.TradingCalendar;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 달력·신선도 도구 2종. "오늘·최근·지금" 이 들어간 질문의 첫 호출은 dataFreshness 다 — 오늘 날짜와 데이터 마지막 거래일이 다를 수 있다.
 */
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CalendarToolkit {

  static final int MAX_DAYS = 60;

  private final ToolSupport support;
  private final StockLookupReader reader;
  private final MarketFeatureService marketFeatures;
  private final AdviceWriter adviceWriter;
  private final TradingCalendar tradingCalendar;

  @Tool(name = "dataFreshness", description = "각 데이터의 최신 기준일을 확인한다: 오늘(KST), 지표가 있는 마지막 거래일, 수급·섹터·미국 지수의 기준일, 마지막 일일 판단의 기준일. "
      + "질문에 '오늘·최근·지금·요즘' 이 있거나 기준일이 애매하면 다른 도구보다 먼저 호출한다. 답변에는 반드시 기준일을 적는다.")
  public Map<String, Object> dataFreshness(ToolContext context) {
    return support.run("dataFreshness", context, () -> {
      LocalDate today = MarketClock.today();
      Map<String, Object> m = ToolJson.obj();
      m.put("today", today.toString());
      m.put("now", MarketClock.now().toLocalDateTime().withNano(0).toString());
      Optional<LocalDate> last = reader.latestMetricDate(today);
      if (last.isEmpty()) {
        m.put("lastTradingDayWithData", null);
        m.put("note", "일별 지표가 아직 없다");
        return m;
      }
      m.put("lastTradingDayWithData", last.get().toString());
      support.noteAsOf(context, last.get());
      MarketFeatures f = marketFeatures.features(last.get());
      m.put("dataAsOf", f.dataAsOf());
      Optional<AdviceHeader> advice = adviceWriter.findLatest(AdviceVariant.LIVE, today);
      if (advice.isPresent()) {
        Map<String, Object> a = ToolJson.obj();
        a.put("baseDate", advice.get().baseDate().toString());
        ToolJson.put(a, "publishedAt", advice.get().publishedAt() == null ? null : advice.get().publishedAt().toString());
        m.put("latestAdvice", a);
      }
      m.put("schedule", "국내 일봉·지표 수집 평일 18:30 KST, 일일 판단 19:30, 미국 지수 수집 06:30(T-1 마감), 아침 점검 07:30, 장중 점검 12:00");
      return m;
    });
  }

  @Tool(name = "tradingDays", description = "국내 거래일 달력. from 다음의 거래일 n개(n 지정) 또는 from~to 사이의 거래일 목록(to 지정). 적용 구간·청산일 계산에 쓴다. 최대 60일.")
  public Map<String, Object> tradingDays(
      @ToolParam(required = false, description = "시작일 yyyy-MM-dd (이 날은 제외). 생략하면 오늘") String from,
      @ToolParam(required = false, description = "종료일 yyyy-MM-dd (포함). n 을 주면 무시") String to,
      @ToolParam(required = false, description = "가져올 거래일 수 (1~60)") Integer n, ToolContext context) {
    return support.run("tradingDays", context, () -> {
      LocalDate start = ToolSupport.parseDate(from).orElse(MarketClock.today());
      List<LocalDate> days;
      LocalDate end;
      if (n != null && n > 0) {
        days = tradingCalendar.nextTradingDays(start, Math.min(n, MAX_DAYS));
        end = days.isEmpty() ? start : days.getLast();
      } else {
        end = ToolSupport.parseDate(to).orElse(start.plusDays(30));
        LocalDate finalEnd = end;
        days = tradingCalendar.nextTradingDays(start, MAX_DAYS).stream().filter(d -> !d.isAfter(finalEnd)).toList();
      }
      Map<String, Object> m = ToolJson.obj();
      m.put("from", start.toString());
      m.put("to", end.toString());
      m.put("n", days.size());
      m.put("days", days.stream().map(LocalDate::toString).toList());
      m.put("note", "휴장일 테이블에 없는 미래 날짜는 평일=개장으로 본다");
      return m;
    });
  }
}
