package kr.hvy.blog.modules.memo.application;

import jakarta.validation.Valid;
import java.util.List;
import kr.hvy.blog.modules.memo.application.dto.MemoCategoryCreate;
import kr.hvy.blog.modules.memo.application.dto.MemoCategoryResponse;
import kr.hvy.blog.modules.memo.application.dto.MemoCategoryUpdate;
import kr.hvy.blog.modules.memo.application.service.MemoCategoryService;
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

@RestController
@RequestMapping("/api/memo-category/admin")
@RequiredArgsConstructor
public class MemoCategoryAdminController {

  private final MemoCategoryService memoCategoryService;

  @GetMapping
  public List<MemoCategoryResponse> findAll() {
    return memoCategoryService.findAll();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public MemoCategoryResponse create(@RequestBody @Valid MemoCategoryCreate dto) {
    return memoCategoryService.create(dto);
  }

  @PutMapping("/{id}")
  public MemoCategoryResponse update(@PathVariable Long id, @RequestBody @Valid MemoCategoryUpdate dto) {
    return memoCategoryService.update(id, dto);
  }

  @DeleteMapping("/{id}")
  public DeleteResponse<Long> delete(@PathVariable Long id) {
    return memoCategoryService.delete(id);
  }
}
