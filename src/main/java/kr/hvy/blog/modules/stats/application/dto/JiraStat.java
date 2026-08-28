package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Jira 미러 현황.
 * <p>
 * 워크로그 시간 합계는 넣지 않는다 — 대시보드의 질문은 "파이프라인이 돌아서 데이터가 들어왔나"이지
 * "내가 몇 시간 일했나"가 아니다. 후자는 스프린트 화면의 몫이다.
 * "마지막 동기화 시각"도 shedlock 위젯(JIRA-SYNC)이 이미 정확히 답한다.
 */
@Value
@Builder
@Jacksonized
public class JiraStat {

  long issueCount;
  long worklogCount;
  Instant lastWorklogAt;
}
