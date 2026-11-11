package kr.hvy.blog.modules.advisor.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 게이트의 차단 시간·만료·차단 범위(계정/모델)·늦은 성공·알림 중복 억제. 시계는 UTC 존의 가변 시계다 — 게이트가 시계의 존이 아니라 스스로 KST 를 적용해 "하루" 를 가르는지를 함께 본다.
 */
@DisplayName("LlmProviderGate - 1순위 공급자 차단 상태·분류별 하루 1회 알림")
class LlmProviderGateTest {

  /** 23:30 KST. 30분 뒤에 KST 날짜가 바뀐다(UTC 로는 같은 날 14:30 → 15:00) */
  static final Instant START = Instant.parse("2026-11-11T14:30:00Z");
  static final Duration EXHAUSTED_COOLDOWN = Duration.ofMinutes(60);
  static final Duration UNAVAILABLE_COOLDOWN = Duration.ofMinutes(5);
  static final Duration MISCONFIGURED_COOLDOWN = Duration.ofMinutes(30);
  /** 이 테스트의 호출이 쓰는 모델 ID(판단 역할) */
  static final String MODEL = "claude-judge";
  /** 다른 역할(채팅)이 쓰는 모델 ID */
  static final String OTHER_MODEL = "claude-chat";

  /** 테스트가 시각을 정하고 앞으로 돌리는 시계. 동시성 테스트에서도 읽으므로 volatile */
  static final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    void set(Instant instant) {
      now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  record Alert(String text, boolean error) {

  }

  static LlmProviderException failure(LlmFailureKind kind, int status, String message) {
    return new LlmProviderException(LlmProvider.CLAUDE, kind, status, null, message, null);
  }

  final MutableClock clock = new MutableClock(START);
  final List<Alert> alerts = new CopyOnWriteArrayList<>();
  final LlmProviderGate gate = new LlmProviderGate(clock, EXHAUSTED_COOLDOWN, UNAVAILABLE_COOLDOWN, MISCONFIGURED_COOLDOWN,
      (text, error) -> alerts.add(new Alert(text, error)));

  @ParameterizedTest(name = "{0} → {1}분")
  @CsvSource({"EXHAUSTED,60", "UNAVAILABLE,5", "MISCONFIGURED,30"})
  @DisplayName("폴백 대상 분류는 분류별 시간만큼 차단하고 만료 시각이 되면 풀린다 — 그 뒤 첫 호출이 프로브다")
  void 분류별_차단시간과_만료(LlmFailureKind kind, long minutes) {
    assertThat(gate.isBlocked(MODEL)).as("처음에는 차단 아님").isFalse();

    gate.recordFailure(failure(kind, 500, "boom"), MODEL, null);

    LlmProviderGate.Block block = gate.currentBlock(MODEL).orElseThrow();
    assertThat(block.kind()).isEqualTo(kind);
    assertThat(block.until()).isEqualTo(START.plus(Duration.ofMinutes(minutes)));

    clock.advance(Duration.ofMinutes(minutes).minusSeconds(1));
    assertThat(gate.isBlocked(MODEL)).as("만료 1초 전").isTrue();

    clock.advance(Duration.ofSeconds(1));
    assertThat(gate.isBlocked(MODEL)).as("만료 시각").isFalse();
    assertThat(gate.currentBlock(MODEL)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(LlmFailureKind.class)
  @DisplayName("차단·알림 여부는 분류의 fallbackable 과 같다 — REQUEST_INVALID 는 차단도 알림도 없다")
  void 폴백대상만_차단(LlmFailureKind kind) {
    gate.recordFailure(failure(kind, 400, "boom"), MODEL, null);

    assertThat(gate.isBlocked(MODEL)).isEqualTo(kind.isFallbackable());
    assertThat(alerts).hasSize(kind.isFallbackable() ? 1 : 0);
  }

  @Test
  @DisplayName("차단 기록은 공급자·분류·상태와 원인 요약(예외 메시지에서 '<공급자> <status>: ' 접두사를 뗀 것)을 기억한다")
  void 차단_정보() {
    gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "Your credit balance is too low"), MODEL, null);

    assertThat(gate.currentBlock(MODEL)).contains(new LlmProviderGate.Block(LlmProvider.CLAUDE, LlmFailureKind.EXHAUSTED, 402,
        "Your credit balance is too low", START.plus(EXHAUSTED_COOLDOWN), null));
  }

  @Test
  @DisplayName("같은 날 같은 분류는 차단이 풀렸다 다시 걸려도 알림 1통, 다른 분류는 따로 1통")
  void 알림_분류별_하루_한통() {
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);
    clock.advance(UNAVAILABLE_COOLDOWN.plusMinutes(1));
    assertThat(gate.isBlocked(MODEL)).isFalse();
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);

