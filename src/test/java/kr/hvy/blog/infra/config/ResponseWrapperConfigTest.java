package kr.hvy.blog.infra.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.exception.SpecificationException;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전역 예외 처리의 Slack 발송 정책: 400 으로 끝나는 예외는 보내지 않고 500 만 #hvy-error 로 보낸다.
 * standaloneSetup 으로 Spring 의 핸들러 선택까지 태운다 (컨텍스트·DB·Redis 불필요).
 */
class ResponseWrapperConfigTest {

  private static final String MESSAGE = "테스트 예외 메시지";

  private final Notify notify = mock(Notify.class);
  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new ThrowingController())
      .setControllerAdvice(new ResponseWrapperConfig(JsonMapper.builder().build(), Optional.of(notify)))
      .build();

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"specification", "not-found", "illegal-argument", "illegal-state"})
  @DisplayName("400 으로 끝나는 업무 예외는 FAIL 응답만 돌려주고 Slack 으로 보내지 않는다")
  void badRequestExceptionsStaySilent(String type) throws Exception {
    mockMvc.perform(get("/throw/{type}", type))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("FAIL"))
        .andExpect(jsonPath("$.message").value(MESSAGE));

    verifyNoInteractions(notify);
  }

  @Test
  @DisplayName("@Valid 검증 실패는 400 과 필드 오류를 돌려주고 Slack 으로 보내지 않는다")
  void validationFailureStaysSilent() throws Exception {
    mockMvc.perform(post("/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("FAIL"))
        .andExpect(jsonPath("$.data[0].field").value("name"))
        .andExpect(jsonPath("$.data[0].message").isNotEmpty());

    verifyNoInteractions(notify);
  }

  @Test
  @DisplayName("그 외 예외는 500 으로 응답하고 #hvy-error 채널로 예외를 보낸다")
  void serverErrorNotifiesErrorChannel() throws Exception {
    mockMvc.perform(get("/throw/{type}", "unexpected"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value("FAIL"))
        .andExpect(jsonPath("$.message").value("서버 오류가 발생하였습니다."));

    ArgumentCaptor<NotifyRequest> captor = ArgumentCaptor.forClass(NotifyRequest.class);
    verify(notify).sendMessage(captor.capture());
    assertThat(captor.getValue().getChannel()).isEqualTo(SlackChannel.ERROR.getChannel());
    assertThat(captor.getValue().getException()).isInstanceOf(RuntimeException.class).hasMessage(MESSAGE);
  }

  /** 예외 종류별로 전역 advice 를 태우기 위한 테스트 전용 컨트롤러. advice 의 basePackages(kr.hvy.blog) 안에 있어야 한다. */
  @RestController
  static class ThrowingController {

    /** 경로 변수로 지정한 종류의 예외를 던진다. 목록에 없는 값은 500 경로로 가는 RuntimeException 이다. */
    @GetMapping("/throw/{type}")
    public String raise(@PathVariable("type") String type) {
      throw switch (type) {
        case "specification" -> new SpecificationException(MESSAGE);
        case "not-found" -> new DataNotFoundException(MESSAGE);
        case "illegal-argument" -> new IllegalArgumentException(MESSAGE);
        case "illegal-state" -> new IllegalStateException(MESSAGE);
        default -> new RuntimeException(MESSAGE);
      };
    }

    /** @Valid 검증을 거치는 엔드포인트. name 이 비면 MethodArgumentNotValidException 이 난다. */
    @PostMapping("/valid")
    public String valid(@Valid @RequestBody ValidBody body) {
      return body.name();
    }
  }

  /** @Valid 검증 대상 본문 */
  record ValidBody(@NotBlank String name) {

  }
}
