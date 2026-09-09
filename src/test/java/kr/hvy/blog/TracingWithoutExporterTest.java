package kr.hvy.blog;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import kr.hvy.blog.common.AbstractTestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>span exporter 가 없어도 traceId 는 생성된다</b>는 이 프로젝트의 전제를 못 박는다.
 * <p>
 * 트레이싱을 붙여 둔 목적이 외부 트레이싱 서버가 아니라 <b>로그 상관관계</b>이기 때문이다 —
 * {@code tb_system_log.trace_id}, Slack 오류 알림의 traceId, 프론트(BFF)와의 W3C traceparent 연결이
 * 전부 traceId 하나에 걸려 있다. 그래서 exporter 는 일부러 두지 않는다(2026-08-30 Zipkin 제거).
 * <p>
 * 이 테스트가 깨진다면 트레이싱 의존성에서 <b>브리지</b>({@code micrometer-tracing-bridge-otel})나
 * auto-config({@code spring-boot-micrometer-tracing-opentelemetry})가 빠진 것이다 — exporter 와 달리
 * 그 둘은 없으면 traceId 자체가 사라진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("exporter 없는 트레이싱")
class TracingWithoutExporterTest extends AbstractTestContainers {

  @Autowired
  private Tracer tracer;

  @Test
  @DisplayName("Tracer 빈이 존재하고 span 에서 유효한 traceId 를 얻는다")
  void tracerProducesTraceId() {
    assertThat(tracer).isNotNull();

    Span span = tracer.nextSpan().name("test").start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      String traceId = span.context().traceId();

      // 32자리 16진수 — 샘플링되지 않아도(test 프로필은 probability 0.0) traceId 는 유효해야 한다.
      assertThat(traceId).isNotBlank().hasSize(32).matches("[0-9a-f]+");
      assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");

      // 로그 MDC 로 나가는 경로도 같은 값이어야 한다 — 이게 달라지면 로그 상관관계가 깨진다.
      assertThat(tracer.currentSpan()).isNotNull();
      assertThat(tracer.currentSpan().context().traceId()).isEqualTo(traceId);

      // logback 패턴 %X{traceId},%X{spanId} 가 실제로 읽는 값. Slf4JEventListener 가 스코프 이벤트로 채운다.
      assertThat(MDC.get("traceId")).isEqualTo(traceId);
      assertThat(MDC.get("spanId")).isEqualTo(span.context().spanId());
    } finally {
      span.end();
    }
  }
}
