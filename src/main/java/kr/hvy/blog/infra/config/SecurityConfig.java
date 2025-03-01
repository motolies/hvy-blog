package kr.hvy.blog.infra.config;

import java.util.Collections;
import kr.hvy.blog.infra.security.CustomAccessDeniedHandler;
import kr.hvy.blog.infra.security.CustomAuthenticationEntryPoint;
import kr.hvy.blog.infra.security.JwtFilter;
import kr.hvy.blog.infra.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
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
//      config.setAllowedOriginPatterns(Collections.singletonList("http://localhost:3000"));
      config.setAllowedOriginPatterns(Collections.singletonList("*"));
      config.setAllowCredentials(true);
      return config;
    };
  }


}
