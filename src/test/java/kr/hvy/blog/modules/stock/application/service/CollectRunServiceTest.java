package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.repository.StockCollectRunRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class CollectRunServiceTest {

  /**
   * 이 서비스의 public 메서드는 전부 DB 를 건드리고, 잡 스레드(트랜잭션 없는 컨텍스트)에서 직접 호출된다.
   * 애노테이션이 빠지면 {@code @Modifying} 벌크 UPDATE 가 TransactionRequiredException 으로 터지고,
   * 같은 빈 안에서 다른 메서드로 위임해도 프록시를 타지 않아 상대의 경계를 빌려올 수 없다.
   */
  @Test
  @DisplayName("public 메서드는 모두 자기 트랜잭션 경계를 선언한다 (자기 호출로 프록시를 우회하지 못하므로)")
  void everyPublicMethodDeclaresItsOwnTransaction() {
    List<String> missing = Arrays.stream(CollectRunService.class.getDeclaredMethods())
        .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
        .filter(method -> !method.isSynthetic())
        .filter(method -> !method.isAnnotationPresent(Transactional.class))
        .map(Method::getName)
        .toList();

    assertThat(missing).isEmpty();
  }

  @Test
  @DisplayName("같은 잡이 RUNNING 이면 INSERT 전에 409 예외를 던진다 (제약 위반 후 같은 세션에서 조회하면 Hibernate 가 죽는다)")
  void startRejectsWhenAlreadyRunningBeforeInsert() {
    StockCollectRunRepository repository = mock(StockCollectRunRepository.class);
    StockCollectRun running = StockCollectRun.builder().runId(63L).jobType(CollectJobType.DERIVED_REFRESH)
        .triggerType(TriggerType.API).build();
    when(repository.findFirstByJobTypeAndStatus(CollectJobType.DERIVED_REFRESH, CollectStatus.RUNNING)).thenReturn(Optional.of(running));
    CollectRunService service = new CollectRunService(repository);

    assertThatThrownBy(() -> service.start(CollectJobType.DERIVED_REFRESH, TriggerType.API, LocalDate.of(2026, 9, 8), null, null, Map.of()))
        .isInstanceOf(CollectAlreadyRunningException.class)
        .extracting("runningRunId").isEqualTo(63L);
    verify(repository, never()).saveAndFlush(any());
    assertThat(service.findRunningId(CollectJobType.DERIVED_REFRESH)).contains(63L);
  }
}
