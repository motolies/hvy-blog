package kr.hvy.blog.modules.advisor.application.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 프롬프트 리소스(정적 시스템 프롬프트) 로더. 파일을 고치면 버전 상수도 올린다 — run·advice 에 버전과 SHA-256 을 함께 남겨
 * "어떤 프롬프트가 만든 판단인가" 를 사후에 분리한다.
 * <p>
 * 시스템 프롬프트는 템플릿 렌더링을 거치지 않고 원문 그대로 보낸다(Spring AI 템플릿 렌더러가 {} 를 변수로 해석하는 함정 회피).
 */
@Component
public class PromptResources {

  public static final String ADVICE_VERSION = "advice-v1";
  public static final String LESSON_VERSION = "lesson-v1";
  static final String BASE = "prompts/advisor/";

  private final String adviceSystem;
  private final String adviceSha256;
  private final String lessonSystem;
  private final String lessonSha256;

  public PromptResources() {
    this.adviceSystem = load(BASE + "advice-system-v1.md");
    this.adviceSha256 = sha256(adviceSystem);
    this.lessonSystem = load(BASE + "lesson-system-v1.md");
    this.lessonSha256 = sha256(lessonSystem);
  }

  public String adviceSystem() {
    return adviceSystem;
  }

  public String adviceSha256() {
    return adviceSha256;
  }

  public String lessonSystem() {
    return lessonSystem;
  }

  public String lessonSha256() {
    return lessonSha256;
  }

  static String load(String path) {
    try (InputStream in = new ClassPathResource(path).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("프롬프트 리소스를 읽을 수 없습니다: " + path, e);
    }
  }

  static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
