package kr.hvy.blog.modules.advisor.application.service;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;

/**
 * 아침 재판정 출력(MorningAdviceResponse)의 strict JSON 스키마. decisions.ticker 는 저녁 픽, additions.ticker 는 저녁 후보에서 픽을 뺀 목록을 enum 으로 박아
 * "후보 밖 추가·픽 밖 제외" 를 API 계층에서 1차로 막는다(2차는 MorningAdviceGuard). 조치는 KEEP·DROP 만 — ADD 는 additions 배열이 표현한다.
 * <p>
 * morning-v2(2026-09-27, 매수 전용): additions 에 direction 이 없다 — 추가는 전부 매수다. 저녁이 관망(0픽)이면 decisions 는 빈 배열이어야 하는데 strict enum 은 빈 목록을
 * 거부하므로 저녁 후보 전체로 대신하고, 가드가 저녁 픽 밖 결정을 unknownDecision 으로 버린다.
 */
public final class MorningAdviceSchemaFactory {

  private MorningAdviceSchemaFactory() {
  }

  /**
   * @param eveningPickTickers 저녁 LIVE 픽 티커 (관망이면 빈 목록 — 결정 ticker enum 은 저녁 후보 전체로 대신한다)
   * @param addableTickers     추가 가능한 티커 = 저녁 후보 − 저녁 픽. 비면(후보를 전부 골랐으면) strict enum 이 빈 목록을 거부하므로 저녁 후보 전체로 대신하고 가드가 거른다
   * @param candidateTickers   저녁 후보 전체 (빈 목록의 대체값)
   */
  public static Map<String, Object> schema(List<String> eveningPickTickers, List<String> addableTickers, List<String> candidateTickers) {
    Map<String, Object> decision = AdviceSchemaFactory.object(Map.of(
        "ticker", AdviceSchemaFactory.enumOf(eveningPickTickers.isEmpty() ? candidateTickers : eveningPickTickers),
        "action", AdviceSchemaFactory.enumOf(List.of(PickAction.KEEP.getCode(), PickAction.DROP.getCode())),
        "reason", AdviceSchemaFactory.string()));
    Map<String, Object> addition = AdviceSchemaFactory.object(Map.of(
        "ticker", AdviceSchemaFactory.enumOf(addableTickers.isEmpty() ? candidateTickers : addableTickers),
        "conviction", AdviceSchemaFactory.enumOf(AdviceSchemaFactory.CONVICTIONS),
        "thesis", AdviceSchemaFactory.string(),
        "risk", AdviceSchemaFactory.string(),
        "reason", AdviceSchemaFactory.string()));
    return AdviceSchemaFactory.object(Map.of(
        "decisions", AdviceSchemaFactory.array(decision),
        "additions", AdviceSchemaFactory.array(addition),
        "summary", AdviceSchemaFactory.string()));
  }

  /**
   * 스키마 JSON 문자열.
   */
  public static String schemaJson(List<String> eveningPickTickers, List<String> addableTickers, List<String> candidateTickers) {
    return AdvisorJson.write(schema(eveningPickTickers, addableTickers, candidateTickers));
  }
}
