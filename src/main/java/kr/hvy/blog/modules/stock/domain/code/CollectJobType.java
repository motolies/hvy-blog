package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 주식 수집 잡 유형. run 테이블의 job_type 이며 RUNNING 부분 유니크 인덱스의 키다.
 * <p>
 * code 는 상수명과 같다 — DB 부분 인덱스(WHERE status='RUNNING')·체크포인트 키·REST 경로 변수가 모두 상수명 기준이기 때문이다.
 */
@Getter
@AllArgsConstructor
public enum CollectJobType implements EnumCode<String> {
  BACKFILL_ALL("BACKFILL_ALL", "전체 백필 (12단계 순차)", true),
  MASTER("MASTER", "종목 마스터 갱신", false),
  HOLIDAY("HOLIDAY", "휴장일 수집", false),
  INDEX_BACKFILL("INDEX_BACKFILL", "지수 일봉 백필", true),
  PRICE_BACKFILL("PRICE_BACKFILL", "종목 일봉 백필", true),
  STOCK_INFO("STOCK_INFO", "종목 기본정보 수집", true),
  VALUATION("VALUATION", "밸류에이션 스냅샷", true),
  MARKET_STAT("MARKET_STAT", "시장 통계(공매도·신용·프로그램)", true),
  CORP_ACTION("CORP_ACTION", "기업행사 수집", true),
  ADJUST_FACTOR("ADJUST_FACTOR", "수정주가 계수 산출", false),
  INVESTOR_BACKFILL("INVESTOR_BACKFILL", "투자자 수급 백필", true),
  FINANCIAL_BACKFILL("FINANCIAL_BACKFILL", "재무제표 백필", true),
  OVERSEAS_BACKFILL("OVERSEAS_BACKFILL", "해외 지표 백필", true),
  DERIVED_REFRESH("DERIVED_REFRESH", "파생 지표 갱신", false),
  VALIDATE("VALIDATE", "정합성 검증", false),
  DAILY("DAILY", "일일 증분 수집", false),
  WEEKLY("WEEKLY", "주간 수집", false),
  OVERSEAS_DAILY("OVERSEAS_DAILY", "해외 일일 증분", false),
  RELOAD("RELOAD", "부분 재적재", true);

  private final String code;
  private final String desc;

  /** 장시간 실행되어 스케줄러가 아닌 전용 실행기(kisBackfillExecutor)에서 돌려야 하는 잡인지 */
  private final boolean longRunning;
}
