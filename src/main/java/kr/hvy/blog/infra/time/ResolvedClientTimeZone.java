package kr.hvy.blog.infra.time;

import java.time.ZoneId;

public record ResolvedClientTimeZone(ZoneId zoneId, ResolutionSource source, String warningReason) {

  public boolean hasWarning() {
    return warningReason != null && !warningReason.isBlank();
  }

  public static ResolvedClientTimeZone fromTimeZone(ZoneId zoneId) {
    return new ResolvedClientTimeZone(zoneId, ResolutionSource.TIMEZONE_HEADER, null);
  }

  public static ResolvedClientTimeZone fromOffset(ZoneId zoneId, String warningReason) {
    return new ResolvedClientTimeZone(zoneId, ResolutionSource.OFFSET_HEADER, warningReason);
  }

  public static ResolvedClientTimeZone utcFallback(String warningReason) {
    return new ResolvedClientTimeZone(ZoneId.of("UTC"), ResolutionSource.UTC_FALLBACK, warningReason);
  }

  public enum ResolutionSource {
    TIMEZONE_HEADER,
    OFFSET_HEADER,
    UTC_FALLBACK
  }
}
