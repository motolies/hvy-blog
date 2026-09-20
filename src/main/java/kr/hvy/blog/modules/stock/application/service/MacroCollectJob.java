package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.stock.client.MacroDataPort;
import kr.hvy.blog.modules.stock.client.MacroProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import kr.hvy.blog.modules.stock.domain.model.MacroSeriesSpec;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;
import kr.hvy.blog.modules.stock.repository.jdbc.MacroDailyWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 거시 위험 지표 수집 (MACRO, 화~토 06:35·08:35 KST). 요청에 startDate 가 있으면 그 날부터(백필), 없으면 최근 lookback-days 만 다시 받는다.
 * 시리즈마다 실패를 격리하고, 메타에 시리즈별 {rows, fetched, latest, lagDays} 를 남겨 T-1 이 잡혔는지(lagDays=1) 바로 볼 수 있게 한다.
 * 겨울(EST)엔 06:35 에 CBOE 가 아직 전날 값을 안 올렸을 수 있어 08:35 에 한 번 더 돈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroCollectJob implements CollectJob {

  private final MacroDataPort port;
  private final MacroDailyWriter writer;
  private final MacroProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.MACRO;
  }

  @Override
  public void execute(CollectExecution execution) {
    LocalDate today = MarketClock.today();
    LocalDate to = Optional.ofNullable(execution.request().endDate()).orElse(today);
    boolean backfill = execution.request().startDate() != null;
    LocalDate from = backfill ? execution.request().startDate() : to.minusDays(Math.max(1, properties.getLookbackDays()) - 1L);
    List<MacroSeriesSpec> specs = properties.specs();
    execution.putMetadata("mode", backfill ? "BACKFILL" : "INCREMENTAL");
    execution.putMetadata("from", from.toString());
    execution.putMetadata("to", to.toString());
    execution.putMetadata("targets", specs.size());
    Map<String, Object> perSeries = new LinkedHashMap<>();

    for (MacroSeriesSpec spec : specs) {
      if (execution.isCancelRequested()) {
        break;
      }
      String code = spec.series().getCode();
      try {
        SourceFetch<MacroObservation> fetched = port.fetchSeries(spec, from, to);
        execution.context(code).stats().getApiCalls().addAndGet(fetched.httpCalls());
        int changed = fetched.rows().isEmpty() ? 0 : writer.upsert(fetched.rows());
        execution.addRows(changed);
        execution.targetDone();
        LocalDate latest = fetched.rows().stream().map(MacroObservation::obsDate).max(LocalDate::compareTo).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fetched", fetched.rows().size());
        m.put("rows", changed);
        if (latest != null) {
          m.put("latest", latest.toString());
          m.put("lagDays", ChronoUnit.DAYS.between(latest, today));
        }
        perSeries.put(code, m);
        if (fetched.rows().isEmpty()) {
          log.warn("거시 지표 {} 가 [{}, {}] 에서 0행 — 원천 형식 변경 또는 게시 지연 확인 ({})", code, from, to, spec.url());
        }
      } catch (RuntimeException e) {
        execution.context(code).stats().getApiFails().incrementAndGet();
        execution.recordFailure(code, e.getMessage());
        log.warn("거시 지표 수집 실패: series={}, cause={}", code, e.getMessage());
      }
    }
    execution.putMetadata("series", perSeries);
    execution.flush();
    log.info("거시 지표 수집: mode={}, from={}, to={}, series={}", backfill ? "BACKFILL" : "INCREMENTAL", from, to, perSeries);
  }
}
