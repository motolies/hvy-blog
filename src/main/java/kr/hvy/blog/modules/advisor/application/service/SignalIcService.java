package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.model.SignalIcRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.SignalIcWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 시그널 rank-IC 계산과 가중치 세트 산출. 학습 신호는 픽이 아니라 전 유니버스(하루 수백 종목)다.
 * <p>
 * IC_k(d) = corr(rank s_k(i,d), rank ex_h(i,d)), ex_h = 종목 수정주가 d→d+h 수익률 − 소속 시장 지수 d→d+h 수익률.
 * d+h 는 캘린더의 h번째 다음 영업일이며 LEAD 는 이 쿼리에만 있다(특징 SQL 에는 절대 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalIcService {

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalIcWriter icWriter;
  private final WeightSetRepository weightSets;
  private final AdvisorProperties properties;

  /**
   * [from, to] 기준일의 IC 를 계산해 저장하고 저장 행 수를 돌려준다. d+h 가 없는 날은 결과에서 자연히 빠진다.
   */
  public int computeAndStore(LocalDate from, LocalDate to) {
    List<SignalIcRow> rows = compute(from, to, properties.getHorizonDays());
    int stored = icWriter.upsert(rows);
    log.info("시그널 IC 계산: {}~{}, rows={}, stored={}", from, to, rows.size(), stored);
    return rows.size();
  }

  /**
   * IC 행 계산 (저장 없음).
   */
  public List<SignalIcRow> compute(LocalDate from, LocalDate to, int horizonDays) {
    List<SignalCode> signals = SignalCode.learnable();
    String sql = FeatureSql.featureCtes()
        + ", cal_all AS (\n"
        + "    SELECT trade_date, ROW_NUMBER() OVER (ORDER BY trade_date) AS rn FROM vw_stock_market_calendar\n"
        + "),\n"
        + "exits AS (\n"
        + "    SELECT c0.trade_date, ch.trade_date AS exit_date\n"
        + "    FROM cal_all c0 JOIN cal_all ch ON ch.rn = c0.rn + :h\n"
        + "    WHERE c0.trade_date BETWEEN :from AND :to\n"
        + "),\n"
        + "realized AS (\n"
        + "    SELECT f.ticker, f.trade_date,\n"
        + "           (mx.adj_close / NULLIF(f.adj_close, 0) - 1) - (ix.close_value / NULLIF(i0.close_value, 0) - 1) AS ex\n"
        + "    FROM feat f\n"
        + "             JOIN exits e ON e.trade_date = f.trade_date\n"
        + "             LEFT JOIN tb_stock_daily_metric mx ON mx.ticker = f.ticker AND mx.trade_date = e.exit_date\n"
        + "             LEFT JOIN mv_stock_index_metric i0 ON i0.index_code = f.bench_index_code AND i0.trade_date = f.trade_date\n"
        + "             LEFT JOIN mv_stock_index_metric ix ON ix.index_code = f.bench_index_code AND ix.trade_date = e.exit_date\n"
        + "),\n"
        + "unpivot AS (\n"
        + "    SELECT f.trade_date, s.signal_code, s.value, r.ex\n"
        + "    FROM feat f\n"
        + "             JOIN realized r ON r.ticker = f.ticker AND r.trade_date = f.trade_date\n"
        + "             CROSS JOIN LATERAL (VALUES\n              " + FeatureSql.icValuesList(signals) + ") AS s(signal_code, value)\n"
        + "    WHERE r.ex IS NOT NULL AND s.value IS NOT NULL\n"
        + "),\n"
        + "ranked AS (\n"
        + "    SELECT trade_date, signal_code,\n"
        + "           RANK() OVER (PARTITION BY trade_date, signal_code ORDER BY value) AS rs,\n"
        + "           RANK() OVER (PARTITION BY trade_date, signal_code ORDER BY ex) AS rx\n"
        + "    FROM unpivot\n"
        + ")\n"
        + "SELECT trade_date, signal_code, corr(rs, rx) AS rank_ic, COUNT(*) AS n\n"
        + "FROM ranked GROUP BY trade_date, signal_code HAVING COUNT(*) >= 30 AND corr(rs, rx) IS NOT NULL ORDER BY trade_date, signal_code";
    return jdbc.query(sql, Map.of("from", from, "to", to, "h", horizonDays),
        (rs, i) -> new SignalIcRow(rs.getString("signal_code"), rs.getObject("trade_date", LocalDate.class), horizonDays,
            rs.getDouble("rank_ic"), rs.getInt("n")));
  }

  /**
   * 기준일 이하 창의 IC 통계로 새 가중치 세트를 산출한다. 게이트(n_eff ≥ ic.min-n-eff) 미달이면 empty.
   * 저장·활성화는 호출자가 결정한다.
   */
  public Optional<WeightSet> proposeWeightSet(LocalDate asOf, WeightSetSource source, Long runId) {
    AdvisorProperties.Ic ic = properties.getIc();
    WeightSet current = weightSets.active().orElseThrow(() -> new IllegalStateException("활성 가중치 세트가 없습니다"));
    List<SignalIcRow> window = icWriter.window(asOf, ic.getWindowDays());
    Map<String, SignalWeightMath.IcStat> stats = SignalWeightMath.aggregate(window, properties.getHorizonDays());
    double minNEff = SignalWeightMath.minLearnableNEff(stats);
    if (minNEff < ic.getMinNEff()) {
      log.info("가중치 세트 미갱신: n_eff {} < {} (asOf={})", minNEff, ic.getMinNEff(), asOf);
      return Optional.empty();
    }
    WeightSet proposed = WeightSet.builder()
        .asOf(asOf)
        .windowDays(ic.getWindowDays())
        .nEff(minNEff)
        .source(source)
        .active(false)
        .reason(source + " IC 창 " + ic.getWindowDays() + "일, n_eff " + String.format("%.1f", minNEff))
        .runId(runId)
        .weights(SignalWeightMath.apply(stats, current.weights(), ic))
        .build();
    return Optional.of(proposed);
  }

  /**
   * IC 가 계산된 마지막 기준일 다음 날부터 계산 가능한 마지막 기준일(캘린더 끝 − h)까지 증분 계산한다.
   *
   * @return 계산한 기준일 범위 (없으면 empty)
   */
  public Optional<LocalDate[]> computeIncremental() {
    LocalDate start = icWriter.maxTradeDate().map(d -> d.plusDays(1)).orElse(LocalDate.parse(properties.getIc().getBackfillFrom()));
    Optional<LocalDate> end = latestScorableDate(properties.getHorizonDays());
    if (end.isEmpty() || end.get().isBefore(start)) {
      return Optional.empty();
    }
    computeAndStore(start, end.get());
    return Optional.of(new LocalDate[] {start, end.get()});
  }

  /**
   * d+h 가 존재하는 마지막 기준일 = 캘린더 마지막 행에서 h 행 앞.
   */
  public Optional<LocalDate> latestScorableDate(int horizonDays) {
    List<LocalDate> dates = jdbc.query("SELECT trade_date FROM vw_stock_market_calendar ORDER BY trade_date DESC OFFSET :h LIMIT 1",
        Map.of("h", horizonDays), (rs, i) -> rs.getObject("trade_date", LocalDate.class));
    return dates.stream().findFirst();
  }
}
