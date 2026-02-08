package kr.hvy.blog.modules.jira.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

/**
 * 워크로그 도메인 값 객체
 * 이슈 애그리게이트 내에서 워크로그 데이터를 전달하는 용도
 */
@Value
@Builder
public class WorklogInfo {

  String issueKey;
  String issueType;
  String status;
  String issueLink;
  String summary;
  String author;
  String components;
  String timeSpent;
  BigDecimal timeHours;
  String comment;
  LocalDateTime started;
  String worklogId;
}
