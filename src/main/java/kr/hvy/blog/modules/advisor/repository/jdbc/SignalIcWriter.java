package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.model.SignalIcRow;
import kr.hvy.blog.modules.stock.repository.jdbc.BatchUpsertSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시그널 일별 rank-IC 저장·조회 (tb_advisor_signal_ic_daily). 호라이즌마다 별도 행이며(PK signal_code, trade_date, horizon_days) 조회는 항상 호라이즌을 명시한다 —
 * 섞으면 h=5 창 통계에 h=20 IC 가 끼어 가중치가 오염된다(M5).
 */
@Repository
@RequiredArgsConstructor
public class SignalIcWriter {

  private static final String UPSERT = "INSERT INTO tb_advisor_signal_ic_daily (signal_code, trade_date, horizon_days, rank_ic, n, computed_at) "
      + "VALUES (?, ?, ?, ?, ?, NOW()) "
      + "ON CONFLICT (signal_code, trade_date, horizon_days) DO UPDATE SET rank_ic = EXCLUDED.rank_ic, n = EXCLUDED.n, "
      + "computed_at = NOW() WHERE (tb_advisor_signal_ic_daily.rank_ic, tb_advisor_signal_ic_daily.n) IS DISTINCT FROM (EXCLUDED.rank_ic, EXCLUDED.n)";

  private final BatchUpsertSupport upsert;
  private final JdbcTemplate jdbc;

  @Transactional
  public int upsert(List<SignalIcRow> rows) {
    return upsert.batchUpsert(UPSERT, rows, 5, (ps, r) -> {
      ps.setString(1, r.signalCode());
      ps.setObject(2, r.tradeDate());
      ps.setInt(3, r.horizonDays());
      ps.setDouble(4, r.rankIc());
      ps.setInt(5, r.n());
    });
  }

  /**
   * 호라이즌 h 의 IC 가 계산된 마지막 기준일 (증분 계산 시작점). 호라이즌마다 따로다 — h=180 은 h=5 보다 175 영업일 늦다.
   */
  public Optional<LocalDate> maxTradeDate(int horizonDays) {
    return Optional.ofNullable(jdbc.queryForObject("SELECT MAX(trade_date) FROM tb_advisor_signal_ic_daily WHERE horizon_days = ?", LocalDate.class,
        horizonDays));
  }

  /**
   * 호라이즌 h 의 창 안 시그널별 IC 행 (기준일 이하, 최근 windowDays 영업일 = 행 수 기준으로 자른다).
   */
  public List<SignalIcRow> window(int horizonDays, LocalDate onOrBefore, int windowDays) {
    return jdbc.query("SELECT signal_code, trade_date, horizon_days, rank_ic, n FROM ("
            + "SELECT signal_code, trade_date, horizon_days, rank_ic, n, "
            + "ROW_NUMBER() OVER (PARTITION BY signal_code ORDER BY trade_date DESC) AS rn "
            + "FROM tb_advisor_signal_ic_daily WHERE horizon_days = ? AND trade_date <= ?) w WHERE rn <= ? ORDER BY signal_code, trade_date",
        (rs, i) -> new SignalIcRow(rs.getString("signal_code"), rs.getObject("trade_date", LocalDate.class), rs.getInt("horizon_days"),
            rs.getDouble("rank_ic"), rs.getInt("n")),
        horizonDays, onOrBefore, windowDays);
  }
}
