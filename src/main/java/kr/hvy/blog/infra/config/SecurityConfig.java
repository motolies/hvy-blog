package kr.hvy.blog.infra.config;

import java.util.Collections;
import java.util.List;
import kr.hvy.blog.infra.security.CustomAccessDeniedHandler;
import kr.hvy.blog.infra.security.CustomAuthenticationEntryPoint;
import kr.hvy.blog.infra.security.JwtFilter;
import kr.hvy.blog.infra.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.CacheControlConfig;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtTokenProvider jwtTokenProvider;

  // CORS 허용 오리진 패턴(쉼표구분). 기본값은 하위호환을 위해 "*" 이나,
  // 운영에서는 hvy.cors.allowed-origins(또는 CORS_ALLOWED_ORIGINS) 로 실제 프론트 도메인만 허용할 것을 권장한다.
  // allowCredentials(true) 와 "*" 조합은 자격증명 포함 요청을 모든 오리진에 반사하므로 과대 허용이다.
  @Value("${hvy.cors.allowed-origins:*}")
  private List<String> allowedOriginPatterns;

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  protected SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .httpBasic(AbstractHttpConfigurer::disable)
        .csrf(AbstractHttpConfigurer::disable)
        .cors(corsConfigurer -> corsConfigurer.configurationSource(corsConfigurationSource()))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(request -> request
            .requestMatchers("/api/*/admin", "/api/*/admin/**").hasAuthority("ROLE_ADMIN").anyRequest().permitAll()
        )
        .exceptionHandling(exception -> exception.accessDeniedHandler(new CustomAccessDeniedHandler())
            .authenticationEntryPoint(new CustomAuthenticationEntryPoint()))
        .addFilterBefore(new JwtFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
        .headers(headers -> headers.frameOptions(FrameOptionsConfig::sameOrigin)
            .cacheControl(CacheControlConfig::disable));

    return http.build();
  }

  private CorsConfigurationSource corsConfigurationSource() {
    return request -> {
      CorsConfiguration config = new CorsConfiguration();
      config.setAllowedHeaders(Collections.singletonList("*"));
      config.setAllowedMethods(Collections.singletonList("*"));
      // 허용 오리진은 프로퍼티(hvy.cors.allowed-origins)로 외부화한다. 기본 "*" 는 하위호환용.
      config.setAllowedOriginPatterns(allowedOriginPatterns);
      config.setAllowCredentials(true);
      return config;
    };
  }


}
