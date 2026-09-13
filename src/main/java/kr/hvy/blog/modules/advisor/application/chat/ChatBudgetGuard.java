package kr.hvy.blog.modules.advisor.application.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.repository.jdbc.ChatWriter;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 호출 직전의 비용 방어선 두 겹 — 일일 토큰 예산(tb_advisor_chat 합계, KST 자정 기준)과 사용자별 쿨다운(Redis SET NX EX).
 * <p>
 * 검사는 답변 직전이 아니라 <b>LLM 호출 직전</b>이다(감사 행은 이미 RUNNING 으로 있고 거부되면 SKIPPED 로 닫힌다). 쿨다운 키는 통과할 때만 심는다 —
 * 예산 거부로 답을 못 받은 사용자가 곧바로 다시 물었을 때 쿨다운까지 겹치지 않게. Redis·DB 장애 시에는 통과시킨다(방어선이 봇을 멈추면 안 된다).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ChatBudgetGuard {

  static final String COOLDOWN_PREFIX = "advisor:chat:cd:";

  /** 거부 사유 — 스레드에 그대로 한 줄로 나간다 */
  public record Refusal(String code, String message) {
  }

  private final AdvisorChatProperties properties;
  private final ChatWriter chatWriter;
  private final RedissonClient redissonClient;

  /**
   * 통과면 빈 Optional. 거부면 사유(예산 소진 / 쿨다운 n초).
   */
  public Optional<Refusal> check(IncomingQuestion question) {
    Optional<Refusal> budget = checkDailyBudget();
    if (budget.isPresent()) {
      return budget;
    }
    return checkCooldown(question.userId());
  }

  /**
   * 오늘(KST) 생성된 대화의 입력+출력 토큰 합이 예산 이상이면 거부. 예산 0 은 무제한.
   */
  Optional<Refusal> checkDailyBudget() {
    long budget = properties.getDailyTokenBudget();
    if (budget <= 0) {
      return Optional.empty();
    }
    try {
      Instant dayStart = LocalDate.now(MarketClock.KST).atStartOfDay(MarketClock.KST).toInstant();
      long used = chatWriter.tokensSince(dayStart);
      if (used >= budget) {
        log.warn("advisor chat 일일 토큰 예산 소진: used={}, budget={}", used, budget);
        return Optional.of(new Refusal("BUDGET", String.format("오늘 토큰 예산을 다 썼습니다(%,d / %,d). 내일(KST 자정) 다시 물어봐 주세요.", used, budget)));
      }
      return Optional.empty();
    } catch (Exception e) {
      log.warn("advisor chat 예산 조회 실패 — 통과시킨다: {}", e.toString());
      return Optional.empty();
    }
  }

  /**
   * 같은 사용자의 직전 통과 이후 cooldown 초가 안 지났으면 거부. 통과 시 키를 심는다.
   */
  Optional<Refusal> checkCooldown(String userId) {
    int seconds = properties.getPerUserCooldownSeconds();
    if (seconds <= 0 || userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    try {
      var bucket = redissonClient.getBucket(COOLDOWN_PREFIX + userId);
      if (bucket.setIfAbsent("1", Duration.ofSeconds(seconds))) {
        return Optional.empty();
      }
      long remaining = Math.max(1, bucket.remainTimeToLive() / 1_000);
      return Optional.of(new Refusal("COOLDOWN", String.format("질문 간격이 너무 짧습니다. %d초 뒤에 다시 물어봐 주세요.", remaining)));
    } catch (Exception e) {
      log.warn("advisor chat 쿨다운 판정 실패 — 통과시킨다: {}", e.toString());
      return Optional.empty();
    }
  }
}
