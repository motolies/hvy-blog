package kr.hvy.blog.infra.config;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;

import kr.hvy.common.aop.advice.ResponseWrapperConfigure;
import kr.hvy.common.aop.advice.dto.ApiResponse;
import kr.hvy.common.aop.advice.dto.FieldValidation;
import kr.hvy.common.core.code.ApiResponseStatus;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.exception.SpecificationException;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RestControllerAdvice(basePackages = "kr.hvy.blog")
public class ResponseWrapperConfig extends ResponseWrapperConfigure {

  public ResponseWrapperConfig(ObjectMapper objectMapper, Optional<Notify> notify) {
    super(objectMapper, notify, Optional.of(SlackChannel.ERROR.getChannel()));
  }

  // 400 계열 업무 예외는 예상된 실패이므로 Slack 으로 보내지 않고 로그만 남긴다. Slack 은 부모의 500 핸들러만 보낸다.
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler({SpecificationException.class, DataNotFoundException.class, IllegalArgumentException.class, IllegalStateException.class})
  public ApiResponse<?> handleException(RuntimeException ex) {
    log.error("{} : ", ex.getClass().getSimpleName(), ex);
    return ApiResponse.builder()
        .status(ApiResponseStatus.FAIL)
        .message(ex.getMessage())
        .build();
  }

  // @Valid 검증 실패(400)는 필드 오류만 응답에 담는다. 부모 구현은 Slack 을 보내므로 재정의한다.
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    Set<FieldValidation> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> FieldValidation.builder()
            .field(error.getField())
            .message(error.getDefaultMessage())
            .receivedValue(error.getRejectedValue())
            .build())
        .collect(Collectors.toSet());

    // 컨트롤러 진입 전 예외라 SystemLogAspect 가 못 잡는다. 입력값은 빼고 필드명·메시지만 남긴다.
    log.warn("MethodArgumentNotValidException : {}", errors.stream()
        .map(error -> error.getField() + ": " + error.getMessage())
        .collect(Collectors.joining(", ")));

    return ResponseEntity.status(status)
        .body(ApiResponse.builder()
            .status(ApiResponseStatus.FAIL)
            .data(errors)
            .build());
  }

}
