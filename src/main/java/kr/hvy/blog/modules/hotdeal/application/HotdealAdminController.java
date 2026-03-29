package kr.hvy.blog.modules.hotdeal.application;

import jakarta.validation.Valid;
import java.util.List;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealItemSearchRequest;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealItemSearchResponse;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealSiteResponse;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealSiteUpdateRequest;
import kr.hvy.blog.modules.hotdeal.application.service.HotDealItemSearchService;
import kr.hvy.blog.modules.hotdeal.application.service.HotDealService;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/hot-deal/admin")
@RequiredArgsConstructor
public class HotdealAdminController {

  private final HotDealService hotDealService;
  private final HotDealItemSearchService hotDealItemSearchService;

  @PostMapping
  public void sync() {
    hotDealService.scrapeAndNotify();
  }

  @GetMapping("/sites")
  public List<HotDealSiteResponse> getAllSites() {
    return hotDealService.getAllSites();
  }

  @PutMapping("/sites/{id}")
  public HotDealSiteResponse updateSite(
      @PathVariable Long id,
      @RequestBody @Valid HotDealSiteUpdateRequest request) {
    return hotDealService.updateSite(id, request);
  }

  @PostMapping("/items/search")
  public PageResponse<HotDealItemSearchResponse> searchItems(
      @RequestBody @Valid HotDealItemSearchRequest request) {
    return hotDealItemSearchService.search(request);
  }
}
