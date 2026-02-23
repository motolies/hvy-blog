package kr.hvy.blog.modules.log.application;

import jakarta.validation.Valid;
import kr.hvy.blog.modules.log.application.dto.SystemLogSearchRequest;
import kr.hvy.blog.modules.log.application.dto.SystemLogSearchResponse;
import kr.hvy.blog.modules.log.application.service.SystemLogSearchService;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/log/admin/system")
@RequiredArgsConstructor
public class SystemLogController {

  private final SystemLogSearchService systemLogSearchService;

  @PostMapping("/search")
  public PageResponse<SystemLogSearchResponse> search(
      @RequestBody @Valid SystemLogSearchRequest request) {
    log.debug("System log search request: {}", request);
    return systemLogSearchService.search(request);
  }

}
