package kr.hvy.blog.modules.stock.application.service;

import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 해외 일일 증분(OVERSEAS_DAILY): 전일 미국장 마감분을 06:30 KST 에 받는다.
 */
@Component
@RequiredArgsConstructor
public class OverseasDailyJob implements CollectJob {

  private final OverseasMarketCollectService overseasService;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.OVERSEAS_DAILY;
  }

  @Override
  public void execute(CollectExecution execution) {
    overseasService.collectRecent(execution);
  }
}
