package kr.hvy.blog.observability;

import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * traceId 를 잃는 동시성 관용구를 빌드에서 차단한다.
 * <p>
 * 이 프로젝트의 traceId 는 로그 상관관계 전용이며, MDC 는 micrometer 가 <b>스코프 이벤트</b>로만 채운다.
 * 따라서 Spring 이 모르는 스레드 풀로 작업을 넘기면 그 안의 로그는 조용히 traceId 를 잃는다. 개별 지점을
 * 고쳐도 새 코드가 같은 실수를 반복하므로, 유실을 만드는 <b>호출 형태 자체</b>를 금지한다.
 * <p>
 * 검사 대상은 {@code src/main} 뿐이다({@link ImportOption.DoNotIncludeTests}) —
 * {@code TracePropagationIntegrationTest} 는 유실을 재현하는 것이 존재 이유라 raw 풀과 commonPool 을
 * 일부러 호출한다.
 */
@AnalyzeClasses(packages = "kr.hvy.blog", importOptions = ImportOption.DoNotIncludeTests.class)
class ConcurrencyArchTest {

  /**
   * {@code Executors.newXxx(...)} 로 직접 만든 풀은 Spring 이 모르므로 {@code TaskDecorator} 도,
   * {@code ContextSnapshot} 도 붙지 않는다.
   */
  private static final DescribedPredicate<JavaCall<?>> RAW_EXECUTORS_FACTORY =
      new DescribedPredicate<>("java.util.concurrent.Executors 의 풀 생성 팩토리를 직접 호출") {
        @Override
        public boolean test(JavaCall<?> call) {
          return call.getTargetOwner().isEquivalentTo(Executors.class)
              && call.getName().startsWith("new");
        }
      };

  /**
   * {@code runAsync(Runnable)} / {@code supplyAsync(Supplier)} 는 executor 를 안 받는 1-인자 오버로드다.
   * 이 경우 작업이 {@code ForkJoinPool.commonPool} 로 가는데, 그 풀은 전파 장치가 전혀 없다.
   */
  private static final DescribedPredicate<JavaCall<?>> EXECUTOR_LESS_COMPLETABLE_FUTURE =
      new DescribedPredicate<>("CompletableFuture.runAsync/supplyAsync 를 executor 없이 호출") {
        @Override
        public boolean test(JavaCall<?> call) {
          return call.getTargetOwner().isEquivalentTo(CompletableFuture.class)
              && ("runAsync".equals(call.getName()) || "supplyAsync".equals(call.getName()))
              && call.getTarget().getRawParameterTypes().size() == 1;
        }
      };

  /** {@code parallelStream()} 도 결국 commonPool 이다. 게다가 실행기를 지정할 방법이 없다. */
  private static final DescribedPredicate<JavaCall<?>> PARALLEL_STREAM =
      new DescribedPredicate<>("parallelStream() 호출") {
        @Override
        public boolean test(JavaCall<?> call) {
          return "parallelStream".equals(call.getName())
              && call.getTarget().getRawParameterTypes().isEmpty();
        }
      };

  @ArchTest
  static final ArchRule 스레드풀은_TraceExecutors_로만_만든다 = noClasses()
      .should(callMethodWhere(RAW_EXECUTORS_FACTORY))
      .because("Executors.new* 로 만든 풀에는 트레이스 컨텍스트 전파가 없어 그 안의 모든 로그가 traceId 를 잃는다. "
          + "kr.hvy.common.config.executor.TraceExecutors 를 쓰거나, 스프링 실행기 빈을 주입받아 쓴다");

  @ArchTest
  static final ArchRule CompletableFuture_는_실행기를_명시한다 = noClasses()
      .should(callMethodWhere(EXECUTOR_LESS_COMPLETABLE_FUTURE))
      .because("executor 를 생략하면 ForkJoinPool.commonPool 로 가고 traceId 가 사라진다. "
          + "runAsync(task, executor) 처럼 실행기를 명시한다");

  @ArchTest
  static final ArchRule parallelStream_은_쓰지_않는다 = noClasses()
      .should(callMethodWhere(PARALLEL_STREAM))
      .because("parallelStream 은 commonPool 을 쓰고 실행기를 지정할 수단이 없어 traceId 가 사라진다. "
          + "순차 stream 을 쓰거나 실행기를 명시한 CompletableFuture 로 바꾼다");
}
