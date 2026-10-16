package kr.hvy.blog.modules.advisor.domain.model;

import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.ThemeStrength;
import kr.hvy.blog.modules.advisor.domain.code.VolRegimeCode;
import lombok.Builder;

/**
 * 합성 국면 스냅샷(M6, tb_advisor_advice.regime_json 의 원천). 규칙 추세(TrendSql) × 변동성 국면(σ20 백분위) → 사전 등록 정책 표(RegimePolicy)의 한도,
 * 그리고 같은 기준일의 KOSPI200 섹터 대분류(테마) 강약. 전부 결정론이며 기준일 이하 데이터만 쓴다. LLM 이 판단하는 5일 위험 선호(regime_code)와는 다른 축이다.
 *
 * @param indexCode      국면 기준 지수 (advisor.regime.index-code, 기본 0001 KOSPI)
 * @param tradeDate      추세 라벨을 계산한 지수 행의 날짜
 * @param trend          확정 추세 라벨 (없으면 null — 정책도 null)
 * @param trendScore     추세 성분 합 [-5, +5]
 * @param vol            변동성 국면 (이력 부족이면 UNKNOWN)
 * @param volPct         σ20 의 기준일 이전 분포 백분위 [0, 1] (이력 부족이면 null)
 * @param sigma20        기준일 σ20 (일간 수익률 표준편차)
 * @param volHistoryDays 백분위 분포에 쓴 과거 σ20 표본 수 (기준일 제외)
 * @param policy         정책 표가 정한 오늘의 한도 (추세가 없으면 null = 기존 가드만)
 * @param themes         테마 강약 (구성 종목 수 게이트를 넘긴 대분류, rs20 내림차순). 없으면 빈 목록
 */
@Builder(toBuilder = true)
public record MarketRegime(
    String indexCode,
    LocalDate tradeDate,
    MarketTrendCode trend,
    Integer trendScore,
    VolRegimeCode vol,
    Double volPct,
    Double sigma20,
    Integer volHistoryDays,
    Policy policy,
    List<Theme> themes) {

  /**
   * 합성 라벨 "BEAR·HIGH". 추세가 없으면 "-".
   */
  public String labelText() {
    return (trend == null ? "-" : trend.getCode()) + "·" + (vol == null ? VolRegimeCode.UNKNOWN.getCode() : vol.getCode());
  }

  /**
   * 사전 등록 정책 표의 한도(advisor.regime.policy). 수치를 바꾸면 version 을 올린다 — 판단마다 regime_json 에 버전과 함께 남아 사후 분리가 된다.
   *
   * <p>
   * regime-policy-v2(2026-09-27, 매수 전용)에서 avoidMax 를 뺐다. v1 시절 regime_json 에 남은 avoidMax 키는 역직렬화 때 무시된다
   * (Jackson 3 기본 FAIL_ON_UNKNOWN_PROPERTIES=false — MarketRegimeTest 가 고정).
   *
   * @param version       정책 표 버전 (예: regime-policy-v2)
   * @param longMax       LONG 픽 상한 (1 이상)
   * @param convictionCap LONG 확신 상한 (허용 이산값으로 내림, null 이면 상한 없음)
   */
  public record Policy(String version, int longMax, Double convictionCap) {
  }

  /**
   * 테마(KOSPI200 섹터 대분류) 1개의 강약. rs = 구성 종목 수익률 중앙값 − KOSPI 같은 창 수익률(소수), breadth = 20일선 위 종목 비율.
   * <p>
   * 2026-10-16(P2): name(설정 advisor.theme.names 매핑, 없으면 null)과 rsEq(구성 종목 중앙값 − KOSPI200 전 구성 종목 중앙값, 동일가중 벤치)를 덧붙였다.
   * strength 는 그대로 rs20/rs60(KOSPI 대비) 기준이라 판단 프롬프트·KPI 는 불변이고, rsEq 는 "시장 전체가 눌릴 때 9/11 이 STRONG" 을 상대 강도로 읽는 보조 지표다.
   * 과거 regime_json 에는 두 필드가 없어 null 로 읽힌다.
   *
   * @param code     kospi200_sector 코드 (KIS 마스터 1자리)
   * @param members  기준일에 KOSPI200 구성(PIT)이고 지표가 있는 종목 수
   * @param leaders  60일 평균 거래대금 상위 종목명(코드 의미를 LLM 이 추정할 수 있게, 최대 advisor.theme.leaders 개)
   * @param strength 강약 라벨
   * @param name     테마 이름(설정 매핑, 미확인 코드는 null)
   * @param rsEq5    구성 종목 중앙값 − 전 구성 종목 중앙값(5일), rsEq20·rsEq60 도 같은 정의
   */
  public record Theme(String code, int members, Double rs5, Double rs20, Double rs60, Double breadth, ThemeStrength strength, List<String> leaders,
                      String name, Double rsEq5, Double rsEq20, Double rsEq60) {

    /** name·rsEq 없는 생성(과거 호출부·테스트 호환) */
    public Theme(String code, int members, Double rs5, Double rs20, Double rs60, Double breadth, ThemeStrength strength, List<String> leaders) {
      this(code, members, rs5, rs20, rs60, breadth, strength, leaders, null, null, null, null);
    }
  }
}
