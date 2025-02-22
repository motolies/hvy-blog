package kr.hvy.blog.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hvy.common.advice.ResponseWrapperConfigure;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "kr.hvy.blog")
public class ResponseWrapperConfig extends ResponseWrapperConfigure {

  public ResponseWrapperConfig(ObjectMapper objectMapper) {
    super(objectMapper);
  }


}