    assertThat(gate.isBlocked(MODEL)).as("프로브가 실패해 다시 차단").isTrue();
    assertThat(alerts).as("같은 날 같은 분류").hasSize(1);

    gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), MODEL, null);
    gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), MODEL, null);

    assertThat(alerts).as("다른 분류는 별도").hasSize(2);
    assertThat(alerts.get(1).text()).contains("EXHAUSTED");
  }

  @Test
  @DisplayName("KST 자정을 넘기면 같은 분류도 다시 1통 — UTC 로는 같은 날이어도")
  void 알림_KST_자정_경계() {
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);
    clock.advance(Duration.ofMinutes(29));
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);
    assertThat(alerts).as("23:59 KST — 같은 날").hasSize(1);

    clock.advance(Duration.ofMinutes(2));
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);
    assertThat(alerts).as("00:01 KST — 다음 날(UTC 는 아직 11-11)").hasSize(2);
  }

  @Test
  @DisplayName("UTC 자정을 넘겨도 KST 로 같은 날이면 다시 알리지 않는다")
  void 알림_UTC_자정은_경계가_아니다() {
    clock.set(Instant.parse("2026-11-11T23:50:00Z"));
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);
    clock.advance(Duration.ofMinutes(20));
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);

    assertThat(alerts).as("08:50 → 09:10 KST, 같은 날").hasSize(1);
  }

  @Test
  @DisplayName("MISCONFIGURED 만 error=true(사람이 고쳐야 함), 소진·일시 장애는 false")
  void 알림_error_여부() {
    gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), MODEL, null);
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);
    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key"), MODEL, null);

    assertThat(alerts).extracting(Alert::error).containsExactly(false, false, true);
  }

  @Test
  @DisplayName("알림 문구에 공급자·분류·차단 범위·상태 코드·원인 요약·다음 시도 시각(KST)이 실린다")
  void 알림_문구() {
    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key"), MODEL, null);

    assertThat(alerts.get(0).text())
        .contains("CLAUDE", "MISCONFIGURED", LlmFailureKind.MISCONFIGURED.getDesc(), "범위: 계정 전체", "HTTP 401", "원인: invalid x-api-key")
        .as("23:30 KST + 30분").contains("2026-11-12 00:00 KST");
  }

  @Test
  @DisplayName("응답을 받지 못한 실패(status 0)는 상태를 '응답 없음' 으로, 길거나 여러 줄인 오류 본문은 한 줄 200자로 줄여 싣는다")
  void 알림_문구_요약() {
    String body = "Read timed out\n  at line 1\n" + "x".repeat(500);
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 0, body), MODEL, null);

    String reason = gate.currentBlock(MODEL).orElseThrow().reason();
    assertThat(reason).hasSize(LlmProviderGate.REASON_MAX_LENGTH).startsWith("Read timed out at line 1 xxx").endsWith("...").doesNotContain("\n");
    assertThat(alerts.get(0).text()).contains("응답 없음").doesNotContain("HTTP 0").contains("원인: " + reason);
    assertThat(alerts.get(0).text().lines()).as("오류 본문의 줄바꿈이 알림 형식을 깨지 않는다").hasSize(5);
  }

  @Test
  @DisplayName("알림 콜백이 던져도 호출 흐름은 깨지지 않고 차단은 걸린다 — 실패한 알림은 다음 실패 때 다시 시도한다")
  void 알림_콜백_예외() {
    AtomicInteger attempts = new AtomicInteger();
    LlmProviderGate throwing = new LlmProviderGate(clock, EXHAUSTED_COOLDOWN, UNAVAILABLE_COOLDOWN, MISCONFIGURED_COOLDOWN, (text, error) -> {
      if (attempts.incrementAndGet() == 1) {
        throw new IllegalStateException("slack down");
      }
      return true;
    });

    assertThatCode(() -> throwing.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key"), MODEL, null)).doesNotThrowAnyException();
    assertThat(throwing.isBlocked(MODEL)).isTrue();

    throwing.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key"), MODEL, null);
    assertThat(attempts).as("첫 알림이 실패했으므로 같은 날이어도 다시 보낸다").hasValue(2);

    throwing.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key"), MODEL, null);
    assertThat(attempts).as("전달된 뒤로는 하루 1회").hasValue(2);
  }

  @Test
  @DisplayName("알림 콜백이 '전달 못 함'(false)을 돌려주면 그날의 표시를 되돌려 다음 실패 때 다시 보낸다 — 운영 콜백은 전송 실패를 삼키고 false 를 준다")
  void 알림_미전달_재시도() {
    AtomicInteger attempts = new AtomicInteger();
    LlmProviderGate undelivered = new LlmProviderGate(clock, EXHAUSTED_COOLDOWN, UNAVAILABLE_COOLDOWN, MISCONFIGURED_COOLDOWN,
        (text, error) -> attempts.incrementAndGet() > 1);

    undelivered.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key"), MODEL, null);
    assertThat(undelivered.isBlocked(MODEL)).as("알림이 전달되지 않아도 차단은 걸린다").isTrue();

    undelivered.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key"), MODEL, null);
    assertThat(attempts).as("첫 알림이 전달되지 않았으므로 같은 날이어도 다시 보낸다").hasValue(2);

    undelivered.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key"), MODEL, null);
    assertThat(attempts).as("전달된 뒤로는 하루 1회").hasValue(2);
  }

  @Test
  @DisplayName("recordSuccess 는 차단을 지운다 — 차단 기록이 없을 때 불러도 무해하다")
  void 성공하면_해제() {
    gate.recordSuccess(gate.beginCall(), MODEL);
    assertThat(gate.isBlocked(MODEL)).isFalse();

    gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), MODEL, null);
    assertThat(gate.isBlocked(MODEL)).isTrue();

    gate.recordSuccess(gate.beginCall(), MODEL);
    assertThat(gate.isBlocked(MODEL)).isFalse();
    assertThat(gate.currentBlock(MODEL)).isEmpty();
  }

  @Test
  @DisplayName("늦은 성공: 호출이 떠난 뒤 다른 역할이 기록한 차단은 그 호출의 성공이 지우지 않는다 — 그 뒤에 시작한 호출의 성공은 지운다")
  void 늦은_성공은_그_뒤의_차단을_지우지_않는다() {
    long judgeCall = gate.beginCall();
    gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), OTHER_MODEL, null);

    gate.recordSuccess(judgeCall, MODEL);

    assertThat(gate.currentBlock(MODEL).orElseThrow().kind()).as("소진 차단이 먼저 떠난 호출의 성공으로 사라지지 않는다").isEqualTo(LlmFailureKind.EXHAUSTED);
    assertThat(gate.isBlocked(OTHER_MODEL)).isTrue();

    gate.recordSuccess(gate.beginCall(), MODEL);
    assertThat(gate.isBlocked(MODEL)).as("차단을 본 뒤에 시작한 호출(프로브)의 성공은 지운다").isFalse();
  }

  @Test
  @DisplayName("모델 없음(MISCONFIGURED 404)은 그 모델만 차단한다 — 다른 모델을 쓰는 역할은 그대로 1순위이고, 알림 문구에 모델 ID 가 실린다")
  void 모델없음은_그_모델만_차단() {
    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 404, "model: claude-chat"), OTHER_MODEL, null);

    LlmProviderGate.Block block = gate.currentBlock(OTHER_MODEL).orElseThrow();
    assertThat(block.scoped()).isTrue();
    assertThat(block.scope()).isEqualTo(OTHER_MODEL);
    assertThat(block.until()).isEqualTo(START.plus(MISCONFIGURED_COOLDOWN));
    assertThat(gate.isBlocked(MODEL)).as("다른 모델은 차단하지 않는다").isFalse();
    assertThat(gate.isBlocked()).as("범위를 주지 않은 호출은 계정 단위 차단만 본다").isFalse();
    assertThat(alerts).hasSize(1);
    assertThat(alerts.get(0).text()).contains("범위: 모델 " + OTHER_MODEL, "HTTP 404");
    assertThat(alerts.get(0).error()).isTrue();
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"MISCONFIGURED,401", "MISCONFIGURED,403", "EXHAUSTED,402", "UNAVAILABLE,529", "UNAVAILABLE,404"})
  @DisplayName("모델 없음이 아닌 실패는 계정 단위다 — 어느 모델로 부르든 차단된다")
  void 그밖의_실패는_계정_단위(LlmFailureKind kind, int status) {
    gate.recordFailure(failure(kind, status, "boom"), OTHER_MODEL, null);

    assertThat(gate.currentBlock(MODEL).orElseThrow().scoped()).isFalse();
    assertThat(gate.isBlocked(OTHER_MODEL)).isTrue();
    assertThat(gate.isBlocked()).isTrue();
  }

  @Test
  @DisplayName("모델 ID 를 모르는 호출의 404 는 계정 단위로 차단한다(모델 단위로 묶을 키가 없다)")
  void 모델을_모르면_404도_계정_단위() {
    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 404, "not found"), " ", null);

    assertThat(gate.currentBlock(MODEL).orElseThrow().scoped()).isFalse();
  }

  @Test
  @DisplayName("모델 단위 차단: 성공은 자기 모델의 차단만 지운다 — 다른 모델의 404 차단은 남는다")
  void 성공은_다른_모델의_차단을_지우지_않는다() {
    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 404, "model: claude-chat"), OTHER_MODEL, null);

    gate.recordSuccess(gate.beginCall(), MODEL);
    assertThat(gate.isBlocked(OTHER_MODEL)).as("판단 모델의 성공은 채팅 모델 ID 가 있다는 증거가 아니다").isTrue();

    gate.recordSuccess(gate.beginCall(), OTHER_MODEL);
    assertThat(gate.isBlocked(OTHER_MODEL)).isFalse();
  }

  @Test
  @DisplayName("모델 단위 알림은 모델별로 하루 1통이고, 계정 단위 MISCONFIGURED 알림과도 따로 센다")
  void 모델단위_알림은_모델별() {
    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 404, "model: claude-chat"), OTHER_MODEL, null);
    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 404, "model: claude-chat"), OTHER_MODEL, null);
    assertThat(alerts).as("같은 모델은 하루 1통").hasSize(1);

    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 404, "model: claude-judge"), MODEL, null);
    assertThat(alerts).as("다른 모델은 별도").hasSize(2);

    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key"), MODEL, null);
    assertThat(alerts).as("계정 단위 키 오류는 또 별도").hasSize(3);
  }

  /** 채팅 래퍼가 게이트에 주는 응답 없음 범위 이름 */
  static final String CHAT_SCOPE = "채팅 봇";

  @Test
  @DisplayName("응답 없음(UNAVAILABLE status 0)은 호출부가 범위를 줬으면 그 범위만 차단한다 — 더 오래 기다리는 역할(판단)은 그대로 1순위")
  void 응답없음은_준_범위만_차단() {
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 0, "응답 없음(타임아웃·연결 실패): Read timed out"), MODEL, CHAT_SCOPE);

    LlmProviderGate.Block block = gate.currentBlock(MODEL, CHAT_SCOPE).orElseThrow();
    assertThat(block.scope()).isEqualTo(CHAT_SCOPE);
    assertThat(block.until()).isEqualTo(START.plus(UNAVAILABLE_COOLDOWN));
    assertThat(gate.isBlocked(MODEL)).as("같은 모델을 쓰는 판단 호출은 차단되지 않는다").isFalse();
    assertThat(gate.isBlocked()).isFalse();
    assertThat(alerts).hasSize(1);
    assertThat(alerts.get(0).text()).contains("범위: " + CHAT_SCOPE + " 호출만", "응답을 받지 못했다", "응답 없음").doesNotContain("계정 전체");
    assertThat(alerts.get(0).error()).isFalse();
  }

  @Test
  @DisplayName("범위를 주지 않은 호출부(판단·보조)의 응답 없음은 계정 단위다 — 가장 오래 기다린 쪽이 못 받았으면 채팅도 차단된다")
  void 범위없는_응답없음은_계정_단위() {
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 0, "Read timed out"), MODEL, null);

    assertThat(gate.currentBlock(OTHER_MODEL, CHAT_SCOPE).orElseThrow().scoped()).isFalse();
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"UNAVAILABLE,529", "UNAVAILABLE,429", "EXHAUSTED,402", "MISCONFIGURED,401"})
  @DisplayName("범위를 준 호출부라도 서버가 답한 실패는 계정 단위다 — 범위 한정은 응답 없음에만 적용된다")
  void 범위를_줘도_서버가_답한_실패는_계정_단위(LlmFailureKind kind, int status) {
    gate.recordFailure(failure(kind, status, "boom"), MODEL, CHAT_SCOPE);

    assertThat(gate.currentBlock(OTHER_MODEL).orElseThrow().scoped()).isFalse();
  }

  @Test
  @DisplayName("범위 차단의 알림은 계정 단위 UNAVAILABLE 알림과 따로 센다 — 채팅 타임아웃이 그날의 장애 알림을 소모하지 않는다")
  void 범위_알림은_계정_알림과_별개() {
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 0, "Read timed out"), MODEL, CHAT_SCOPE);
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 0, "Read timed out"), MODEL, CHAT_SCOPE);
    assertThat(alerts).as("같은 범위는 하루 1통").hasSize(1);

    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);
    assertThat(alerts).as("진짜 장애 알림은 따로 나간다").hasSize(2);
    assertThat(alerts.get(1).text()).contains("범위: 계정 전체");
  }

  @Test
  @DisplayName("성공은 자기 범위들의 차단을 함께 지운다 — 채팅 호출의 성공이 채팅 범위 차단을 푼다")
  void 성공은_자기_범위들을_지운다() {
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 0, "Read timed out"), MODEL, CHAT_SCOPE);

    gate.recordSuccess(gate.beginCall(), MODEL);
    assertThat(gate.isBlocked(MODEL, CHAT_SCOPE)).as("판단 호출의 성공은 채팅 범위 차단을 풀지 않는다").isTrue();

    gate.recordSuccess(gate.beginCall(), MODEL, CHAT_SCOPE);
    assertThat(gate.isBlocked(MODEL, CHAT_SCOPE)).isFalse();
  }

  @Test
  @DisplayName("계정 단위 차단이 모델 단위 차단보다 먼저 보인다 — 폴백 사유가 계정 문제를 가리킨다")
  void 계정_차단이_먼저() {
    gate.recordFailure(failure(LlmFailureKind.MISCONFIGURED, 404, "model: claude-judge"), MODEL, null);
    gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), OTHER_MODEL, null);

    assertThat(gate.currentBlock(MODEL).orElseThrow().kind()).isEqualTo(LlmFailureKind.EXHAUSTED);
  }

  @Test
  @DisplayName("더 늦게 끝나는 차단이 있으면 짧은 분류의 실패가 그것을 줄이지 않는다 — 반대로 더 긴 차단은 덮어쓴다")
  void 차단은_줄어들지_않는다() {
    gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), MODEL, null);
    clock.advance(Duration.ofMinutes(1));
    gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 0, "먼저 떠난 호출의 타임아웃"), MODEL, null);

    LlmProviderGate.Block kept = gate.currentBlock(MODEL).orElseThrow();
    assertThat(kept.kind()).isEqualTo(LlmFailureKind.EXHAUSTED);
    assertThat(kept.until()).isEqualTo(START.plus(EXHAUSTED_COOLDOWN));
    assertThat(alerts).as("분류가 다르므로 알림은 각각").hasSize(2);
    assertThat(alerts.get(1).text()).as("다음 시도 시각은 실제로 적용된 차단의 것(23:30 + 60분)").contains("2026-11-12 00:30 KST");

    LlmProviderGate other = new LlmProviderGate(clock, EXHAUSTED_COOLDOWN, UNAVAILABLE_COOLDOWN, MISCONFIGURED_COOLDOWN, (text, error) -> true);
    other.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), MODEL, null);
    other.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), MODEL, null);
    assertThat(other.currentBlock(MODEL).orElseThrow().kind()).isEqualTo(LlmFailureKind.EXHAUSTED);
  }

  @Test
  @DisplayName("여러 역할이 동시에 같은 분류로 실패해도 알림은 한 통이다")
  void 동시_실패_알림_한통() throws InterruptedException {
    int threads = 16;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      Thread worker = new Thread(() -> {
        ready.countDown();
        try {
          go.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), MODEL, null);
      });
      workers.add(worker);
      worker.start();
    }
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    go.countDown();
    for (Thread worker : workers) {
      worker.join(5_000);
      assertThat(worker.isAlive()).isFalse();
    }

    assertThat(alerts).hasSize(1);
    assertThat(gate.currentBlock(MODEL).orElseThrow().kind()).isEqualTo(LlmFailureKind.EXHAUSTED);
  }

  @Test
  @DisplayName("생성 시 null·음수 차단 시간을 거부한다. 0 은 허용 — 차단 없이 알림만 보낸다")
  void 생성자_검증() {
    LlmProviderGate.AlertSink sink = (text, error) -> alerts.add(new Alert(text, error));

    assertThatThrownBy(() -> new LlmProviderGate(null, EXHAUSTED_COOLDOWN, UNAVAILABLE_COOLDOWN, MISCONFIGURED_COOLDOWN, sink))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new LlmProviderGate(clock, EXHAUSTED_COOLDOWN, null, MISCONFIGURED_COOLDOWN, sink))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new LlmProviderGate(clock, EXHAUSTED_COOLDOWN, UNAVAILABLE_COOLDOWN, MISCONFIGURED_COOLDOWN, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new LlmProviderGate(clock, Duration.ofMinutes(-1), UNAVAILABLE_COOLDOWN, MISCONFIGURED_COOLDOWN, sink))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exhaustedCooldown");

    LlmProviderGate noBlock = new LlmProviderGate(clock, Duration.ZERO, UNAVAILABLE_COOLDOWN, MISCONFIGURED_COOLDOWN, sink);
    noBlock.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), MODEL, null);
    assertThat(noBlock.isBlocked(MODEL)).isFalse();
    assertThat(alerts).hasSize(1);
  }
}
