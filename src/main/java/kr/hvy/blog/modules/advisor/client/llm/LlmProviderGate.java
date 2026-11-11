package kr.hvy.blog.modules.advisor.client.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 1순위 공급자 하나의 차단 상태(2026-11-11). 1순위 호출이 폴백 대상 분류로 실패하면 분류별 시간만큼 "차단" 으로 기억하고, 그동안
 * {@link FallbackChatModel} 은 1순위를 부르지 않고 곧바로 폴백 공급자로 간다 — 소진·장애가 확인된 공급자에 매 호출마다 타임아웃·재시도를 치르지 않기 위해서다.
 * <p>
 * <b>인스턴스 하나를 역할(judge/assist/chat)의 래퍼 셋이 공유한다.</b> 차단의 범위는 실패의 원인 범위를 따른다.
 * <ul>
 *   <li><b>계정 단위</b> — 크레딧 소진·키/권한 오류·일시 장애. 한 역할이 겪은 실패가 나머지 역할에도 그대로 적용된다</li>
 *   <li><b>모델 단위</b> — 모델 없음({@link LlmFailureKind#MISCONFIGURED} 이고 HTTP {@value #MODEL_NOT_FOUND_STATUS}). 그 모델 ID 를 쓰는 역할만 차단한다 —
 *       채팅 모델 ID 하나가 틀렸다고 판단까지 폴백으로 내려가지 않게(역할들이 같은 모델을 쓰면 결과는 계정 단위와 같다)</li>
 *   <li><b>호출 경로 단위</b> — 호출부가 "응답 없음 범위" 를 준 경우의 응답 없음({@link LlmFailureKind#UNAVAILABLE} 이고 status 0:
 *       타임아웃·연결 실패·응답 해석 실패·재시도 대기 인터럽트).
 *       응답 타임아웃이 짧은 경로(채팅 90초)가 답을 못 받았다는 것은 그 경로에 대한 증거일 뿐이라, 더 오래 기다리는 경로(판단 300초)까지
 *       차단하지 않는다. 범위를 주지 않은 호출부(판단·보조)의 응답 없음은 계정 단위다 — 가장 오래 기다린 쪽이 못 받았으면 모두 못 받는다</li>
 * </ul>
 * 계정 단위가 아닌 차단은 범위 키(모델 ID 또는 호출부가 준 범위 이름)별로 따로 기억한다.
 * <p>
 * <b>프로브.</b> 별도의 half-open 상태는 없다. 만료 시각이 지나면 차단이 아니므로 다음 호출이 1순위를 다시 시도하고, 성공하면 {@link #recordSuccess} 가
 * 기록을 지우고 실패하면 {@link #recordFailure} 가 다시 차단한다. 만료된 기록을 스스로 지우지 않는 것은 그 프로브가 성공했을 때 복귀를 로그로 남기기 위해서다.
 * <p>
 * <b>늦은 성공.</b> 성공은 "그 호출이 떠난 뒤 실패가 기록되지 않았을 때" 에만 차단을 지운다. 호출을 시작할 때 {@link #beginCall()} 로 실패 순번을 받아 두고
 * 성공 때 그 순번을 돌려준다 — 순번이 바뀌었으면 그 사이에 다른 호출(다른 역할)이 실패를 기록한 것이라, 먼저 떠난 호출의 성공이 그 차단을 지우면 안 된다
 * (소진 60분 차단이 2분 전에 떠난 판단 호출의 성공으로 사라지는 경우).
 * <p>
 * <b>알림.</b> 차단에 들어갈 때 알리되 분류별(범위가 한정된 차단은 범위별)로 KST 하루 1회만 보낸다({@code ClaudeCodeRefreshService.expiryWarnedOn} 과 같은 관용구) —
 * 차단이 풀릴 때마다 프로브가 다시 실패하므로 날짜 단위로 묶지 않으면 소진 하나에 하루 수십 통이 간다. 콜백이 전달하지 못했다고 답하면(또는 던지면)
 * 그날의 표시를 되돌려 다음 실패 때 다시 보낸다. 문구에는 자격 증명이 들어가지 않는다: 이 클래스는 키·토큰을 받지 않고,
 * 원인 문구는 공급자 클라이언트가 만든 예외 메시지(오류 본문 요약)를 잘라 쓴 것뿐이다.
 * <p>
 * <b>Redis 를 쓰지 않는다.</b> advisor 는 단일 인스턴스 전제라 인메모리로 충분하다. 재시작하면 상태가 사라지지만 그 대가는 1순위 프로브 1회와
 * 같은 날 알림 1통의 중복뿐이다(다중 인스턴스로 가면 그때 공유 저장소로 옮긴다).
 * <p>
 * 스레드 안전: 차단 기록과 실패 순번은 {@link #lock} 하나로 지킨다(순번 확인과 차단 해제가 한 임계 구역이어야 늦은 성공을 가를 수 있다).
 * 알림 날짜는 {@link ConcurrentHashMap} 이고 알림 콜백은 락을 잡지 않은 채 부른다(콜백이 Slack 전송이라 느릴 수 있다).
 */
@Slf4j
public class LlmProviderGate {

  /** 알림·로그에 싣는 원인 문구의 최대 길이 — 공급자 오류 본문이 통째로 Slack 에 붙지 않게 한다 */
  static final int REASON_MAX_LENGTH = 200;
  /** 모델 단위로 차단하는 HTTP 상태 — 요청한 모델 ID 가 없다(오타·은퇴) */
  static final int MODEL_NOT_FOUND_STATUS = 404;

  private static final DateTimeFormatter RETRY_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  /**
   * 차단 진입 알림을 받는 쪽. 운영에서는 {@code AdvisorNotifier.alert(String, boolean)} 이 연결된다 — error=true 면 사람이 고쳐야 하는 문제(#hvy-error + 멘션).
   */
  @FunctionalInterface
  public interface AlertSink {

    /**
     * @return 실제로 전달했는가. false 면(전송 실패를 삼켰거나 보낼 곳이 없으면) 게이트가 그날의 알림 표시를 되돌려 다음 실패 때 다시 보낸다
     */
    boolean alert(String text, boolean error);
  }

  /**
   * 차단 기록 한 건.
   *
   * @param provider 실패한 공급자
   * @param kind     실패 분류
   * @param status   HTTP 상태(응답을 받지 못했으면 0)
   * @param reason   원인 요약(공급자 오류 메시지를 한 줄로 줄이고 {@link #REASON_MAX_LENGTH} 자에서 자른 것)
   * @param until    이 시각이 지나면 차단이 아니다
   * @param scope    범위 키 — 모델 단위 차단이면 모델 ID, 호출 경로 단위 차단이면 호출부가 준 범위 이름, 계정 단위 차단이면 null
   */
  public record Block(LlmProvider provider, LlmFailureKind kind, int status, String reason, Instant until, String scope) {

    /**
     * 범위가 한정된 차단인가(아니면 계정 단위).
     */
    public boolean scoped() {
      return scope != null;
    }
  }

  private final Clock clock;
  private final Duration exhaustedCooldown;
  private final Duration unavailableCooldown;
  private final Duration misconfiguredCooldown;
  private final AlertSink alertSink;

  /** 차단 기록과 실패 순번을 지키는 락. 알림 콜백은 이 락 밖에서 부른다 */
  private final Object lock = new Object();
  /** 계정 단위 차단({@link #lock}) */
  private Block accountBlock;
  /** 범위 키(모델 ID·호출 경로 이름) → 범위가 한정된 차단({@link #lock}). 키는 설정에서 오는 값뿐이라 늘어나지 않는다 */
  private final Map<String, Block> scopedBlocks = new HashMap<>();
  /** 지금까지 기록한 실패 수({@link #lock}) — 늦은 성공을 가리는 순번 */
  private long failureSeq;
  /** 알림 키(분류, 범위가 한정된 차단이면 분류@범위) → 마지막으로 알린 날(KST) */
  private final Map<String, LocalDate> alertedOn = new ConcurrentHashMap<>();

  /**
   * @param clock                 현재 시각(테스트에서 고정·전진시킨다)
   * @param exhaustedCooldown     소진(EXHAUSTED) 뒤 차단 시간
   * @param unavailableCooldown   재시도 뒤에도 남은 일시 장애(UNAVAILABLE) 뒤 차단 시간
   * @param misconfiguredCooldown 키·권한·모델 ID 오류(MISCONFIGURED) 뒤 차단 시간
   * @param alertSink             차단 진입 알림 콜백
   * @throws IllegalArgumentException 차단 시간이 음수일 때. 0 은 허용한다 — 차단 없이 알림만 보낸다
   */
  public LlmProviderGate(Clock clock, Duration exhaustedCooldown, Duration unavailableCooldown, Duration misconfiguredCooldown,
      AlertSink alertSink) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.exhaustedCooldown = requireNonNegative(exhaustedCooldown, "exhaustedCooldown");
    this.unavailableCooldown = requireNonNegative(unavailableCooldown, "unavailableCooldown");
    this.misconfiguredCooldown = requireNonNegative(misconfiguredCooldown, "misconfiguredCooldown");
    this.alertSink = Objects.requireNonNull(alertSink, "alertSink");
  }

  /**
   * 1순위 호출을 시작하기 직전에 부른다. 돌려준 순번을 그 호출이 성공했을 때 {@link #recordSuccess} 에 넘긴다.
   */
  public long beginCall() {
    synchronized (lock) {
      return failureSeq;
    }
  }

  /**
   * 1순위 호출 실패를 기록한다. 폴백 대상 분류면 {@code 지금 + 분류별 시간} 까지 차단하고 알린다(분류별·범위별 KST 하루 1회).
   * {@link LlmFailureKind#REQUEST_INVALID} 는 공급자가 아니라 요청의 문제라 차단하지도 알리지도 않는다.
   * <p>
   * 모델 없음(404)은 그 모델만, 응답 없음은 호출부가 범위를 줬으면 그 범위만 차단하고 나머지는 계정 전체를 차단한다.
   * 같은 범위에 더 늦게 끝나는 차단이 이미 있으면 그것을 유지한다 — 소진(60분) 차단 중에 먼저 떠난 호출이 타임아웃(5분)으로 돌아와도
   * 차단이 짧아지지 않는다. 알림 콜백이 던진 예외는 여기서 끝낸다: 알림 실패가 폴백 호출을 막으면 안 된다.
   *
   * @param model           실패한 호출의 모델 ID. null·공백이면 404 도 계정 단위로 차단한다
   * @param unansweredScope 이 호출부의 응답 없음(status 0 — 타임아웃·연결 실패·응답 해석 실패)을 한정할 범위 이름. null·공백이면 응답 없음도 계정 단위로 차단한다
   */
  public void recordFailure(LlmProviderException failure, String model, String unansweredScope) {
    Duration cooldown = cooldownOf(failure.getKind());
    if (cooldown == null) {
      return;
    }
    Instant now = clock.instant();
    String scope = scopeOf(failure, model, unansweredScope);
    Block candidate = new Block(failure.getProvider(), failure.getKind(), failure.getStatus(), summarize(failure), now.plus(cooldown), scope);
    Block effective;
    synchronized (lock) {
      failureSeq++;
      Block current = scope == null ? accountBlock : scopedBlocks.get(scope);
      effective = current != null && current.until().isAfter(candidate.until()) ? current : candidate;
      if (scope == null) {
        accountBlock = effective;
      } else {
        scopedBlocks.put(scope, effective);
      }
    }
    alertOncePerDay(candidate, effective.until(), now);
  }

  /**
   * 1순위 호출 성공을 기록한다. 그 호출이 떠난 뒤 실패가 기록되지 않았으면 계정 단위 차단과 그 호출의 범위(모델·호출 경로) 차단 기록을 지우고,
   * 기록이 있었으면(차단 만료 뒤의 프로브가 성공했으면) 복귀를 로그로 남긴다. 다른 범위의 차단은 건드리지 않는다 — 이 모델의 성공은
   * 다른 모델 ID 가 있다는 증거가 아니다. 정상 경로에서 매 호출 불리므로 기록이 없을 때는 아무 일도 하지 않는다.
   *
   * @param callToken 그 호출을 시작할 때 {@link #beginCall()} 이 돌려준 순번
   * @param scopes    성공한 호출의 범위 키들(모델 ID·응답 없음 범위). null·공백은 건너뛴다. 없으면 계정 단위 차단만 지운다
   */
  public void recordSuccess(long callToken, String... scopes) {
    Block clearedAccount;
    List<Block> clearedScoped = new ArrayList<>();
    synchronized (lock) {
      if (failureSeq != callToken) {
        return;
      }
      clearedAccount = accountBlock;
      accountBlock = null;
      for (String key : scopeKeys(scopes)) {
        Block removed = scopedBlocks.remove(key);
        if (removed != null) {
          clearedScoped.add(removed);
        }
      }
    }
    if (clearedAccount != null) {
      log.info("LLM 공급자 복귀: {} 호출 성공 — {} 차단 해제", clearedAccount.provider(), clearedAccount.kind());
    }
    for (Block cleared : clearedScoped) {
      log.info("LLM 공급자 복귀: {} 호출 성공 — {} 차단 해제(범위 {})", cleared.provider(), cleared.kind(), cleared.scope());
    }
  }

  /**
   * 이 범위들로 부르는 호출에 지금 유효한 차단. 계정 단위 차단이 먼저이고, 없으면 준 순서대로 범위 차단을 본다. 기록이 없거나 만료 시각이 지났으면
   * 비어 있다. 차단 여부와 분류를 한 번에 읽어야 하는 쪽({@link FallbackChatModel})은 {@link #isBlocked} 뒤에 다시 묻지 말고 이 메서드를
   * 한 번만 부른다(두 번 읽는 사이에 만료될 수 있다).
   *
   * @param scopes 부르려는 호출의 범위 키들(모델 ID·응답 없음 범위). null·공백은 건너뛴다. 없으면 계정 단위 차단만 본다
   */
  public Optional<Block> currentBlock(String... scopes) {
    Instant now = clock.instant();
    synchronized (lock) {
      if (isActive(accountBlock, now)) {
        return Optional.of(accountBlock);
      }
      for (String key : scopeKeys(scopes)) {
        Block scoped = scopedBlocks.get(key);
        if (isActive(scoped, now)) {
          return Optional.of(scoped);
        }
      }
      return Optional.empty();
    }
  }

  /**
   * 이 범위들로 부르는 호출이 지금 차단 중인지.
   */
  public boolean isBlocked(String... scopes) {
    return currentBlock(scopes).isPresent();
  }

  /**
   * 분류별 차단 시간. 차단하지 않는 분류는 null. default 를 두지 않은 것은 분류가 늘면 컴파일이 깨져 여기서 정책을 정하게 하기 위해서다.
   */
  private Duration cooldownOf(LlmFailureKind kind) {
    return switch (kind) {
      case EXHAUSTED -> exhaustedCooldown;
      case UNAVAILABLE -> unavailableCooldown;
      case MISCONFIGURED -> misconfiguredCooldown;
      case REQUEST_INVALID -> null;
    };
  }

  /**
   * 이 실패가 차단할 범위의 키. 모델 없음(MISCONFIGURED + 404)은 모델 ID, 응답 없음(UNAVAILABLE + status 0)은 호출부가 준 범위 이름이고
   * 그 밖은 계정 단위(null)다 — 401·403 은 키·권한, 429·5xx 는 서버가 답한 장애라 어느 경로로 불러도 같다.
   * 키가 비어 있으면(모델·범위를 모름) 계정 단위로 떨어진다.
   */
  private static String scopeOf(LlmProviderException failure, String model, String unansweredScope) {
    if (failure.getKind() == LlmFailureKind.MISCONFIGURED && failure.getStatus() == MODEL_NOT_FOUND_STATUS) {
      return StringUtils.trimToNull(model);
    }
    if (failure.getKind() == LlmFailureKind.UNAVAILABLE && failure.getStatus() == 0) {
      return StringUtils.trimToNull(unansweredScope);
    }
    return null;
  }

  /**
   * 범위 키 목록에서 null·공백을 뺀다. 배열 자체가 null 이어도(가변 인자에 null 하나를 넘긴 경우) 빈 목록이다.
   */
  private static List<String> scopeKeys(String[] scopes) {
    List<String> keys = new ArrayList<>();
    if (scopes != null) {
      for (String scope : scopes) {
        String key = StringUtils.trimToNull(scope);
        if (key != null) {
          keys.add(key);
        }
      }
    }
    return keys;
  }

  /**
   * 기록이 있고 아직 만료되지 않았는가.
   */
  private static boolean isActive(Block block, Instant now) {
    return block != null && block.until().isAfter(now);
  }

  /**
   * 이 분류(범위가 한정된 차단이면 이 범위)를 오늘(KST) 아직 알리지 않았으면 알린다. 날짜를 먼저 찍고 보내므로 동시에 들어온 같은 실패는 한 통만 나간다.
   * 콜백이 전달하지 못했다고 답하거나 던지면 찍은 날짜를 되돌려 다음 실패 때 다시 시도한다 — 사람이 고쳐야 하는 문제(MISCONFIGURED)가
   * 알림 없이 하루를 넘기지 않게.
   * <p>
   * 운영 콜백({@code AdvisorNotifier.alert})은 Slack 이 메시지를 받았는지를 돌려준다 — Notify 빈이 없거나, 전송이 실패했거나, Slack 이 거부했으면
   * (ok=false: 채널 없음·토큰 무효) false 다. 그날 한 번도 전달되지 않았으면 다음 실패 때마다 다시 시도한다.
   *
   * @param retryAt 실제로 적용된 차단의 만료 시각(더 긴 기존 차단이 유지됐으면 그 시각)
   */
  private void alertOncePerDay(Block failure, Instant retryAt, Instant now) {
    String key = failure.scoped() ? failure.kind().name() + "@" + failure.scope() : failure.kind().name();
    LocalDate today = LocalDate.ofInstant(now, MarketClock.KST);
    if (today.equals(alertedOn.put(key, today))) {
      return;
    }
    boolean delivered;
    try {
      delivered = alertSink.alert(alertText(failure, retryAt), failure.kind() == LlmFailureKind.MISCONFIGURED);
    } catch (RuntimeException e) {
      alertedOn.remove(key, today);
      log.warn("LLM 공급자 차단 알림 실패(무시, 다음 실패 때 다시 시도): {} {} — {}", failure.provider(), key, e.getMessage());
      return;
    }
    if (!delivered) {
      alertedOn.remove(key, today);
      log.warn("LLM 공급자 차단 알림 미전달(다음 실패 때 다시 시도): {} {}", failure.provider(), key);
    }
  }

  /**
   * 알림 문구 — 공급자·분류·차단 범위·상태 코드·원인 요약·다음 시도 시각(KST).
   */
  private static String alertText(Block failure, Instant retryAt) {
    return String.format("[LLM 1순위 차단] %s · %s(%s)%n범위: %s%n상태: %s%n원인: %s%n다음 시도: %s KST 이후 (그동안 폴백 공급자가 준비돼 있으면 폴백으로 처리)",
        failure.provider(), failure.kind(), failure.kind().getDesc(),
        scopeText(failure),
        failure.status() == 0 ? "응답 없음(타임아웃·연결 실패·응답 해석 실패)" : "HTTP " + failure.status(),
        failure.reason(),
        RETRY_AT.format(retryAt.atZone(MarketClock.KST)));
  }

  /**
   * 알림에 싣는 차단 범위 문구. 범위가 한정된 차단은 무엇 때문에 그 범위인지를 함께 적는다.
   */
  private static String scopeText(Block failure) {
    if (!failure.scoped()) {
      return "계정 전체(모든 역할)";
    }
    if (failure.status() == MODEL_NOT_FOUND_STATUS) {
      return "모델 " + failure.scope() + " 만(다른 모델을 쓰는 역할은 그대로 1순위)";
    }
    return failure.scope() + " 호출만 — 이 경로에서 응답을 받지 못했다(다른 역할은 그대로 1순위)";
  }

  /**
   * 예외 메시지를 한 줄 요약으로 줄인다. {@link LlmProviderException} 의 메시지는 {@code "<공급자> <status>: <설명>"} 이라, 공급자·상태를 따로 싣는
   * 알림에서 겹치지 않게 그 접두사를 떼고 설명만 남긴다.
   */
  private static String summarize(LlmProviderException failure) {
    String message = StringUtils.defaultString(failure.getMessage());
    String prefix = failure.getProvider() + " " + failure.getStatus() + ": ";
    String description = StringUtils.normalizeSpace(message.startsWith(prefix) ? message.substring(prefix.length()) : message);
    return StringUtils.defaultIfBlank(StringUtils.abbreviate(description, REASON_MAX_LENGTH), "(메시지 없음)");
  }

  /**
   * 차단 시간 검증 — 배선 실수(null·음수)를 첫 실패 때가 아니라 조립 시점에 드러낸다.
   */
  private static Duration requireNonNegative(Duration cooldown, String name) {
    Objects.requireNonNull(cooldown, name);
    if (cooldown.isNegative()) {
      throw new IllegalArgumentException(name + " 은 음수일 수 없습니다: " + cooldown);
    }
    return cooldown;
  }
}
