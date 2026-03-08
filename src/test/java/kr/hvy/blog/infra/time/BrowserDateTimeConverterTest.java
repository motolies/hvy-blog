package kr.hvy.blog.infra.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(OutputCaptureExtension.class)
class BrowserDateTimeConverterTest {

  private final BrowserDateTimeConverter browserDateTimeConverter =
      new BrowserDateTimeConverter(new ClientTimeZoneResolver());

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void convertsKstDateRangeToUtc(CapturedOutput output) {
    bindRequest("Asia/Seoul", "540");

    UtcDateRange utcDateRange = browserDateTimeConverter.toUtcDateRange(
        LocalDate.of(2026, 3, 8),
        LocalDate.of(2026, 3, 8)
    );

    assertEquals(LocalDateTime.of(2026, 3, 7, 15, 0), utcDateRange.fromInclusive());
    assertEquals(LocalDateTime.of(2026, 3, 8, 15, 0), utcDateRange.toExclusive());
    assertFalse(output.getOut().contains("Client timezone headers are incomplete or invalid"));
  }

  @Test
  void fallsBackToOffsetWhenTimeZoneHeaderIsInvalid(CapturedOutput output) {
    bindRequest("Invalid/Timezone", "540");

    UtcDateRange utcDateRange = browserDateTimeConverter.toUtcDateRange(
        LocalDate.of(2026, 3, 8),
        LocalDate.of(2026, 3, 8)
    );

    assertEquals(LocalDateTime.of(2026, 3, 7, 15, 0), utcDateRange.fromInclusive());
    assertEquals(LocalDateTime.of(2026, 3, 8, 15, 0), utcDateRange.toExclusive());
    assertTrue(output.getOut().contains("Invalid X-Client-Timezone header"));
  }

  @Test
  void fallsBackToUtcWhenHeadersAreMissing(CapturedOutput output) {
    bindRequest(null, null);

    UtcDateRange utcDateRange = browserDateTimeConverter.toUtcDateRange(
        LocalDate.of(2026, 3, 8),
        LocalDate.of(2026, 3, 8)
    );

    assertEquals(LocalDateTime.of(2026, 3, 8, 0, 0), utcDateRange.fromInclusive());
    assertEquals(LocalDateTime.of(2026, 3, 9, 0, 0), utcDateRange.toExclusive());
    assertTrue(output.getOut().contains("Missing both X-Client-Timezone and X-Client-Utc-Offset-Minutes headers"));
  }

  @Test
  void usesZoneIdForDstTransitionInsteadOfStaticOffset(CapturedOutput output) {
    bindRequest("America/Los_Angeles", "-480");

    UtcDateRange utcDateRange = browserDateTimeConverter.toUtcDateRange(
        LocalDate.of(2026, 3, 8),
        LocalDate.of(2026, 3, 8)
    );

    assertEquals(LocalDateTime.of(2026, 3, 8, 8, 0), utcDateRange.fromInclusive());
    assertEquals(LocalDateTime.of(2026, 3, 9, 7, 0), utcDateRange.toExclusive());
    assertFalse(output.getOut().contains("Client timezone headers are incomplete or invalid"));
  }

  private void bindRequest(String timeZone, String offsetMinutes) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (timeZone != null) {
      request.addHeader(ClientTimeZoneResolver.TIMEZONE_HEADER, timeZone);
    }
    if (offsetMinutes != null) {
      request.addHeader(ClientTimeZoneResolver.OFFSET_HEADER, offsetMinutes);
    }

    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }
}
