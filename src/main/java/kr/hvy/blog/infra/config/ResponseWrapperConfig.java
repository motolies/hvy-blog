package kr.hvy.blog.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;

import kr.hvy.common.aop.advice.ResponseWrapperConfigure;
import kr.hvy.common.aop.advice.dto.ApiResponse;
import kr.hvy.common.core.code.ApiResponseStatus;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.exception.SpecificationException;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "kr.hvy.blog")
public class ResponseWrapperConfig extends ResponseWrapperConfigure {

  public ResponseWrapperConfig(ObjectMapper objectMapper, Optional<Notify> notify) {
    super(objectMapper, notify, Optional.of(SlackChannel.ERROR.getChannel()));
  }

  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler({SpecificationException.class, DataNotFoundException.class, IllegalArgumentException.class, IllegalStateException.class})
  public ApiResponse<?> handleException(RuntimeException ex) {
    notify.ifPresent(value -> value.sendMessage(NotifyRequest.builder()
        .channel(defaultErrorChannel.orElse("#hvy-error"))
        .exception(ex)
        .build()));

    log.error("{} : ", ex.getClass().getSimpleName(), ex);
    return ApiResponse.builder()
        .status(ApiResponseStatus.FAIL)
        .message(ex.getMessage())
        .build();
  }

}
