package kr.hvy.blog.modules.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import kr.hvy.blog.common.AbstractTestContainers;
import kr.hvy.blog.modules.advisor.application.chat.SlackChatRouter;
import kr.hvy.blog.modules.advisor.application.chat.SlackSocketModeRunner;
import kr.hvy.blog.modules.advisor.application.service.AdviseJob;
import kr.hvy.blog.modules.advisor.application.service.AdvisorOrchestrator;
import kr.hvy.blog.modules.advisor.application.service.MarketJudgeClient;
import kr.hvy.blog.modules.advisor.application.service.WeeklyReviewJob;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * advisor.enabled=true 로 컨텍스트가 뜨는지 검증한다.
 * <p>
 * 기본 부팅 테스트(BlogApplicationTests)는 advisor.enabled=false 라 @ConditionalOnProperty 빈이 등록조차 되지 않아, 2026-09-13 로컬 기동에서
 * 드러난 두 결함을 잡지 못했다 — (1) 잡 클래스의 생성자 2개·@Autowired 없음 → "No default constructor found", (2) Spring AI 빌더에 비동기
 * 클라이언트를 안 넘겨 환경변수 OPENAI_API_KEY 가 없으면 "At least one credential source must be specified". 두 결함이 있으면 같은 예외로 실패한다.
 * OpenAI 는 호출하지 않는다 — 빈 조립만 검증하므로 더미 키·빈 키로 충분하다.
 */
@ActiveProfiles("test")
class AdvisorContextBootTest {

  /** 오케스트레이터·잡 2종·MarketJudgeClient 빈 2종이 모두 조립되어야 한다 */
  static void assertAdvisorBeansWired(ApplicationContext context) {
    assertThat(context.getBean(AdvisorOrchestrator.class)).isNotNull();
    assertThat(context.getBean(AdviseJob.class)).isNotNull();
    assertThat(context.getBean(WeeklyReviewJob.class)).isNotNull();
    assertThat(context.getBean(MarketJudgeClient.JUDGE_BEAN, MarketJudgeClient.class)).isNotNull();
    assertThat(context.getBean(MarketJudgeClient.ASSIST_BEAN, MarketJudgeClient.class)).isNotNull();
  }

  /** 키가 있는 정상 경로 */
  @Nested
  @SpringBootTest(properties = {"advisor.enabled=true", "spring.ai.openai.api-key=test-key"})
  class WithApiKey extends AbstractTestContainers {

    @Autowired
    private ApplicationContext context;

    @Test
    void advisorBeansAreWired() {
      assertAdvisorBeansWired(context);
    }
  }

  /**
   * Slack 채팅 봇(chat-v1)을 켰지만 app-level 토큰·채널·허용 사용자가 없는 경우 — 러너·라우터 빈은 조립되되 Socket Mode 연결은 열지 않고 기동은 성공해야 한다.
   * 잘못된 Slack 설정으로 블로그 전체가 안 뜨면 안 된다는 AdvisorChatProperties 의 계약이 이 테스트의 존재 이유다.
   */
  @Nested
  @SpringBootTest(properties = {"advisor.enabled=true", "spring.ai.openai.api-key=test-key", "advisor.chat.enabled=true", "advisor.chat.app-token="})
  class WithChatEnabledButUnconfigured extends AbstractTestContainers {

    @Autowired
    private ApplicationContext context;

    @Test
    void chatBeansAreWiredButSocketModeIsNotStarted() {
      assertAdvisorBeansWired(context);
      assertThat(context.getBean(SlackChatRouter.class)).isNotNull();
      SlackSocketModeRunner runner = context.getBean(SlackSocketModeRunner.class);
      assertThat(runner.isAutoStartup()).isFalse();
      assertThat(runner.isRunning()).isFalse();
    }
  }

  /**
   * 키가 비어 있어도 기동은 되어야 한다(AdvisorAiConfig 의 계약: 잡 실행 시 isConfigured 로 거부, 기동 실패 아님).
   * 개발자 셸에 OPENAI_API_KEY 가 있어도 인라인 프로퍼티가 우선하므로 빈 키 경로가 실제로 검증된다.
   */
  @Nested
  @SpringBootTest(properties = {"advisor.enabled=true", "spring.ai.openai.api-key="})
  class WithoutApiKey extends AbstractTestContainers {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStartsAndAdvisorBeansAreWired() {
      assertAdvisorBeansWired(context);
    }
  }
}
