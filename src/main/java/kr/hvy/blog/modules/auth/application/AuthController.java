package kr.hvy.blog.modules.auth.application;

import jakarta.servlet.http.HttpServletRequest;
import kr.hvy.blog.infra.security.CookieProvider;
import kr.hvy.blog.modules.auth.application.dto.LoginRequest;
import kr.hvy.blog.modules.auth.application.dto.RsaKeyResponse;
import kr.hvy.blog.modules.auth.application.dto.UserCreate;
import kr.hvy.blog.modules.auth.application.dto.UserResponse;
import kr.hvy.blog.modules.auth.application.service.UserService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  @Value("${jwt.header.name}")
  private String tokenHeader;

  private final CookieProvider cookieProvider;
  private final UserService userService;

  @PostMapping("/shake")
  public RsaKeyResponse shake() {
    return userService.getRsaKey();
  }

  @GetMapping("/profile")
  public ResponseEntity<?> getMeInfo(Authentication authentication) {
    if (ObjectUtils.isEmpty(authentication)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    return ResponseEntity.ok(userService.getProfile());
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(HttpServletRequest request, @RequestBody LoginRequest loginRequest) {
    UserResponse user = userService.login(loginRequest);
    String token = userService.makeToken(user);

    ResponseCookie springCookie = cookieProvider.createCookie(request, tokenHeader, token);

    return ResponseEntity
        .status(HttpStatus.OK)
        .header(HttpHeaders.SET_COOKIE, springCookie.toString())
        .body(user);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse create(@RequestBody UserCreate userCreate) {
    return userService.create(userCreate);
  }
}
