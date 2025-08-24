package kr.hvy.blog.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CookieConfig {

  @Value("${cookie.same-site}")
  private String sameSite;

  @Bean
  public CookieSameSiteSupplier cookieSameSiteSupplier() {
    return CookieSameSiteSupplier.of(SameSite.valueOf(sameSite));
  }

//  @Value("${cookie.secure}")
//  private boolean secure;

//  세션 사용시에는 사용할 수 있을 것 같음
//  @Bean
//  public WebServerFactoryCustomizer<TomcatServletWebServerFactory> cookieSecureCustomizer() {
//    return factory -> factory.addContextCustomizers(context -> {
//      context.getServletContext().getSessionCookieConfig().setSecure(secure);
//    });
//  }
}
