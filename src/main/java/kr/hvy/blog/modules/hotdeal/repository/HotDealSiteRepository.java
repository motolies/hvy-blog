package kr.hvy.blog.modules.hotdeal.repository;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotDealSiteRepository extends JpaRepository<HotDealSite, Long> {

  List<HotDealSite> findByEnabledTrue();

  Optional<HotDealSite> findBySiteCode(String siteCode);
}
