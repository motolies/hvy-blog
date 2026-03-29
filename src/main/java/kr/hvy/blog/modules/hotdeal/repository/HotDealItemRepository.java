package kr.hvy.blog.modules.hotdeal.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealItem;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HotDealItemRepository extends JpaRepository<HotDealItem, Long> {

  boolean existsBySiteAndExternalId(HotDealSite site, String externalId);

  Optional<HotDealItem> findBySiteAndExternalId(HotDealSite site, String externalId);

  @Modifying
  @Query("DELETE FROM HotDealItem h WHERE h.created.at < :cutoffDate")
  int deleteByCreatedAtBefore(@Param("cutoffDate") LocalDateTime cutoffDate);
}
