package kr.hvy.blog.modules.stock.repository;

import java.util.Collection;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.entity.StockMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockMasterRepository extends JpaRepository<StockMaster, String> {

  List<StockMaster> findAllByActiveTrueOrderByTicker();

  List<StockMaster> findAllByActiveTrueAndSecurityGroupInOrderByTicker(Collection<String> securityGroups);

  List<StockMaster> findAllByActiveTrueAndMarketTypeOrderByTicker(MarketType marketType);

  /**
   * 활성 종목 코드만 (백필 대상 목록용, 엔티티 로딩 없이).
   */
  @Query("SELECT m.ticker FROM StockMaster m WHERE m.active = TRUE AND m.securityGroup IN :groups ORDER BY m.ticker")
  List<String> findActiveTickers(@Param("groups") Collection<String> securityGroups);

  long countByActiveTrue();
}
