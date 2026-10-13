package kr.hvy.blog.modules.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpSecurityConfig {
  @Bean
  @Order(0)
  SecurityFilterChain mcpSecurity(HttpSecurity http,
      @Value("${MCP_API_KEY:}") String key,
      @Value("${mcp.allowed-origins:https://hvy.kr,https://api.hvy.kr}") List<String> origins) throws Exception {
    if (key.isBlank()) {
      throw new IllegalStateException("MCP_ENABLED requires MCP_API_KEY");
    }
    return http.securityMatcher("/mcp", "/mcp/**")
        .csrf(AbstractHttpConfigurer::disable).cors(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .addFilterBefore(new BearerFilter(key, origins), UsernamePasswordAuthenticationFilter.class).build();
  }

  static final class BearerFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final List<String> origins;
    BearerFilter(String key, List<String> origins) {
      this.expected = key.getBytes(StandardCharsets.UTF_8);
      this.origins = origins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String origin = request.getHeader("Origin");
      if (origin != null && !origins.contains(origin)) {
        response.setStatus(403);
        return;
      }
      if (origin != null) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept, MCP-Protocol-Version");
        if ("OPTIONS".equals(request.getMethod())) {
          response.setStatus(204);
          return;
        }
      }
      String auth = request.getHeader("Authorization");
      if (auth == null || !auth.startsWith("Bearer ")
          || !MessageDigest.isEqual(expected, auth.substring(7).getBytes(StandardCharsets.UTF_8))) {
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setStatus(401);
        return;
      }
      chain.doFilter(request, response);
    }
  }
}
