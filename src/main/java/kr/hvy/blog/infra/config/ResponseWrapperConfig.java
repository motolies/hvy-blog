package kr.hvy.blog.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hvy.common.advice.ResponseWrapperConfigure;
import kr.hvy.common.advice.dto.ApiResponse;
import kr.hvy.common.code.ApiResponseStatus;
import kr.hvy.common.exception.SpecificationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "kr.hvy.blog")
public class ResponseWrapperConfig extends ResponseWrapperConfigure {

  public ResponseWrapperConfig(ObjectMapper objectMapper) {
    super(objectMapper);
  }


  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(SpecificationException.class)
  public ApiResponse<?> handleException(SpecificationException ex) {
    // todo : slack 또는 email로 예외 발생 알림을 전송합니다.
    log.error("SpecificationException : ", ex);
    return ApiResponse.builder()
        .status(ApiResponseStatus.FAIL)
        .message(ex.getMessage())
        .build();
  }

}
