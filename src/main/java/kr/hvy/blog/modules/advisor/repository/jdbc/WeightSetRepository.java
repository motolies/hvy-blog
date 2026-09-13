package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.model.SignalWeightRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가중치 세트 저장·조회 (tb_advisor_weight_set + tb_advisor_signal_weight). 활성 세트는 부분 유니크로 하나만 존재한다.
 */
@Repository
@RequiredArgsConstructor
public class WeightSetRepository {

  private static final String SET_COLUMNS = "weight_set_id, as_of, window_days, n_eff, source, is_active, reason, run_id";

  private final JdbcTemplate jdbc;

  /**
   * 활성 세트 (시드가 적용됐다면 항상 존재).
   */
  public Optional<WeightSet> active() {
    return jdbc.query("SELECT " + SET_COLUMNS + " FROM tb_advisor_weight_set WHERE is_active ORDER BY weight_set_id DESC LIMIT 1", SET_MAPPER)
        .stream().findFirst().map(this::withWeights);
  }

  public Optional<WeightSet> find(long weightSetId) {
    return jdbc.query("SELECT " + SET_COLUMNS + " FROM tb_advisor_weight_set WHERE weight_set_id = ?", SET_MAPPER, weightSetId)
        .stream().findFirst().map(this::withWeights);
  }

  public List<WeightSet> recent(int limit) {
    return jdbc.query("SELECT " + SET_COLUMNS + " FROM tb_advisor_weight_set ORDER BY weight_set_id DESC LIMIT ?", SET_MAPPER, limit)
        .stream().map(this::withWeights).toList();
  }

  /**
   * 새 세트를 저장한다. activate=true 면 기존 활성 세트를 내리고 이 세트를 활성화한다 (한 트랜잭션).
   */
  @Transactional
  public long insert(WeightSet set, boolean activate) {
    if (activate) {
      jdbc.update("UPDATE tb_advisor_weight_set SET is_active = FALSE WHERE is_active");
    }
    Long id = jdbc.queryForObject("INSERT INTO tb_advisor_weight_set (as_of, window_days, n_eff, source, is_active, reason, run_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING weight_set_id", Long.class,
        set.asOf(), set.windowDays(), set.nEff(), set.source().getCode(), activate, set.reason(), set.runId());
    jdbc.batchUpdate("INSERT INTO tb_advisor_signal_weight (weight_set_id, signal_code, base_weight, multiplier, weight, enabled, ic_mean, ic_se, "
            + "t_stat, n_days, flagged, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        set.weights(), set.weights().size(), (ps, w) -> {
          ps.setLong(1, id);
          ps.setString(2, w.signalCode());
          ps.setDouble(3, w.baseWeight());
          ps.setDouble(4, w.multiplier());
          ps.setDouble(5, w.weight());
          ps.setBoolean(6, w.enabled());
          ps.setObject(7, w.icMean());
          ps.setObject(8, w.icSe());
          ps.setObject(9, w.tStat());
          ps.setObject(10, w.nDays());
          ps.setBoolean(11, w.flagged());
          ps.setString(12, w.note());
        });
    return id;
  }

  /**
   * 기존 세트를 활성화한다 (수동 롤백).
   */
  @Transactional
  public void activate(long weightSetId) {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM tb_advisor_weight_set WHERE weight_set_id = ?", Integer.class, weightSetId) == 0) {
      throw new NoSuchElementException("가중치 세트를 찾을 수 없습니다: " + weightSetId);
    }
    jdbc.update("UPDATE tb_advisor_weight_set SET is_active = FALSE WHERE is_active");
    jdbc.update("UPDATE tb_advisor_weight_set SET is_active = TRUE WHERE weight_set_id = ?", weightSetId);
  }

  private WeightSet withWeights(WeightSet set) {
    List<SignalWeightRow> weights = jdbc.query("SELECT signal_code, base_weight, multiplier, weight, enabled, ic_mean, ic_se, t_stat, n_days, "
        + "flagged, note FROM tb_advisor_signal_weight WHERE weight_set_id = ? ORDER BY signal_code", WEIGHT_MAPPER, set.weightSetId());
    return set.toBuilder().weights(weights).build();
  }

  static final RowMapper<WeightSet> SET_MAPPER = (rs, i) -> WeightSet.builder()
      .weightSetId(rs.getLong("weight_set_id"))
      .asOf(rs.getObject("as_of", LocalDate.class))
      .windowDays(rs.getInt("window_days"))
      .nEff(rs.getDouble("n_eff"))
      .source(AdvisorJdbc.enumOrNull(rs, "source", WeightSetSource.class))
      .active(rs.getBoolean("is_active"))
      .reason(rs.getString("reason"))
      .runId(AdvisorJdbc.nullableLong(rs, "run_id"))
      .weights(List.of())
      .build();

  static final RowMapper<SignalWeightRow> WEIGHT_MAPPER = (rs, i) -> SignalWeightRow.builder()
      .signalCode(rs.getString("signal_code"))
      .baseWeight(rs.getDouble("base_weight"))
      .multiplier(rs.getDouble("multiplier"))
      .weight(rs.getDouble("weight"))
      .enabled(rs.getBoolean("enabled"))
      .icMean(AdvisorJdbc.nullableDouble(rs, "ic_mean"))
      .icSe(AdvisorJdbc.nullableDouble(rs, "ic_se"))
      .tStat(AdvisorJdbc.nullableDouble(rs, "t_stat"))
      .nDays(AdvisorJdbc.nullableInt(rs, "n_days"))
      .flagged(rs.getBoolean("flagged"))
      .note(rs.getString("note"))
      .build();
}
