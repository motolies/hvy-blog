package kr.hvy.blog.modules.stats.application.dto;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 월별 글 수.
 * <p>
 * ⚠️ tb_post 에 published_at 이 없어 현재는 <b>작성일(created_at) 기준</b>이다.
 * 글 작성 흐름이 "새 글" 버튼에서 빈 글을 만들고 나중에 채우는 구조라 발행일과 다를 수 있다.
 * 화면 라벨도 "작성일 기준"으로 표기할 것.
 */
@Value
@Builder
@Jacksonized
public class MonthlyPostCount {

  LocalDate month;
  long postCount;
}
