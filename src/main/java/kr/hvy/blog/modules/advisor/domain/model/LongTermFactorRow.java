package kr.hvy.blog.modules.advisor.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import lombok.Builder;

/**
 * 기준일 유니버스 종목 1개의 장기 팩터 원값(M8, LongTermFactorSql). 전부 기준일 이하 데이터이며 재무는 bitemporal PIT(available_from·first_seen_at ≤ 기준일)다.
 *
 * @param kospi200        기준일 KOSPI200 구성 여부(PIT 이력, 이력 전이면 null = 모름)
 * @param k200Sector      구성종목일 때만 KOSPI200 섹터 대분류(테마) 코드
 * @param factors         장기 팩터 → 원값(null 이면 결측). 키는 SignalCode.longTerm()
 * @param fiscalPeriod    재무 팩터가 쓴 결산기(YYYYMM, 없으면 null)
 * @param financialAsOf   그 재무 행의 available_from (없으면 null)
 */
@Builder
public record LongTermFactorRow(
    String ticker,
    String stockName,
    String marketType,
    String sectorCode,
    String sectorName,
    Boolean kospi200,
    String k200Sector,
    BigDecimal rawClose,
    Double adjClose,
    Map<SignalCode, Double> factors,
    String fiscalPeriod,
    LocalDate financialAsOf) {
}
