package kr.hvy.blog.modules.advisor.application.service;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;

/**
 * 12:00 픽 회고 출력(PickNoteResponse)의 strict JSON 스키마. 그날 회고 대상 픽의 티커와 후보 섹터 코드를 enum 으로 박아 환각을 API 계층에서 막고,
 * tags.signals 는 종목 점수에 쓰는 SignalCode 만 허용한다. hypothesis 는 일반화가 불가능하면 null 을 허용한다(가드가 티커·종목명·날짜 언급도 null 로 바꾼다).
 */
public final class NoteSchemaFactory {

  private NoteSchemaFactory() {
  }

  /**
   * @param tickers     회고 대상 픽 티커 (FLAT 제외, 비면 안 된다)
   * @param sectorCodes 대상 픽의 섹터 코드 (비면 sector 는 자유 문자열 또는 null)
   */
  public static Map<String, Object> schema(List<String> tickers, List<String> sectorCodes) {
    Map<String, Object> tags = AdviceSchemaFactory.object(Map.of(
        "signals", AdviceSchemaFactory.array(AdviceSchemaFactory.enumOf(SignalCode.scorable().stream().map(SignalCode::getCode).toList())),
        "sector", sectorCodes == null || sectorCodes.isEmpty() ? LessonSchemaFactory.nullable("string") : LessonSchemaFactory.nullableEnum(sectorCodes),
        "regime", LessonSchemaFactory.nullableEnum(List.of(MarketRegimeCode.RISK_ON.getCode(), MarketRegimeCode.NEUTRAL.getCode(),
            MarketRegimeCode.RISK_OFF.getCode()))));
    Map<String, Object> note = AdviceSchemaFactory.object(Map.of(
        "ticker", AdviceSchemaFactory.enumOf(tickers),
        "deviation", AdviceSchemaFactory.string(),
        "why", AdviceSchemaFactory.string(),
        "hypothesis", LessonSchemaFactory.nullable("string"),
        "tags", tags));
    return AdviceSchemaFactory.object(Map.of("notes", AdviceSchemaFactory.array(note)));
  }

  /**
   * 스키마 JSON 문자열.
   */
  public static String schemaJson(List<String> tickers, List<String> sectorCodes) {
    return AdvisorJson.write(schema(tickers, sectorCodes));
  }
}
