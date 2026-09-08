package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.repository.StockCollectCheckpointRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * 재개 대상 조회: PAUSED 를 포함해 조회하고, attempt 임계를 넘은 것만 건너뛴다.
 */
class CollectCheckpointServiceTest {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 8);

  private final StockCollectCheckpointRepository repository = mock(StockCollectCheckpointRepository.class);
  private final KisProperties properties = new KisProperties();
  private final CollectCheckpointService service = new CollectCheckpointService(repository, properties);

  @Test
  @DisplayName("findResumable 은 PENDING·IN_PROGRESS·FAILED·PAUSED 를 조회하고 attempt < max-attempts 만 돌려준다")
  @SuppressWarnings("unchecked")
  void findResumableIncludesPausedAndFiltersAttempts() {
    properties.getBackfill().setMaxAttempts(3);
    StockCollectCheckpoint paused = StockCollectCheckpoint.pending(CollectJobType.INVESTOR_BACKFILL, "005930", DAY);
    paused.start(1L);
    paused.pause("윈도우 수 상한 도달 (40)");                       // attempt 0
    StockCollectCheckpoint exhaustedAttempts = StockCollectCheckpoint.pending(CollectJobType.INVESTOR_BACKFILL, "000660", DAY);
    for (int i = 0; i < 3; i++) {
      exhaustedAttempts.start((long) i);
      exhaustedAttempts.markFailed("boom");                        // attempt 3
    }
    when(repository.findResumable(eq("INVESTOR_BACKFILL"), any(), any(Pageable.class)))
        .thenReturn(List.of(paused, exhaustedAttempts));

    List<StockCollectCheckpoint> resumable = service.findResumable(CollectJobType.INVESTOR_BACKFILL, 100);

    assertThat(resumable).containsExactly(paused);
    ArgumentCaptor<Collection<CheckpointStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
    verify(repository).findResumable(eq("INVESTOR_BACKFILL"), statuses.capture(), any(Pageable.class));
    assertThat(statuses.getValue()).containsExactlyInAnyOrder(CheckpointStatus.PENDING, CheckpointStatus.IN_PROGRESS,
        CheckpointStatus.FAILED, CheckpointStatus.PAUSED);
  }
}
