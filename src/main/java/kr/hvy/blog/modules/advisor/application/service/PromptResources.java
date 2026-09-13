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

  /**
   * advice-v5 (2026-09-13): 종목 후보를 KOSPI 로 한정(advisor.markets)·섹터 지표는 양시장 기준임을 명시·thesis 300자/risk 150자 + 근거→해석→기대 흐름 구조
   * (Slack 이 전문을 싣는다). v4 는 news 블록 입력 + citedNews 출력. v3 는 market.global r20/r60 + market.link(β·상관) 입력. v2 는 dataAsOf·window·dataQuality·market.trend 입력, trendOutlook 출력.
   * 옛 파일은 비교용으로 남긴다.
   */
  public static final String ADVICE_VERSION = "advice-v5";
  /** lesson-v2 (2026-09-13): condition 에 trend 키 */
  public static final String LESSON_VERSION = "lesson-v2";
  /** chat-v1 (2026-09-13): Slack #hvy-advisor 채팅 봇 시스템 프롬프트 — 도구 결과만 인용·기준일 명시·조건부 시나리오·면책은 코드가 부착 */
  public static final String CHAT_VERSION = "chat-v1";
  static final String BASE = "prompts/advisor/";

  private final String adviceSystem;
  private final String adviceSha256;
  private final String lessonSystem;
  private final String lessonSha256;
  private final String chatSystem;
  private final String chatSha256;

  public PromptResources() {
    this.adviceSystem = load(BASE + "advice-system-v5.md");
    this.adviceSha256 = sha256(adviceSystem);
    this.lessonSystem = load(BASE + "lesson-system-v2.md");
    this.lessonSha256 = sha256(lessonSystem);
    this.chatSystem = load(BASE + "chat-system-v1.md");
    this.chatSha256 = sha256(chatSystem);
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

  public String chatSystem() {
    return chatSystem;
  }

  public String chatSha256() {
    return chatSha256;
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
