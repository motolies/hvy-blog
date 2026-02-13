package kr.hvy.blog.modules.memo.application.service;

import java.util.List;
import kr.hvy.blog.modules.memo.application.dto.MemoCategoryCreate;
import kr.hvy.blog.modules.memo.application.dto.MemoCategoryResponse;
import kr.hvy.blog.modules.memo.application.dto.MemoCategoryUpdate;
import kr.hvy.blog.modules.memo.domain.entity.MemoCategory;
import kr.hvy.blog.modules.memo.mapper.MemoCategoryDtoMapper;
import kr.hvy.blog.modules.memo.repository.MemoCategoryRepository;
import kr.hvy.blog.modules.memo.repository.MemoRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class MemoCategoryService {

  private final MemoCategoryDtoMapper memoCategoryDtoMapper;
  private final MemoCategoryRepository memoCategoryRepository;
  private final MemoRepository memoRepository;

  public MemoCategory findById(Long id) {
    return memoCategoryRepository.findById(id)
        .orElseThrow(() -> new DataNotFoundException("메모 카테고리가 존재하지 않습니다."));
  }

  @Transactional(readOnly = true)
  public List<MemoCategoryResponse> findAll() {
    return memoCategoryRepository.findAllByOrderBySeqAscNameAsc()
        .stream()
        .map(memoCategoryDtoMapper::toResponse)
        .toList();
  }

  public MemoCategoryResponse create(MemoCategoryCreate dto) {
    MemoCategory entity = memoCategoryDtoMapper.toDomain(dto);
    MemoCategory saved = memoCategoryRepository.save(entity);
    return memoCategoryDtoMapper.toResponse(saved);
  }

  public MemoCategoryResponse update(Long id, MemoCategoryUpdate dto) {
    MemoCategory entity = findById(id);
    entity.update(dto.getName(), dto.getSeq());
    MemoCategory saved = memoCategoryRepository.save(entity);
    return memoCategoryDtoMapper.toResponse(saved);
  }

  public DeleteResponse<Long> delete(Long id) {
    if (memoRepository.existsByCategoryId(id)) {
      throw new IllegalArgumentException("해당 카테고리에 연결된 메모가 있어 삭제할 수 없습니다.");
    }
    memoCategoryRepository.deleteById(id);
    return DeleteResponse.<Long>builder().id(id).build();
  }
}
