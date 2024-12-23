package kr.hvy.blog.infra.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.SecretKey;
import kr.hvy.blog.modules.auth.domain.SecurityUser;
import kr.hvy.blog.modules.auth.domain.User;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

  @Value("${jwt.header.prefix}")
  private String tokenPrefix;

  @Value("${jwt.header.name}")
  private String tokenHeader;

  @Value("${jwt.secret}")
  private String secretKey;

  @Value("${jwt.expiration}")
  private long tokenValidSecond;

  @Value("${jwt.refresh.expiration}")
  private long refreshTokenValidSecond;

  // JWT 토큰 생성
  public String createToken(User user) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("roles", user.getAuthorities().stream().map(AuthorityName::getCode).toArray());
    claims.put("userId", user.getUsername());
    claims.put("name", user.getName());

    final Date createdDate = new Date();
    final Date expirationDate = new Date(createdDate.getTime() + tokenValidSecond * 1000);

    return Jwts.builder()
        .setClaims(claims)
        .setSubject(user.getId().toString())
        .setIssuedAt(createdDate)
        .setExpiration(expirationDate)
        .signWith(getSecretKey(secretKey))
        .compact();
  }


  private SecretKey getSecretKey(String secret) {
    byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
    return Keys.hmacShaKeyFor(keyBytes);
  }

  // JWT 토큰에서 인증 정보 조회(DB 조회하지 않고 강제주입)
  public Authentication getAuthenticationWithoutDB(String token) {
    String userId = getUserId(token);
    String loginId = getClaimFromToken(token, claims -> claims.get("userId", String.class));
    String name = getClaimFromToken(token, claims -> claims.get("name", String.class));
    Set<GrantedAuthority> roles = getAuthoritiesFromToken(token);
    SecurityUser user = SecurityUser.builder()
        .id(Long.parseLong(userId))
        .username(loginId)
        .name(name)
        .authorities(roles)
        .build();
    return new UsernamePasswordAuthenticationToken((UserDetails) user, "", roles);
  }

  public Set<GrantedAuthority> getAuthoritiesFromToken(String token) {
    HashSet<GrantedAuthority> auth = new HashSet<GrantedAuthority>();
    Function<Claims, ArrayList> func = t -> t.get("roles", ArrayList.class);

    List<String> listAuth = getClaimFromToken(token, func);
    for (String role : listAuth) {
      auth.add(new SimpleGrantedAuthority(role));
    }
    return auth;
  }


  public String getUserId(String token) {
    return getClaimFromToken(token, Claims::getSubject);
  }

  public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
    final Claims claims = getAllClaimsFromToken(token);

    if (claims != null) {
      return claimsResolver.apply(claims);
    } else {
      return null;
    }
  }

  private Claims getAllClaimsFromToken(String token) throws ExpiredJwtException {
    try {
      return Jwts.parserBuilder().setSigningKey(getSecretKey(secretKey)).build().parseClaimsJws(token).getBody();
    } catch (ExpiredJwtException e) {
      return null;
    }
  }


  // Request header에서 token 꺼내옴
  public String resolveToken(HttpServletRequest request) {
    String token = request.getHeader(tokenHeader);

    // 가져온 Authorization Header 가 문자열이고, Bearer 로 시작해야 가져옴
    if (StringUtils.hasText(token) && token.startsWith(tokenPrefix)) {
      return token.substring(tokenPrefix.length() + 1);
    } else {
      // 없으면 쿠키에서 재 탐색
      Cookie authCookie = WebUtils.getCookie(request, this.tokenHeader);
      if (authCookie != null) {
        return authCookie.getValue();
      }
    }

    return null;
  }

  // JWT 토큰 유효성 체크
  public boolean validateToken(String token) {
    try {
      Jws<Claims> claims = Jwts.parserBuilder().setSigningKey(getSecretKey(secretKey)).build().parseClaimsJws(token);
      return !claims.getBody().getExpiration().before(new Date());
    } catch (SecurityException | MalformedJwtException | IllegalArgumentException exception) {
      log.error("잘못된 Jwt 토큰입니다");
    } catch (ExpiredJwtException exception) {
      log.error("만료된 Jwt 토큰입니다");
    } catch (UnsupportedJwtException exception) {
      log.error("지원하지 않는 Jwt 토큰입니다");
    } catch (Exception exception) {
      log.error("잘못된 Jwt 토큰입니다");
    }

    return false;
  }
}
