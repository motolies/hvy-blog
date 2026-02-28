package kr.hvy.blog.modules.hotdeal.repository;

import java.util.Optional;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealItem;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotDealItemRepository extends JpaRepository<HotDealItem, Long> {

  boolean existsBySiteAndExternalId(HotDealSite site, String externalId);

  Optional<HotDealItem> findBySiteAndExternalId(HotDealSite site, String externalId);
}
