package kr.hvy.blog.modules.post.application;

import jakarta.servlet.http.HttpServletRequest;
import kr.hvy.blog.modules.post.application.service.PostViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 조회수 beacon — 포스트 상세를 실제로 읽은 시점에 프론트가 1회 호출한다.
 * <p>
 * <b>이 엔드포인트의 tb_system_log 행은 대시보드가 의존하는 데이터다. 로깅을 끄지 말 것.</b>
 * SystemLogAspect 가 남기는 행의 {@code remote_addr}·{@code created_at} 이
 * 고유 방문자 수·일별 추이·최근 N일 인기글의 유일한 소스다.
 * (정 볼륨이 문제가 되면 이 클래스를 {@code ...post.application.log} 패키지로 옮기면
 *  기존 제외 규칙 {@code !execution(* kr.hvy..log..*Controller.*(..))} 에 걸려
 *  hvy-common 릴리스 없이 로깅만 끌 수 있다.)
 * <p>
 * 별도 컨트롤러로 분리한 이유: PostController 는 ObjectMapper·Validator 를 물고 있는 읽기 전용
 * 컨트롤러이고, 여기에 쓰기 + 남용 방어라는 다른 관심사를 섞지 않는다.
 * <p>
 * 경로가 {@code /api/*&#47;admin/**} 에 걸리지 않으므로 SecurityConfig 의 permitAll 대상이며,
 * CSRF 는 전역 비활성이라 별도 토큰이 필요 없다.
 */
@RestController
@RequestMapping("/api/post")
@RequiredArgsConstructor
public class PostViewController {

  private final PostViewService postViewService;

  /**
   * 조회 1건 기록. 미존재·비공개·중복·봇 무엇이든 조용히 204 를 반환한다 —
   * 예외를 던지면 ResponseWrapperConfigure 를 거쳐 Slack 알림까지 발송된다.
   */
  @PostMapping("/{postId}/view")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void recordView(@PathVariable Long postId, HttpServletRequest request) {
    postViewService.recordView(postId, resolveRemoteAddr(request), request.getHeader("User-Agent"));
  }

  /**
   * SystemLogAspect.getRemoteAddr() 와 동일한 우선순위로 클라이언트 IP 를 판정한다.
   * hvy-common 의 해당 메서드가 private 이라 소량 중복을 감수한다 — 공통 모듈을 건드리면
   * 태그 push → GH Packages → 핀 갱신 리드타임이 붙는다.
   */
  private String resolveRemoteAddr(HttpServletRequest request) {
    String remoteAddr = request.getHeader("X-Real-IP");
    if (remoteAddr == null) {
      remoteAddr = request.getHeader("X-Forwarded-For");
      if (remoteAddr != null && remoteAddr.contains(",")) {
        remoteAddr = remoteAddr.split(",")[0].trim();
      }
    }
    return remoteAddr == null ? request.getRemoteAddr() : remoteAddr;
  }
}
