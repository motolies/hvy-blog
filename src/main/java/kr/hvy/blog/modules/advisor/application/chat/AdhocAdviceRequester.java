package kr.hvy.blog.modules.advisor.application.chat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.service.AdvisorAlreadyRunningException;
import kr.hvy.blog.modules.advisor.application.service.AdvisorGateService;
import kr.hvy.blog.modules.advisor.application.service.AdvisorOrchestrator;
import kr.hvy.blog.modules.advisor.application.service.AdvisorRequestException;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 채팅 수시 판단 요청(requestAdvice, chat-v2)의 판정과 트리거. 순서: 허용 사용자 → 기준일 결정 → 같은 기준일 ADHOC 존재 → 일 상한 → ADVISE_ADHOC 비동기 트리거.
 * <p>
 * 기준일은 "지금 판단할 수 있는 마지막 거래일" 이다. 지표 테이블의 마지막 거래일이 오늘이지만 DAILY 수집이 아직 끝나지 않았으면(장중·수집 중) 그 전 거래일로 내린다 —
 * 잡의 게이트가 같은 판정으로 SKIPPED 로 닫아 사용자가 run id 만 받고 결과를 못 보는 경우를 앞에서 막는다.
 * 존재 확인을 상한보다 먼저 하는 이유: 이미 있는 판단을 알려 주는 응답은 비용이 없으므로 한도를 다 쓴 뒤에도 돌려줄 수 있어야 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AdhocAdviceRequester {

  /** 요청 결과 종류 — 도구가 모델에게 그대로 전달한다. advisor 규약대로 EnumCode(code == 상수명) */
  @Getter
  @AllArgsConstructor
  public enum Status implements EnumCode<String> {
    STARTED("STARTED", "비동기 실행 시작"),
    EXISTS("EXISTS", "같은 기준일 수시 판단 있음"),
    RUNNING("RUNNING", "수시 판단 진행 중"),
    FORBIDDEN("FORBIDDEN", "허용 목록 밖 사용자"),
    LIMIT("LIMIT", "일 상한 초과"),
    NOT_READY("NOT_READY", "판단할 거래일 데이터 없음"),
    REJECTED("REJECTED", "트리거 거부(설정 누락 등)");

    private final String code;
    private final String desc;
  }

  /** 요청 결과. runId·adviceId·baseDate 는 상태에 따라 null */
  public record Outcome(Status status, Long runId, Long adviceId, LocalDate baseDate, String message) {
  }

  private final AdvisorChatProperties properties;
  private final ChatBudgetGuard budget;
  private final AdvisorOrchestrator orchestrator;
  private final AdvisorGateService gate;
  private final AdviceWriter adviceWriter;
  private final StockLookupReader reader;

  /**
   * 수시 판단을 요청한다. 예외를 던지지 않고 결과 종류로 돌려준다(도구 루프를 죽이지 않게).
   */
  public Outcome request(String userId) {
    if (!properties.isAllowedUser(userId)) {
      log.warn("advisor chat 수시 판단 거부 — 허용 목록 밖 사용자: {}", userId);
      return new Outcome(Status.FORBIDDEN, null, null, null, "수시 판단 요청은 허용된 사용자만 할 수 있습니다");
    }
    Optional<LocalDate> baseDate = resolveBaseDate();
    if (baseDate.isEmpty()) {
      return new Outcome(Status.NOT_READY, null, null, null, "판단할 수 있는 거래일 데이터가 없습니다(지표 미수집)");
    }
    LocalDate d = baseDate.get();
    Optional<AdviceHeader> existing = adviceWriter.find(d, AdviceKind.ADHOC, AdviceVariant.LIVE);
    if (existing.isPresent()) {
      return new Outcome(Status.EXISTS, existing.get().runId(), existing.get().adviceId(), d,
          "같은 기준일의 수시 판단이 이미 있습니다 — 입력(일봉 지표)이 같아 다시 만들지 않습니다");
    }
    Optional<ChatBudgetGuard.Refusal> refusal = budget.checkAdhocQuota();
    if (refusal.isPresent()) {
      return new Outcome(Status.LIMIT, null, null, d, refusal.get().message());
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("requestedBy", userId);
    try {
      AdvisorOrchestrator.TriggerResult result = orchestrator.trigger(AdvisorJobType.ADVISE_ADHOC, d, AdvisorTriggerType.CHAT, metadata);
      log.info("advisor chat 수시 판단 시작: run={}, base={}, user={}", result.run().getRunId(), d, userId);
      return new Outcome(Status.STARTED, result.run().getRunId(), null, d, "수시 판단을 시작했습니다");
    } catch (AdvisorAlreadyRunningException e) {
      return new Outcome(Status.RUNNING, e.getRunningRunId(), null, d, "이미 수시 판단이 진행 중입니다");
    } catch (AdvisorRequestException e) {
      log.warn("advisor chat 수시 판단 트리거 거부: {}", e.getMessage());
      return new Outcome(Status.REJECTED, null, null, d, e.getMessage());
    }
  }

  /**
   * 지금 판단할 수 있는 마지막 거래일. 오늘이 지표상 마지막 날이지만 DAILY 수집이 덜 끝났으면 직전 거래일로 내린다.
   */
  Optional<LocalDate> resolveBaseDate() {
    LocalDate today = MarketClock.today();
    Optional<LocalDate> latest = reader.latestMetricDate(today);
    if (latest.isEmpty()) {
      return Optional.empty();
    }
    AdvisorGateService.Decision decision = gate.decide(latest.get());
    if (decision.tradingDay() && decision.dataReady()) {
      return latest;
    }
    Optional<LocalDate> previous = reader.latestMetricDate(latest.get().minusDays(1));
    return previous.filter(p -> {
      AdvisorGateService.Decision prior = gate.decide(p);
      return prior.tradingDay() && prior.dataReady();
    });
  }
}
