package kr.hvy.blog.modules.memo.application.service;

import io.hypersistence.tsid.TSID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import kr.hvy.blog.modules.memo.application.dto.MemoCategoryResponse;
import kr.hvy.blog.modules.memo.application.dto.MemoCreate;
import kr.hvy.blog.modules.memo.application.dto.MemoResponse;
import kr.hvy.blog.modules.memo.application.dto.MemoSearchRequest;
import kr.hvy.blog.modules.memo.application.dto.MemoSearchResult;
import kr.hvy.blog.modules.memo.application.dto.MemoUpdate;
import kr.hvy.blog.modules.memo.domain.entity.Memo;
import kr.hvy.blog.modules.memo.domain.entity.MemoCategory;
import kr.hvy.blog.modules.memo.mapper.MemoDtoMapper;
import kr.hvy.blog.modules.memo.repository.MemoRepository;
import kr.hvy.blog.modules.memo.repository.mapper.MemoMapper;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
import kr.hvy.common.application.domain.vo.EventLog;
import kr.hvy.common.core.exception.DataNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class MemoService {

  @PersistenceContext
  private EntityManager entityManager;

  private final MemoDtoMapper memoDtoMapper;
  private final MemoRepository memoRepository;
  private final MemoMapper memoMapper;
  private final MemoCategoryService memoCategoryService;

  public Memo findById(Long id) {
    return memoRepository.findById(id)
        .orElseThrow(() -> new DataNotFoundException("메모가 존재하지 않습니다."));
  }

  public MemoResponse create(MemoCreate dto) {
    MemoCategory category = dto.getCategoryId() != null
        ? memoCategoryService.findById(dto.getCategoryId())
        : null;

    Memo memo = Memo.builder()
        .content(dto.getContent())
        .category(category)
        .build();

    Memo saved = memoRepository.save(memo);
    entityManager.flush();
    entityManager.refresh(saved);
    return memoDtoMapper.toResponse(saved);
  }

  @Transactional(readOnly = true)
  public MemoResponse getById(String hexId) {
    Long id = TSID.from(hexId).toLong();
    Memo memo = findById(id);
    return memoDtoMapper.toResponse(memo);
  }

  public MemoResponse update(String hexId, MemoUpdate dto) {
    Long id = TSID.from(hexId).toLong();
    Memo memo = findById(id);

    MemoCategory category = dto.getCategoryId() != null
        ? memoCategoryService.findById(dto.getCategoryId())
        : null;

    memo.update(dto.getContent(), category);
    Memo saved = memoRepository.save(memo);
    entityManager.flush();
    entityManager.refresh(saved);
    return memoDtoMapper.toResponse(saved);
  }

  public DeleteResponse<String> delete(String hexId) {
    Long id = TSID.from(hexId).toLong();
    Memo memo = findById(id);
    memo.softDelete();
    memoRepository.save(memo);
    return DeleteResponse.<String>builder().id(hexId).build();
  }

  @Transactional(readOnly = true)
  public PageResponse<MemoResponse> search(MemoSearchRequest request) {
    int totalCount = memoMapper.countMemos(request);
    List<MemoSearchResult> results = memoMapper.searchMemos(request);
    List<MemoResponse> memos = results.stream().map(this::toMemoResponse).toList();
    return PageResponse.<MemoResponse>builder()
        .list(memos)
        .page(request.getPage())
        .pageSize(request.getPageSize())
        .totalCount(totalCount)
        .build();
  }

  private MemoResponse toMemoResponse(MemoSearchResult result) {
    MemoCategoryResponse category = result.getCategoryId() != null
        ? MemoCategoryResponse.builder()
        .id(result.getCategoryId())
        .name(result.getCategoryName())
        .seq(result.getCategorySeq())
        .build()
        : null;

    return MemoResponse.builder()
        .id(TSID.from(result.getId()).toString())
        .content(result.getContent())
        .deleted(result.isDeleted())
        .category(category)
        .created(EventLog.builder().at(result.getCreatedAt()).by(result.getCreatedBy()).build())
        .updated(EventLog.builder().at(result.getUpdatedAt()).by(result.getUpdatedBy()).build())
        .build();
  }
}
