package kr.hvy.blog.modules.advisor.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteStatus;
import kr.hvy.blog.modules.advisor.domain.model.PickNoteRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.PickNoteRepository;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 프롬프트 recentOutcomes 블록(note-v1, 2026-09-21): 최근 N 거래일 픽의 12:00 편차 분류(class)와 T+5 확정 결과를 {@code class × secCons} 빈도표로 요약한다.
 * <ul>
 *   <li>원천은 {@link PickNoteRepository#findFinalized} — (advice, ticker) 별 가장 이른 확정(CONFIRMED|REFUTED) 노트, finalized_at·noted_at 이 기준일 cutoff(KST) 이하만.
 *       사후 재실행(?baseDate=)에서 미래 확정이 새지 않는다(bitemporal, advisor.news.cutoff 와 같은 규율)</li>
 *   <li>창은 기준일 앞 {@code advisor.note.window-trading-days} 거래일(기준일 당일 노트는 12:00 점검이 다음 날이라 아직 없다)</li>
 *   <li>확정 노트가 {@code min-finalized} 미만이면 블록을 만들지 않는다(empty). 행은 n 내림차순 상위 {@code max-rows}</li>
 *   <li>코드가 만든 결정론 표만 넣는다 — LLM 회고 문장(deviation·why·hypothesis)·OPEN 노트는 어떤 경로로도 프롬프트에 들어가지 않는다(사용자 결정 ①)</li>
 * </ul>
 * 형식: {@code {windowTradingDays, finalizedAsOf, columns:[class,secCons,n,confirmRate,meanFinalExcess,se,underpowered], rows:[[…]]}}.
 * confirmRate = CONFIRMED/n, meanFinalExcess = 평균 T+5 초과(소수), se = 표본 표준편차/√n(n<2 면 null), underpowered = n < 30.
 */
@Service
@RequiredArgsConstructor
public class RecentOutcomesService {

  static final List<String> COLUMNS = List.of("class", "secCons", "n", "confirmRate", "meanFinalExcess", "se", "underpowered");
  /** 이보다 표본이 적은 행은 underpowered=true — 프롬프트가 참고만 하도록 */
  static final int UNDERPOWERED_N = 30;
  /** secCons 태그가 없는(업종 지수 없음) 노트의 표기 */
  static final String SEC_CONS_UNKNOWN = "-";

  private final PickNoteRepository notes;
  private final TradingCalendar calendar;
  private final AdvisorProperties properties;

  /**
   * 기준일 판단에 넣을 recentOutcomes 블록. 확정 노트가 게이트(min-finalized) 미만이면 empty.
   */
  public Optional<Map<String, Object>> block(LocalDate baseDate) {
    AdvisorProperties.Note cfg = properties.getNote();
    LocalDate from = calendar.previousTradingDays(baseDate, cfg.getWindowTradingDays()).getLast();
    Instant cutoff = ZonedDateTime.of(baseDate, cfg.getCutoff(), MarketClock.KST).toInstant();
    List<PickNoteRow> finalized = notes.findFinalized(from, baseDate, cutoff);
    if (finalized.size() < cfg.getMinFinalized()) {
      return Optional.empty();
    }
    return Optional.of(build(finalized, cfg.getWindowTradingDays(), baseDate, cfg.getMaxRows()));
  }

  /**
   * 빈도표 조립(순수 함수): class × secCons 로 묶어 n·confirmRate·meanFinalExcess·se·underpowered 를 계산하고 n 내림차순(동률은 class·secCons 순) 상위 maxRows 행.
   */
  static Map<String, Object> build(List<PickNoteRow> finalized, int windowTradingDays, LocalDate finalizedAsOf, int maxRows) {
    Map<String, List<PickNoteRow>> groups = new LinkedHashMap<>();
    for (PickNoteRow row : finalized) {
      if (row.noteClass() == null) {
        continue;
      }
      groups.computeIfAbsent(row.noteClass().getCode() + "|" + secConsLabel(row), k -> new ArrayList<>()).add(row);
    }
    List<List<Object>> rows = new ArrayList<>();
    for (Map.Entry<String, List<PickNoteRow>> e : groups.entrySet()) {
      List<PickNoteRow> group = e.getValue();
      int n = group.size();
      long confirmed = group.stream().filter(r -> r.status() == PickNoteStatus.CONFIRMED).count();
      double[] excess = group.stream().filter(r -> r.finalExcess() != null && !r.finalExcess().isNaN()).mapToDouble(PickNoteRow::finalExcess).toArray();
      Double mean = excess.length == 0 ? null : java.util.Arrays.stream(excess).average().orElse(0);
      Double se = null;
      if (excess.length >= 2 && mean != null) {
        double var = java.util.Arrays.stream(excess).map(v -> (v - mean) * (v - mean)).sum() / (excess.length - 1);
        se = Math.sqrt(var / excess.length);
      }
      String[] key = e.getKey().split("\\|", -1);
      List<Object> r = new ArrayList<>();
      r.add(key[0]);
      r.add(SEC_CONS_UNKNOWN.equals(key[1]) ? SEC_CONS_UNKNOWN : Integer.valueOf(key[1]));
      r.add(n);
      r.add(AdvicePromptBuilder.round((double) confirmed / n));
      r.add(AdvicePromptBuilder.round(mean));
      r.add(AdvicePromptBuilder.round(se));
      r.add(n < UNDERPOWERED_N);
      rows.add(r);
    }
    rows.sort(Comparator.<List<Object>>comparingInt(r -> -(Integer) r.get(2)).thenComparing(r -> String.valueOf(r.get(0))).thenComparing(r -> String.valueOf(r.get(1))));
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("windowTradingDays", windowTradingDays);
    block.put("finalizedAsOf", finalizedAsOf.toString());
    block.put("columns", COLUMNS);
    block.put("rows", rows.size() > maxRows ? new ArrayList<>(rows.subList(0, maxRows)) : rows);
    return block;
  }

  /**
   * 노트 tags.secCons(1/0, JSONB 왕복으로 Number 또는 Boolean) → "1"/"0", 없으면 "-".
   */
  static String secConsLabel(PickNoteRow row) {
    Object v = row.tags() == null ? null : row.tags().get("secCons");
    if (v instanceof Number n) {
      return n.intValue() > 0 ? "1" : "0";
    }
    if (v instanceof Boolean b) {
      return b ? "1" : "0";
    }
    return SEC_CONS_UNKNOWN;
  }

  /**
   * 블록의 행 수 (memory_json 요약용). 블록이 null 이면 0.
   */
  static int rowCount(Map<String, Object> block) {
    return block != null && block.get("rows") instanceof List<?> rows ? rows.size() : 0;
  }
}
