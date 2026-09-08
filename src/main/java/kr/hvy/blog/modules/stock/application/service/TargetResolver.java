package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.entity.StockMaster;
import kr.hvy.blog.modules.stock.repository.StockMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수집 대상 종목 결정: 요청의 tickers → 범위(tickerFrom/To) → 활성 유니버스(kis.backfill.security-groups) 순.
 */
@Component
@RequiredArgsConstructor
public class TargetResolver {

  private final StockMasterRepository masterRepository;
  private final KisProperties properties;

  /**
   * 요청에 맞는 대상 종목(오름차순, 중복 제거). 유니버스는 yml 의 kis.backfill.security-groups(기본 ST).
   */
  public List<String> resolveTickers(BackfillRequest request) {
    return resolveTickers(request, properties.getBackfill().getSecurityGroups());
  }

  /**
   * 증권그룹을 지정한 대상 해석 (예: ETF NAV 는 EF). tickers 가 있으면 그룹과 무관하게 그대로 쓴다.
   */
  public List<String> resolveTickers(BackfillRequest request, Collection<String> securityGroups) {
    if (request.hasTickers()) {
      return request.tickers().stream().distinct().sorted().toList();
    }
    return activeTickers(securityGroups).stream()
        .filter(t -> request.tickerFrom() == null || t.compareTo(request.tickerFrom()) >= 0)
        .filter(t -> request.tickerTo() == null || t.compareTo(request.tickerTo()) <= 0)
        .toList();
  }

  /**
   * 활성 유니버스(증분 대상).
   */
  public List<String> activeTickers() {
    return activeTickers(properties.getBackfill().getSecurityGroups());
  }

  /**
   * 지정 증권그룹의 활성 종목.
   */
  public List<String> activeTickers(Collection<String> securityGroups) {
    return masterRepository.findActiveTickers(securityGroups);
  }

  /**
   * 종목별 상장일 (백필 하한).
   */
  public Map<String, LocalDate> listingDates(Collection<String> tickers) {
    Map<String, LocalDate> result = new HashMap<>();
    for (StockMaster master : masterRepository.findAllById(tickers)) {
      if (master.getListingDate() != null) {
        result.put(master.getTicker(), master.getListingDate());
      }
    }
    return result;
  }
}
