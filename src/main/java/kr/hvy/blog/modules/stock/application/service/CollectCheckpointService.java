package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.entity.CollectCheckpointId;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.repository.StockCollectCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종목/지수 단위 체크포인트 관리. 백필 재개의 단일 진실이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectCheckpointService {

  private static final List<CheckpointStatus> RESUMABLE = List.of(
      CheckpointStatus.PENDING, CheckpointStatus.IN_PROGRESS, CheckpointStatus.FAILED);

  private final StockCollectCheckpointRepository repository;
  private final KisProperties properties;

  /**
   * 대상 키들의 체크포인트를 준비한다. 없으면 PENDING 으로 만들고, reset 이면 기존 것도 커서를 되돌린다.
   *
   * @return 새로 만들거나 초기화한 건수
   */
  @Transactional
  public int initialize(CollectJobType jobType, Collection<String> targetKeys, LocalDate cursorDate, boolean reset) {
    int touched = 0;
    for (String key : targetKeys) {
      CollectCheckpointId id = CollectCheckpointId.of(jobType, key);
      StockCollectCheckpoint existing = repository.findById(id).orElse(null);
      if (existing == null) {
        repository.save(StockCollectCheckpoint.pending(jobType, key, cursorDate));
        touched++;
      } else if (reset) {
        existing.reset(cursorDate);
        touched++;
      }
    }
    log.info("체크포인트 준비: jobType={}, targets={}, touched={}, reset={}", jobType, targetKeys.size(), touched, reset);
    return touched;
  }

  /**
   * 재개 대상을 조회한다. attempt_count 가 임계를 넘은 FAILED 는 영구 실패로 보고 건너뛴다.
   * IN_PROGRESS 를 포함하는 것은 의도적이다 — 강제 종료된 종목을 다음 실행이 이어받는다.
   */
  @Transactional(readOnly = true)
  public List<StockCollectCheckpoint> findResumable(CollectJobType jobType, int limit) {
    int maxAttempts = properties.getBackfill().getMaxAttempts();
    return repository.findResumable(jobType.getCode(), RESUMABLE, PageRequest.of(0, limit)).stream()
        .filter(cp -> cp.getAttemptCount() < maxAttempts)
        .toList();
  }

  /**
   * 체크포인트 1건을 새 트랜잭션으로 저장한다. 백필 루프가 윈도우마다 호출하므로 본 작업과 분리한다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public StockCollectCheckpoint save(StockCollectCheckpoint checkpoint) {
    return repository.save(checkpoint);
  }

  @Transactional(readOnly = true)
  public List<StockCollectCheckpoint> findRecent(CollectJobType jobType, CheckpointStatus status, int limit) {
    PageRequest page = PageRequest.of(0, limit);
    return status == null
        ? repository.findRecent(jobType.getCode(), page)
        : repository.findRecentByStatus(jobType.getCode(), status, page);
  }

  /**
   * 상태별 건수. 모든 상태를 키로 포함해 0 도 표시한다.
   */
  @Transactional(readOnly = true)
  public Map<CheckpointStatus, Long> summary(CollectJobType jobType) {
    Map<CheckpointStatus, Long> result = new EnumMap<>(CheckpointStatus.class);
    for (CheckpointStatus status : CheckpointStatus.values()) {
      result.put(status, 0L);
    }
    repository.countByStatus(jobType.getCode()).forEach(row -> result.put(row.getStatus(), row.getCount()));
    return result;
  }
}
