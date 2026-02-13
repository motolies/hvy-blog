package kr.hvy.blog.modules.memo.application;

import jakarta.validation.Valid;
import kr.hvy.blog.modules.memo.application.dto.MemoCreate;
import kr.hvy.blog.modules.memo.application.dto.MemoResponse;
import kr.hvy.blog.modules.memo.application.dto.MemoSearchRequest;
import kr.hvy.blog.modules.memo.application.dto.MemoUpdate;
import kr.hvy.blog.modules.memo.application.service.MemoService;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
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

@RestController
@RequestMapping("/api/memo/admin")
@RequiredArgsConstructor
public class MemoAdminController {

  private final MemoService memoService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public MemoResponse create(@RequestBody @Valid MemoCreate dto) {
    return memoService.create(dto);
  }

  @GetMapping("/{id}")
  public MemoResponse getById(@PathVariable String id) {
    return memoService.getById(id);
  }

  @PutMapping("/{id}")
  public MemoResponse update(@PathVariable String id, @RequestBody @Valid MemoUpdate dto) {
    return memoService.update(id, dto);
  }

  @DeleteMapping("/{id}")
  public DeleteResponse<String> delete(@PathVariable String id) {
    return memoService.delete(id);
  }

  @PostMapping("/search")
  public PageResponse<MemoResponse> search(@RequestBody MemoSearchRequest request) {
    return memoService.search(request);
  }
}
