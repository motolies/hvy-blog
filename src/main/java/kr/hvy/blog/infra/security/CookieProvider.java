package kr.hvy.blog.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CookieProvider {

  //https://velog.io/@kwakwoohyun/%EC%9D%B4%EC%8A%88%EC%B2%98%EB%A6%AC-spring-boot-local-cookie
  private static final String COOKIE_PATH = "/";
  private static final boolean READONLY = true;
  private int MAX_AGE;

  @Value("${cookie.secure}")
  private boolean secure;

  @Value("${jwt.expiration}")
  public void setMAX_AGE(String age) {
    this.MAX_AGE = Integer.parseInt(age);
  }

  public ResponseCookie setSpringCookie(HttpServletRequest request, String name, String value) {
    return ResponseCookie.from(name, value)
        .secure(secure)
        .path(COOKIE_PATH)
        .httpOnly(READONLY)
        .maxAge(MAX_AGE)
        .build();
  }

  public ResponseCookie removeSpringCookie(HttpServletRequest request, String name) {
    return ResponseCookie.from(name, null)
        .secure(secure)
        .path(COOKIE_PATH)
        .httpOnly(READONLY)
        .maxAge(0)
        .build();
  }

}
