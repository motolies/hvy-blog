package kr.hvy.blog.modules.stock.application.service;

import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 부분 재적재(RELOAD): 지정 종목·기간의 일봉을 체크포인트를 되돌려 다시 받는다. 삭제 없이 upsert 로 덮어쓴다.
 * PRICE_BACKFILL 과 체크포인트 네임스페이스가 달라 백필이 돌고 있어도 방해하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PriceReloadJob implements CollectJob {

  private final StockDailyPriceCollectService priceCollectService;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.RELOAD;
  }

  @Override
  public void execute(CollectExecution execution) {
    if (!execution.request().hasTickers()) {
      throw new CollectRequestException("RELOAD 는 tickers 를 지정해야 합니다");
    }
    priceCollectService.backfill(execution, CollectJobType.RELOAD, true);
  }
}
