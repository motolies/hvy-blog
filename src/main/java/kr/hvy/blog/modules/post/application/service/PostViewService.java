package kr.hvy.blog.modules.post.application.service;

import java.util.regex.Pattern;
import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.blog.modules.post.repository.PostRepository;
import kr.hvy.common.core.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회수 계측 — 공개 beacon 이 호출한다.
 * <p>
 * <b>이 서비스는 어떤 경우에도 예외를 밖으로 던지지 않는다.</b>
 * {@code ResponseWrapperConfigure} 가 DataNotFoundException·IllegalArgumentException 까지
 * Slack 으로 발송하므로, 봇이 존재하지 않는 postId 를 긁으면 알림 채널이 도배된다.
 * 미존재·비공개·중복은 전부 조용한 no-op 이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostViewService {

  /**
   * 봇 User-Agent 차단 — 보조 방어일 뿐이다(실제 크롤러는 JS 를 실행하지 않아 beacon 자체가 오지 않는다).
   * <p>
   * ⚠️ 'naver'·'daum' 을 통째로 매칭하면 안 된다 — NAVER 인앱 브라우저 UA 에 포함되어 실사용자를 차단한다.
   * 검색 봇은 'Yeti'(네이버)·'Daumoa'(다음)라는 고유 토큰을 쓴다.
   */
  private static final Pattern BOT_USER_AGENT = Pattern.compile(
      "(?i)bot|crawler|crawling|spider|slurp|Yeti|Daumoa|facebookexternalhit"
          + "|headless|python-requests|curl/|wget|monitor|uptime|lighthouse"
          + "|semrush|ahrefs|mj12|dotbot|petalbot");

  private final PostRepository postRepository;
  private final PostViewDeduplicator deduplicator;

  /**
   * 조회수를 1 증가시킨다. 집계 대상이 아니면 아무 일도 하지 않는다.
   *
   * @return 실제로 증가시켰으면 true (테스트·로깅용. 응답 본문에는 싣지 않는다)
   */
  @Transactional
  public boolean recordView(Long postId, String remoteAddr, String userAgent) {
    try {
      // 관리자 본인의 조회는 통계에 넣지 않는다 (프론트도 로그인 상태면 beacon 을 쏘지 않는 것이 권장)
      if (SecurityUtils.hasAdminRole()) {
        return false;
      }
      if (userAgent != null && BOT_USER_AGENT.matcher(userAgent).find()) {
        return false;
      }
      if (!deduplicator.isFirstView(postId, remoteAddr)) {
        return false;
      }
      // 대상이 없으면(미존재·임시저장·비공개) 0 — 예외 없이 그대로 종료
      return postRepository.incrementViewCount(postId, PostStatus.PUBLISH) > 0;
    } catch (Exception e) {
      // 계측 실패가 사용자 응답에 영향을 주면 안 된다
      log.warn("조회수 계측 실패: postId={}, cause={}", postId, e.getMessage());
      return false;
    }
  }
}
