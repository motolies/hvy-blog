package kr.hvy.blog.infra.security;

import com.google.common.net.InternetDomainName;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
@Slf4j
@Component
public class CookieProvider {

  private static final String COOKIE_PATH = "/";
  private static final boolean READONLY = true;
  private final int maxAge;

  public CookieProvider(@Value("${jwt.expiration}") int maxAge) {
    this.maxAge = maxAge;
  }

  private String getRootDomain(String refererUrl) {
    try {
      URI uri = new URI(refererUrl);
      return InternetDomainName.from(uri.getHost()).topDomainUnderRegistrySuffix().toString();
    } catch (Exception e) {
      log.error("getRootDomain error : {}", e.getMessage());
      return null;
    }
  }

  private ResponseCookie.ResponseCookieBuilder buildBaseCookie(String name, String value) {
    return ResponseCookie.from(name, value)
        .path(COOKIE_PATH)
        .httpOnly(READONLY)
        .maxAge(maxAge);
  }

  public ResponseCookie createCookie(HttpServletRequest request, String name, String value) {
    String domain = getRootDomain(request.getHeader("referer"));
    ResponseCookie.ResponseCookieBuilder builder = buildBaseCookie(name, value);
    if (domain != null) {
      builder.sameSite("None")
          .secure(true)
          .domain(domain);
    } else {
      builder.secure(false);
    }
    return builder.build();
  }

  public ResponseCookie removeCookie(HttpServletRequest request, String name) {
    String domain = getRootDomain(request.getHeader("referer"));
    // 값이 null이고 maxAge를 0으로 설정하여 쿠키 제거
    ResponseCookie.ResponseCookieBuilder builder = buildBaseCookie(name, null)
        .maxAge(0);
    if (domain != null) {
      builder.sameSite("None")
          .secure(true)
          .domain(domain);
    } else {
      builder.secure(false);
    }
    return builder.build();
  }
}