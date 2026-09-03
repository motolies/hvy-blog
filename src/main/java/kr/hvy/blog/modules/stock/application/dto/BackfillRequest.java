package kr.hvy.blog.modules.stock.application.dto;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import kr.hvy.blog.modules.stock.application.service.CollectRequestException;

/**
 * 수집 잡 트리거 요청. 모든 필드가 선택이며 잡 유형마다 해석이 다르다.
 *
 * @param startDate       백필 목표 시작일 (기본 kis.backfill.start-date)
 * @param endDate         백필 커서 시작일 = 가장 최근 일자 (기본 오늘)
 * @param tickerFrom      종목코드 범위 시작 (포함) — 900종목씩 나눠 돌릴 때
 * @param tickerTo        종목코드 범위 끝 (포함)
 * @param tickers         명시 종목 목록 (있으면 범위 무시)
 * @param indexCodes      지수 백필 대상 코드 (없으면 지수 마스터 전체)
 * @param resetCheckpoint true 면 체크포인트를 PENDING 으로 되돌려 처음부터 다시 받는다
 * @param force           일부 잡의 안전장치를 무시한다 (예: DAILY 의 휴장일 스킵)
 */
public record BackfillRequest(
    LocalDate startDate,
    LocalDate endDate,
    String tickerFrom,
    String tickerTo,
    List<String> tickers,
    List<String> indexCodes,
    Boolean resetCheckpoint,
    Boolean force
) {

  private static final Pattern TICKER = Pattern.compile("^[A-Z0-9]{6}$");
  private static final Pattern INDEX_CODE = Pattern.compile("^[0-9]{4}$");
  private static final int MAX_TICKERS = 5_000;

  /**
   * 빈 요청 (전부 기본값).
   */
  public static BackfillRequest empty() {
    return new BackfillRequest(null, null, null, null, null, null, null, null);
  }

  /**
   * 종목 목록만 담은 요청.
   */
  public static BackfillRequest forTickers(List<String> tickers) {
    return new BackfillRequest(null, null, null, null, tickers, null, null, null);
  }

  public boolean reset() {
    return Boolean.TRUE.equals(resetCheckpoint);
  }

  public boolean isForce() {
    return Boolean.TRUE.equals(force);
  }

  public boolean hasTickers() {
    return tickers != null && !tickers.isEmpty();
  }

  public boolean hasIndexCodes() {
    return indexCodes != null && !indexCodes.isEmpty();
  }

  /**
   * 형식 검증. 위반 시 {@link CollectRequestException}(400).
   */
  public void validate() {
    if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
      throw new CollectRequestException("startDate 가 endDate 보다 늦습니다");
    }
    if (tickers != null) {
      if (tickers.size() > MAX_TICKERS) {
        throw new CollectRequestException("tickers 는 최대 " + MAX_TICKERS + "개입니다");
      }
      for (String ticker : tickers) {
        if (ticker == null || !TICKER.matcher(ticker).matches()) {
          throw new CollectRequestException("종목코드 형식 오류: " + ticker);
        }
      }
    }
    if (indexCodes != null) {
      for (String code : indexCodes) {
        if (code == null || !INDEX_CODE.matcher(code).matches()) {
          throw new CollectRequestException("지수코드 형식 오류: " + code);
        }
      }
    }
    if ((tickerFrom != null && !TICKER.matcher(tickerFrom).matches())
        || (tickerTo != null && !TICKER.matcher(tickerTo).matches())) {
      throw new CollectRequestException("tickerFrom/tickerTo 형식 오류");
    }
    if (tickerFrom != null && tickerTo != null && tickerFrom.compareTo(tickerTo) > 0) {
      throw new CollectRequestException("tickerFrom 이 tickerTo 보다 큽니다");
    }
  }

  /**
   * run.metadata_json 에 남길 요약 (null 필드는 제외).
   */
  public Map<String, Object> toMetadata() {
    Map<String, Object> map = new LinkedHashMap<>();
    if (startDate != null) {
      map.put("startDate", startDate.toString());
    }
    if (endDate != null) {
      map.put("endDate", endDate.toString());
    }
    if (tickerFrom != null) {
      map.put("tickerFrom", tickerFrom);
    }
    if (tickerTo != null) {
      map.put("tickerTo", tickerTo);
    }
    if (hasTickers()) {
      map.put("tickerCount", tickers.size());
      map.put("tickers", tickers.size() <= 20 ? tickers : tickers.subList(0, 20));
    }
    if (hasIndexCodes()) {
      map.put("indexCodes", indexCodes);
    }
    if (reset()) {
      map.put("resetCheckpoint", true);
    }
    if (isForce()) {
      map.put("force", true);
    }
    return map;
  }
}
