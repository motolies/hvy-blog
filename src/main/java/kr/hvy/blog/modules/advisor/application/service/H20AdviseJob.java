package kr.hvy.blog.modules.advisor.application.service;

import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 주간 20거래일 판단 잡(ADVISE_H20, M7 — 금요일 20:10 KST, 일일 수집 18:30·ADVISE 19:30~19:55 뒤). 파이프라인은 {@link AdviseJob} 과 같고
 * 종류만 H20 이다 — 차이(H20 가중치 세트·20거래일 창·뉴스/메모리 제외·H20 픽 범위·QUANT_TOPN 섀도만)는 전부 {@link AdviseJob#plan} 이 정한다.
 * 금요일이 휴장이면 게이트가 SKIPPED 로 닫는다(다음 영업일로 옮기지 않는다 — 주간 표본 간격을 고정해야 날짜 대응 비교가 성립한다).
 * <p>
 * 사전 등록 판정(26주 뒤): 같은 기준일 H20 LIVE − QUANT_TOPN 의 픽 평균 초과수익(h=20) 차이를 날짜 대응으로 모아 t ≥ 2 면 LLM 선택 유지(AdvisorProperties.H20 참고).
 */
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class H20AdviseJob implements AdvisorJob {

  private final AdviseJob adviseJob;

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.ADVISE_H20;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    adviseJob.advise(execution, AdviceKind.H20);
  }
}
