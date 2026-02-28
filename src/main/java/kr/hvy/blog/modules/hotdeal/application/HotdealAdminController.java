package kr.hvy.blog.modules.hotdeal.application;

import kr.hvy.blog.modules.hotdeal.application.service.HotDealService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/hot-deal/admin")
@RequiredArgsConstructor
public class HotdealAdminController {

  private final HotDealService hotDealService;

  /**
   * 지라 이슈 동기화
   */
  @PostMapping
  public void sync() {
    hotDealService.scrapeAndNotify();
  }
}
