package kr.hvy.blog.modules.stock.domain.code;

import java.util.Set;

/**
 * KIS 응답 msg_cd 분류. EGW00201 은 공식 README 에 명시된 초당 거래건수 초과 코드이며,
 * 토큰 관련 코드는 커뮤니티 보고 기반(미검증)이라 실측 후 보정한다.
 */
public final class KisErrorCode {

  /** 초당 거래건수 초과 (공식) */
  public static final String RATE_LIMIT_EXCEEDED = "EGW00201";

  /** 접근토큰 발급 잠시 후 다시 시도 (1분 1회 제한, 커뮤니티 보고) */
  public static final String TOKEN_ISSUE_TOO_FREQUENT = "EGW00133";

  /** 토큰 만료·유효하지 않은 토큰 계열 (커뮤니티 보고, 미검증) */
  private static final Set<String> TOKEN_INVALID_CODES = Set.of("EGW00121", "EGW00123");

  private KisErrorCode() {
  }

  /**
   * 초당 호출 한도 초과 응답인지 판정한다.
   */
  public static boolean isRateLimited(String msgCd) {
    return RATE_LIMIT_EXCEEDED.equals(msgCd);
  }

  /**
   * 토큰 재발급으로 해결되는 오류인지 판정한다.
   */
  public static boolean isTokenInvalid(String msgCd) {
    return msgCd != null && TOKEN_INVALID_CODES.contains(msgCd);
  }
}
