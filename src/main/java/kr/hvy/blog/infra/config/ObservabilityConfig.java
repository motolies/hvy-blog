package kr.hvy.blog.infra.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import kr.hvy.common.config.executor.ContextPropagationConfigurer;
import kr.hvy.common.config.executor.TaskExecutorConfigurer;
import kr.hvy.common.observability.TraceBoundary;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * traceId/spanId 전파 장치를 등록한다.
 * <p>
 * <b>왜 {@code TaskExecutorConfig} 가 아니라 별도 클래스인가</b> — {@code TaskExecutorConfig} 는
 * {@code @Profile("!test")} 라 테스트 컨텍스트에 올라오지 않는다. 여기 있는 세 빈은 전파의 뼈대라
 * 테스트에서도 그대로 있어야 회귀 테스트가 운영과 같은 조건을 검증한다
 * (특히 {@code TracePropagationIntegrationTest} 의 raw span 전파·BPP 자동 부착 단언).
 * {@code TraceBoundary} 는 {@code StockCollectStartupListener} 가 생성자로 요구하므로
 * 프로필과 무관하게 존재해야 컨텍스트가 뜬다.
 */
@Configuration
public class ObservabilityConfig {

  /**
   * Task 실행기 빈에 {@code TraceTaskDecorator} 를 자동 부착하는 BeanPostProcessor.
   * <p>
   * {@code static} 이어야 한다 — BeanPostProcessor 는 컨테이너 초기화 극초기에 필요한데, 인스턴스
   * {@code @Bean} 이면 이 설정 클래스와 그 의존성이 통째로 조기 초기화되어 다른 빈의 후처리를 놓친다.
   * 덕분에 {@code kisBackfillExecutor} 처럼 데코레이터를 명시하지 않은 실행기 빈도 자동 커버된다.
   */
  @Bean
  public static BeanPostProcessor traceTaskDecoratorBeanPostProcessor() {
    return TaskExecutorConfigurer.traceTaskDecoratorBeanPostProcessor();
  }

  /**
   * raw span(tracer.nextSpan/withSpan) 경계도 자식 스레드로 전파되도록 ThreadLocalAccessor 를 수동 등록한다.
   * ServiceLoader 는 ObservationThreadLocalAccessor 하나만 자동 등록하므로 이 빈이 없으면 수동 span 하위의
   * 비동기 로그에서 traceId 가 사라진다.
   */
  @Bean
  public ContextPropagationConfigurer contextPropagationConfigurer(ObservationRegistry observationRegistry, Tracer tracer) {
    return new ContextPropagationConfigurer(observationRegistry, tracer);
  }

  /**
   * 프레임워크가 경계를 만들어 주지 않는 진입점(기동 리스너, Redisson 캐시 무효화 콜백 등)에서 쓰는 경계 프리미티브.
   * hvy-common 의 {@code CacheInvalidationConfig} 가 {@code ObjectProvider} 로 선택 주입하므로,
   * 이 빈이 있어야 캐시 무효화 리스너 로그에도 traceId 가 붙는다.
   */
  @Bean
  public TraceBoundary traceBoundary(ObservationRegistry observationRegistry) {
    return new TraceBoundary(observationRegistry);
  }
}
