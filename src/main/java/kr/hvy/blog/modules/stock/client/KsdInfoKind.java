package kr.hvy.blog.modules.stock.client;

import java.util.Map;
import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 예탁원정보(ksdinfo) API 7종. 공통 파라미터 CTS/F_DT/T_DT/SHT_CD 외에 유형별 고정 파라미터가 있다.
 * code 는 체크포인트 대상 키("REV_SPLIT:2026-01-01")와 run 메타데이터 키에 쓰인다.
 */
@Getter
@AllArgsConstructor
public enum KsdInfoKind implements EnumCode<String> {
  REV_SPLIT("REV_SPLIT", "액면분할·병합", "/uapi/domestic-stock/v1/ksdinfo/rev-split", "HHKDB669105C0",
      Map.of("MARKET_GB", "0")),
  BONUS_ISSUE("BONUS_ISSUE", "무상증자", "/uapi/domestic-stock/v1/ksdinfo/bonus-issue", "HHKDB669101C0", Map.of()),
  PAIDIN_CAPIN("PAIDIN_CAPIN", "유상증자", "/uapi/domestic-stock/v1/ksdinfo/paidin-capin", "HHKDB669100C0",
      Map.of("GB1", "2")),
  CAP_DCRS("CAP_DCRS", "감자", "/uapi/domestic-stock/v1/ksdinfo/cap-dcrs", "HHKDB669106C0", Map.of()),
  MERGER_SPLIT("MERGER_SPLIT", "합병·분할", "/uapi/domestic-stock/v1/ksdinfo/merger-split", "HHKDB669104C0", Map.of()),
  LIST_INFO("LIST_INFO", "상장 정보", "/uapi/domestic-stock/v1/ksdinfo/list-info", "HHKDB669107C0", Map.of()),
  DIVIDEND("DIVIDEND", "배당", "/uapi/domestic-stock/v1/ksdinfo/dividend", "HHKDB669102C0",
      Map.of("GB1", "0", "HIGH_GB", ""));

  private final String code;
  private final String desc;
  private final String path;
  private final String trId;
  /** 유형별 고정 파라미터 (GB1 기준일별, MARKET_GB 전체 등) */
  private final Map<String, String> fixedParams;
}
