package kr.hvy.blog.modules.hotdeal.application;

import jakarta.validation.Valid;
import java.util.List;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealKeywordCreate;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealKeywordResponse;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealKeywordUpdate;
import kr.hvy.blog.modules.hotdeal.application.service.HotDealKeywordService;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 핫딜 알림 키워드 관리 API.
 *
 * <p>경로가 /api/*&#47;admin/** 패턴이므로 SecurityConfig가 ROLE_ADMIN을 강제한다.
 */
@RestController
@RequestMapping("/api/hot-deal/admin/keywords")
@RequiredArgsConstructor
public class HotDealKeywordAdminController {

  private final HotDealKeywordService hotDealKeywordService;

  @GetMapping
  public List<HotDealKeywordResponse> getAll() {
    return hotDealKeywordService.getAllKeywords();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public HotDealKeywordResponse create(@RequestBody @Valid HotDealKeywordCreate request) {
    return hotDealKeywordService.create(request);
  }

  @PutMapping("/{id}")
  public HotDealKeywordResponse update(
      @PathVariable Long id,
      @RequestBody @Valid HotDealKeywordUpdate request) {
    return hotDealKeywordService.update(id, request);
  }

  @DeleteMapping("/{id}")
  public DeleteResponse<Long> delete(@PathVariable Long id) {
    return hotDealKeywordService.delete(id);
  }
}
