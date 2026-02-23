package kr.hvy.blog.modules.log.application;

import jakarta.validation.Valid;
import kr.hvy.blog.modules.log.application.dto.ApiLogSearchRequest;
import kr.hvy.blog.modules.log.application.dto.ApiLogSearchResponse;
import kr.hvy.blog.modules.log.application.service.ApiLogSearchService;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/log/admin/api")
@RequiredArgsConstructor
public class ApiLogController {

  private final ApiLogSearchService apiLogSearchService;

  @PostMapping("/search")
  public PageResponse<ApiLogSearchResponse> search(
      @RequestBody @Valid ApiLogSearchRequest request) {
    log.debug("API log search request: {}", request);
    return apiLogSearchService.search(request);
  }

}
