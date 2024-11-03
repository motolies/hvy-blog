package kr.hvy.blog.modules.auth;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import kr.hvy.blog.infra.config.SecurityConfig;
import kr.hvy.blog.infra.security.CookieProvider;
import kr.hvy.blog.infra.security.CustomAccessDeniedHandler;
import kr.hvy.blog.infra.security.CustomAuthenticationEntryPoint;
import kr.hvy.blog.infra.security.JwtTokenProvider;
import kr.hvy.blog.modules.auth.framework.in.AuthController;
import kr.hvy.blog.modules.auth.application.service.UserManagementService;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.domain.dto.LoginRequest;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.dto.UserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;

// test 시에는 SecurityConfig 를 읽어가지 못해서 cors, csrf 등의 문제가 있으므로 추가함
@Import(SecurityConfig.class)
@WebMvcTest(AuthController.class)
public class AuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private CookieProvider cookieProvider;

  @Value("${jwt.header.name}")
  private String tokenHeader;

  @MockBean
  private JwtTokenProvider jwtTokenProvider; // SecurityConfig에서 사용하는 빈 모킹

  @MockBean
  private CustomAccessDeniedHandler customAccessDeniedHandler; // SecurityConfig에서 사용하는 빈 모킹

  @MockBean
  private CustomAuthenticationEntryPoint customAuthenticationEntryPoint; // SecurityConfig에서 사용하는 빈 모킹

  @MockBean
  private UserManagementService userManagementService; // userManagementUseCase 를 사용하면 default 함수를 호출해서 구현체 모킹

  @Test
  @DisplayName("POST /api/auth")
  void create() throws Exception {
    // given
    UserCreate userCreate = getUserCreate();

    UserResponse userResponse = getUserResponse();

    given(userManagementService.create(any(UserCreate.class)))
        .willReturn(userResponse);

    // Act & Assert
    mockMvc.perform(post("/api/auth")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(userCreate)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.name").value("my name"))
        .andExpect(jsonPath("$.username").value("hi"))
        .andExpect(jsonPath("$.isEnabled").value(true))
        .andExpect(jsonPath("$.authorities").isArray())
        .andExpect(jsonPath("$.authorities", hasItem("ROLE_USER")));
  }


  @Test
  @DisplayName("POST /api/auth/login")
  void login() throws Exception {
    // given
    LoginRequest loginRequest = getLoginRequest();
    UserResponse userResponse = getUserResponse();
    String mockToken = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlcyI6WyJST0xFX1VTRVIiXSwic3ViIjoiNjMzMjc2NTc2NjE1MDc3MDQzIiwiaWF0IjoxNzI4ODIxNzA4LCJleHAiOjE3NjAyNzEzMDh9.t_zHWtZKpgHBKNKQxO3EvnIko_qAD3GUlN6AiYev_2A";

    // Mocking UserManagementUseCase
    given(userManagementService.login(any(LoginRequest.class)))
        .willReturn(userResponse);
    given(userManagementService.makeToken(any(UserResponse.class)))
        .willReturn(mockToken);

    // Mocking CookieProvider
    ResponseCookie mockResponseCookie = ResponseCookie.from(tokenHeader, mockToken)
        .httpOnly(true)
        .secure(false) // 테스트 환경에 맞게 설정
        .path("/")
        .maxAge(31449600) // 1년
        .build();

    given(cookieProvider.setSpringCookie(any(HttpServletRequest.class), any(String.class), any(String.class)))
        .willReturn(mockResponseCookie);

    // Act & Assert
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(loginRequest)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.name").value("my name"))
        .andExpect(jsonPath("$.username").value("hi"))
        .andExpect(jsonPath("$.isEnabled").value(true))
        .andExpect(jsonPath("$.authorities").isArray())
        .andExpect(jsonPath("$.authorities", hasItem("ROLE_USER")))
        .andExpect(cookie().exists(tokenHeader)) // Authorization 쿠키 존재 여부 확인
        .andExpect(cookie().value(tokenHeader, mockToken)) // Authorization 쿠키의 값 확인
//        .andExpect(cookie().httpOnly("Authorization", true)) // HttpOnly 속성 확인
//        .andExpect(cookie().path("Authorization", "/")) // Path 속성 확인
//        .andExpect(cookie().maxAge("Authorization", 31449600)) // Max-Age 속성 확인
    ;
  }

  private static LoginRequest getLoginRequest() {
    return LoginRequest.builder()
        .username("hi")
        .password("bye")
        .build();
  }

  private static UserCreate getUserCreate() {
    return UserCreate.builder()
        .name("my name")
        .username("hi")
        .password("bye")
        .authorities(Set.of(AuthorityName.ROLE_USER))
        .build();
  }

  private static UserResponse getUserResponse() {
   return UserResponse.builder()
        .id(1L)
        .name("my name")
        .username("hi")
        .authorities(Set.of(AuthorityName.ROLE_USER))
        .isEnabled(true)
        .build();
  }


}
