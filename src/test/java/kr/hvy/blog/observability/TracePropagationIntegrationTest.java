package kr.hvy.blog.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.hvy.blog.common.AbstractTestContainers;
import kr.hvy.blog.modules.stock.application.service.CollectExecution;
import kr.hvy.blog.modules.stock.application.service.ConcurrentTargetRunner;
import kr.hvy.common.config.executor.TraceTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>traceId MDC 전파 회귀 테스트.</b>
 * <p>
 * 요구사항이 "어느 스레드에서 찍히든 모든 로그에 traceId 가 있는가"이므로, 단언 대상은
 * {@code tracer.currentSpan()} 이 아니라 <b>{@link ILoggingEvent#getMDCPropertyMap()}</b> 이다.
 * logback 패턴 {@code %X{traceId}} 가 읽는 값과 1:1 로 대응한다.
 * <p>
 * {@code TestTaskExecutorConfig} 는 {@code virtualThreadExecutor}·{@code kisBackfillExecutor} 를
 * {@code SyncTaskExecutor} 로 갈아끼워 <b>스레드 경계 자체를 없앤다</b>. 다른 테스트가 그 동기 실행에
 * 의존하므로 건드리지 않고, 이름이 다른 프로브 실행기를 {@link ProbeConfig} 로 띄워 진짜 스레드 경계를 만든다.
 * <p>
 * <b>여전히 "유실"로 단언하는 케이스가 있다([4-b], [4-c]).</b> 그건 버그가 아니라 이 구조의 경계선이다 —
 * Spring 이 모르는 풀(직접 만든 {@code Executors.new*}, {@code ForkJoinPool.commonPool})에는 붙일 자리가
 * 없다. 그래서 {@code TraceExecutors} 를 쓰라는 규칙({@link ConcurrencyArchTest})이 필요하다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("traceId MDC 전파")
class TracePropagationIntegrationTest extends AbstractTestContainers {

  /** 프로브 전용 로거 — 다른 로그와 섞이지 않도록 이름을 따로 둔다. */
  private static final String PROBE_LOGGER_NAME = "kr.hvy.blog.observability.TRACE_PROBE";
  private static final Logger PROBE_LOG = LoggerFactory.getLogger(PROBE_LOGGER_NAME);

  private static final String TRACE_ID = "traceId";
  private static final String SPAN_ID = "spanId";

  @Autowired
  private Tracer tracer;

  @Autowired
  private ObservationRegistry observationRegistry;

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private ConcurrentTargetRunner concurrentTargetRunner;

  /** 운영 virtualThreadExecutor 와 동일 구성(ConcurrentTaskExecutor + TraceTaskDecorator). */
  @Autowired
  @Qualifier("traceProbeDecorated")
  private TaskExecutor decoratedProbe;

  /** 데코레이터를 <b>명시하지 않은</b> 실행기 빈 — BPP 자동 부착의 검증 대상(kisBackfillExecutor 재현). */
  @Autowired
  @Qualifier("traceProbePlain")
  private TaskExecutor plainProbe;

  private MdcCapturingAppender appender;

  /**
   * 프로브 로거에 MDC 캡처 appender 를 붙인다.
   * <p>
   * {@code setAdditive(false)} 로 루트 appender 를 타지 않게 해 콘솔 오염을 막는다.
   */
  @BeforeEach
  void attachAppender() {
    ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger) PROBE_LOG;
    logbackLogger.detachAndStopAllAppenders();
    logbackLogger.setLevel(Level.INFO);
    logbackLogger.setAdditive(false);

    appender = new MdcCapturingAppender();
    appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    appender.start();
    logbackLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger) PROBE_LOG;
    logbackLogger.detachAndStopAllAppenders();
    logbackLogger.setAdditive(true);
  }

  // ---------------------------------------------------------------------------------------------
  // 1. 가설 B(샘플링이 원인이다) 기각 — sampling 0.0 이어도 스코프 안이면 MDC 가 채워진다
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("[1] sampling 0.0 이어도 span 스코프 안의 로그에는 traceId 가 찍힌다")
  void mdcIsPopulatedInsideSpanScopeEvenWhenSamplingIsZero() {
    Span span = tracer.nextSpan().name("probe.sampling");
    String traceId;
    try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
      traceId = span.context().traceId();

      // 스코프를 연 스레드의 MDC — Slf4JEventListener 가 스코프 이벤트로 채운다(샘플링 분기 없음).
      assertThat(MDC.get(TRACE_ID)).isEqualTo(traceId);

      PROBE_LOG.info("in-scope");
    } finally {
      span.end();
    }

    assertThat(events()).hasSize(1);
    assertThat(mdcOf(0))
        .as("test 프로필은 management.tracing.sampling.probability=0.0 이지만 MDC 는 채워진다")
        .containsEntry(TRACE_ID, traceId)
        .containsKey(SPAN_ID);

    // 스코프를 닫으면 MDC 도 비워진다 = "스코프 밖" == "traceId 없는 로그".
    assertThat(MDC.get(TRACE_ID)).isNull();
  }

  // ---------------------------------------------------------------------------------------------
  // 2. 메커니즘 — 어떤 ThreadLocalAccessor 가 등록돼 있는가
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("[2] Observation accessor(자동)와 tracing accessor(수동 등록)가 모두 있다")
  void bothObservationAndTracingAccessorsAreRegistered() {
    List<String> keys = ContextRegistry.getInstance().getThreadLocalAccessors().stream()
        .map(ThreadLocalAccessor::key)
        .map(String::valueOf)
        .toList();

    // micrometer-observation.jar 만 META-INF/services/io.micrometer.context.ThreadLocalAccessor 를 갖는다.
    assertThat(keys).as("등록된 ThreadLocalAccessor 키 = %s", keys)
        .contains(ObservationThreadLocalAccessor.KEY);

    // micrometer-tracing.jar 에는 그 services 파일이 없고 Boot auto-config 도 등록하지 않는다.
    // ContextPropagationConfigurer(hvy-common) 가 수동 등록해야만 raw span 이 스냅샷에 잡힌다 → [4] 의 전제.
    assertThat(keys).as("등록된 ThreadLocalAccessor 키 = %s", keys)
        .contains(ObservationAwareSpanThreadLocalAccessor.KEY);
  }

  // ---------------------------------------------------------------------------------------------
  // 3~4. 전파되어야 하는 경로
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("[3] Observation 경계 → 데코레이터 실행기 → 자식 스레드 로그에 같은 traceId")
  void observationBoundaryPropagatesMdcToChildThread() {
    AtomicReference<String> parentTraceId = new AtomicReference<>();

    Observation.createNotStarted("probe.observation", observationRegistry).observe(() -> {
      parentTraceId.set(MDC.get(TRACE_ID));
      logInChildThread(decoratedProbe);
    });

    assertThat(parentTraceId.get()).as("Observation 경계 안 부모 스레드의 MDC").isNotBlank();
    assertThat(events()).hasSize(1);
    assertThat(threadNameOf(0)).as("실제로 다른 스레드에서 실행됐는지").startsWith("probe-dec-");
    assertThat(mdcOf(0))
        .as("ObservationThreadLocalAccessor 가 잡아 자식 스레드에서 스코프를 재개한다")
        .containsEntry(TRACE_ID, parentTraceId.get());
  }

  @Test
  @DisplayName("[4] raw tracer.withSpan 경계 → 자식 스레드도 부모와 같은 traceId (accessor 수동 등록 효과)")
  void rawSpanBoundaryPropagatesMdcToChildThread() {
    Span span = tracer.nextSpan().name("scheduler.PROBE");
    String parentTraceId;
    try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
      parentTraceId = MDC.get(TRACE_ID);
      // 부모 스레드에는 traceId 가 분명히 있다 — AbstractScheduler 의 "### Scheduler.X start ###" 로그가 이 상태.
      assertThat(parentTraceId).isEqualTo(span.context().traceId());

      logInChildThread(decoratedProbe);
    } finally {
      span.end();
    }

    assertThat(events()).hasSize(1);
    assertThat(threadNameOf(0)).startsWith("probe-dec-");

    // 이 단언이 이번 작업의 핵심이다. captureAll() 은 MDC 맵을 복사하지 않고 등록된 accessor 로만 컨텍스트를
    // 옮기므로, ContextPropagationConfigurer 가 ObservationAwareSpanThreadLocalAccessor 를 등록하지 않으면
    // Observation 이 아닌 raw span 은 스냅샷에 잡히지 않아 자식 스레드 MDC 가 통째로 비어 있었다.
    assertThat(mdcOf(0))
        .as("raw span 하위 자식 스레드도 부모 traceId 를 갖는다 (실측 MDC = %s)", mdcOf(0))
        .containsEntry(TRACE_ID, parentTraceId)
        .containsKey(SPAN_ID);
  }

  @Test
  @DisplayName("[4-a] 데코레이터를 명시하지 않은 실행기 빈도 BPP 자동 부착으로 전파된다")
  void plainExecutorBeanIsAutoDecoratedByBeanPostProcessor() {
    AtomicReference<String> parentTraceId = new AtomicReference<>();

    Observation.createNotStarted("probe.plainExecutor", observationRegistry).observe(() -> {
      parentTraceId.set(MDC.get(TRACE_ID));
      logInChildThread(plainProbe);
    });

    assertThat(parentTraceId.get()).isNotBlank();
    assertThat(events()).hasSize(1);
    assertThat(threadNameOf(0)).startsWith("probe-plain-");

    // traceProbePlain 은 setTaskDecorator 를 호출하지 않은 ConcurrentTaskExecutor 빈이다.
    // TraceTaskDecoratorBeanPostProcessor 가 빈 초기화 전에 데코레이터를 심어 주므로 전파된다
    // → kisBackfillExecutor 처럼 코드를 한 줄도 안 고친 실행기 빈이 자동 커버된다는 증거.
    assertThat(mdcOf(0))
        .as("BPP 가 데코레이터를 자동 부착한다 (실측 MDC = %s)", mdcOf(0))
        .containsEntry(TRACE_ID, parentTraceId.get());
  }

  @Test
  @DisplayName("[4-d] ConcurrentTargetRunner 종목 작업 로그에 부모 traceId 가 그대로 있다")
  void concurrentTargetRunnerPropagatesTraceIdToTargetThreads() {
    // 취소 판정만 쓰므로(기본 false) 실제 run/체크포인트 없이 목으로 충분하다.
    CollectExecution execution = mock(CollectExecution.class);
    List<String> targets = List.of("005930", "000660");

    Span span = tracer.nextSpan().name("scheduler.STOCK-PROBE");
    String parentTraceId;
    try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
      parentTraceId = MDC.get(TRACE_ID);
      concurrentTargetRunner.run(execution, targets, target -> PROBE_LOG.info("target={}", target));
    } finally {
      span.end();
    }

    assertThat(events()).hasSize(targets.size());
    assertThat(events()).allSatisfy(event -> {
      // TraceExecutors.virtualThreadPerTask("kis-target-") 로 만든 풀에서 돌았는지
      assertThat(event.getThreadName()).startsWith("kis-target-");
      assertThat(event.getMDCPropertyMap()).containsEntry(TRACE_ID, parentTraceId);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // 4-b·4-c. 유실이 정상인 경로 — Spring 이 모르는 풀에는 붙일 자리가 없다
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("[4-b] 자체 생성 풀(Executors.newVirtualThreadPerTaskExecutor)은 여전히 유실된다")
  void selfCreatedVirtualThreadPoolStillLosesMdc() {
    AtomicReference<String> parentTraceId = new AtomicReference<>();

    // ConcurrentTargetRunner 가 예전에 쓰던 형태를 그대로 재현한다.
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Observation.createNotStarted("probe.rawPool", observationRegistry).observe(() -> {
        parentTraceId.set(MDC.get(TRACE_ID));
        logInChildThread(pool);
      });
    }

    assertThat(parentTraceId.get()).isNotBlank();
    assertThat(events()).hasSize(1);
    assertThat(threadNameOf(0)).as("호출 스레드와 달라야 한다").isNotEqualTo(Thread.currentThread().getName());

    // 여기는 고칠 수 없는 지점이다 — 빈이 아니라 BPP 가 못 보고, 데코레이터를 걸 API 도 없다.
    // 따라서 "TraceExecutors 를 쓰라"는 ConcurrencyArchTest 규칙이 유일한 방어선이며,
    // 이 단언은 그 규칙이 왜 필요한지를 실측으로 남겨 둔 것이다. 전파에 성공하면 오히려 전제가 바뀐 것이다.
    assertThat(mdcOf(0))
        .as("Spring 이 모르는 자체 풀은 traceId 를 잃는다 (실측 MDC = %s)", mdcOf(0))
        .doesNotContainKey(TRACE_ID)
        .isEmpty();
  }

  @Test
  @DisplayName("[4-c] executor 미지정 CompletableFuture(ForkJoinPool.commonPool)는 여전히 유실된다")
  void commonPoolStillLosesMdc() {
    AtomicReference<String> parentTraceId = new AtomicReference<>();

    // executor 를 생략한 CompletableFuture.runAsync 재현 — commonPool 로 간다.
    Observation.createNotStarted("probe.commonPool", observationRegistry).observe(() -> {
      parentTraceId.set(MDC.get(TRACE_ID));
      CompletableFuture.runAsync(() -> PROBE_LOG.info("child")).join();
    });

    assertThat(parentTraceId.get()).isNotBlank();
    assertThat(events()).hasSize(1);
    assertThat(threadNameOf(0)).as("commonPool 워커에서 실행됐는지").contains("ForkJoinPool.commonPool");

    // [4-b] 와 같은 이유로 유실이 정상이다. ConcurrencyArchTest 가 이 형태를 금지한다.
    assertThat(mdcOf(0))
        .as("ForkJoinPool.commonPool 은 traceId 를 잃는다 (실측 MDC = %s)", mdcOf(0))
        .doesNotContainKey(TRACE_ID)
        .isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // 5. Boot 4.1 실측 — @Scheduled 진입 시점에 이미 traceId 가 있는가
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("[5] @Scheduled 진입 시점에 이미 traceId 가 있다 (Boot 4.1 자동 Observation 래핑)")
  void scheduledMethodEntryAlreadyHasTraceId() throws InterruptedException {
    assertThat(ScheduledProbe.INVOKED.await(20, TimeUnit.SECONDS))
        .as("@Scheduled 프로브가 실행되지 않았다")
        .isTrue();

    // 근거: ScheduledTasksObservationAutoConfiguration 이 taskRegistrar.setObservationRegistry 를 호출하고,
    //      Spring 7 ScheduledMethodRunnable.run() 이 TASKS_SCHEDULED_EXECUTION Observation 으로 감싼다.
    //      → platformTaskScheduler 에 TaskDecorator 를 붙일 필요가 없다(붙이면 스냅샷이 기동 시점에 고정되는 버그).
    assertThat(applicationContext.getBeansOfType(SchedulingConfigurer.class).values())
        .as("Boot 의 ObservabilitySchedulingConfigurer 가 등록돼 있어야 한다")
        .anySatisfy(configurer ->
            assertThat(configurer.getClass().getName()).contains("ScheduledTasksObservationAutoConfiguration"));

    assertThat(ScheduledProbe.MDC_AT_ENTRY)
        .as("@Scheduled 메서드 진입 시점의 MDC = %s", ScheduledProbe.MDC_AT_ENTRY)
        .containsKey(TRACE_ID)
        .containsKey(SPAN_ID);
    assertThat(ScheduledProbe.MDC_AT_ENTRY.get(TRACE_ID))
        .as("스케줄러 진입 traceId 는 유효한 32자리 16진수여야 한다")
        .hasSize(32)
        .matches("[0-9a-f]+")
        .isNotEqualTo("00000000000000000000000000000000");
  }

  // ---------------------------------------------------------------------------------------------
  // 헬퍼
  // ---------------------------------------------------------------------------------------------

  /** 자식 스레드에서 프로브 로그를 1건 남기고 완료를 기다린다. */
  private void logInChildThread(Executor executor) {
    CountDownLatch done = new CountDownLatch(1);
    executor.execute(() -> {
      try {
        PROBE_LOG.info("child");
      } finally {
        done.countDown();
      }
    });
    try {
      assertThat(done.await(10, TimeUnit.SECONDS)).as("자식 스레드 작업이 끝나지 않았다").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private List<ILoggingEvent> events() {
    return appender.events;
  }

  private Map<String, String> mdcOf(int index) {
    return appender.events.get(index).getMDCPropertyMap();
  }

  private String threadNameOf(int index) {
    return appender.events.get(index).getThreadName();
  }

  /**
   * 로깅 스레드에서 MDC 스냅샷을 확정해 두는 appender.
   * <p>
   * {@code LoggingEvent#getMDCPropertyMap()} 은 지연 평가라 나중에(테스트 스레드에서) 읽으면 엉뚱한 MDC 를
   * 보게 된다. {@code prepareForDeferredProcessing()} 은 logback 이 비동기 appender 에서 쓰는 것과 같은
   * 방식으로 이벤트 발생 시점의 MDC·스레드명을 고정한다 → {@code %X{traceId}} 출력과 1:1 로 대응한다.
   */
  private static final class MdcCapturingAppender extends AppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent eventObject) {
      eventObject.prepareForDeferredProcessing();
      events.add(eventObject);
    }
  }

  /**
   * 프로브 빈 설정.
   * <p>
   * {@code TestTaskExecutorConfig} 의 {@code SyncTaskExecutor} 교체를 우회하려고 <b>이름이 다른</b>
   * 실행기를 추가한다. 기존 빈은 그대로 두므로 다른 테스트의 동기 실행 가정은 깨지지 않는다.
   */
  @TestConfiguration
  static class ProbeConfig {

    /** 운영 virtualThreadExecutor 와 동일 구성 — TraceTaskDecorator 를 명시 부착. */
    @Bean("traceProbeDecorated")
    TaskExecutor traceProbeDecorated() {
      ConcurrentTaskExecutor exec = new ConcurrentTaskExecutor(
          Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("probe-dec-", 0).factory()));
      exec.setTaskDecorator(new TraceTaskDecorator());
      return exec;
    }

    /**
     * 데코레이터를 <b>명시하지 않은</b> 실행기 빈 (kisBackfillExecutor 재현).
     * TraceTaskDecoratorBeanPostProcessor 가 자동으로 부착해 주는지를 [4-a] 가 검증한다.
     */
    @Bean("traceProbePlain")
    TaskExecutor traceProbePlain() {
      // 가상 스레드는 데몬이고 태스크마다 새로 만들어지므로 별도 shutdown 없이 테스트 컨텍스트와 함께 사라진다.
      return new ConcurrentTaskExecutor(
          Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("probe-plain-", 0).factory()));
    }

    @Bean
    ScheduledProbe scheduledProbe() {
      return new ScheduledProbe();
    }
  }

  /**
   * {@code @Scheduled} 진입 시점의 MDC 를 1회만 채집한다.
   * <p>
   * {@code BlogApplication} 에 {@code @EnableScheduling} 이 있어 테스트 컨텍스트에서도 실제로 스케줄된다.
   */
  static class ScheduledProbe {

    static final CountDownLatch INVOKED = new CountDownLatch(1);
    static final Map<String, String> MDC_AT_ENTRY = new ConcurrentHashMap<>();

    @Scheduled(initialDelay = 100L, fixedDelay = 86_400_000L)
    public void captureMdc() {
      if (INVOKED.getCount() == 0) {
        return;
      }
      Map<String, String> snapshot = MDC.getCopyOfContextMap();
      if (snapshot != null) {
        MDC_AT_ENTRY.putAll(snapshot);
      }
      INVOKED.countDown();
    }
  }
}
