package kr.hvy.blog.infra.time;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserDateTimeConverter {

  private static final ZoneId UTC = ZoneOffset.UTC;
  private static final String WARNING_LOGGED_ATTRIBUTE =
      BrowserDateTimeConverter.class.getName() + ".warningLogged";

  private final ClientTimeZoneResolver clientTimeZoneResolver;

  public UtcDateRange toUtcDateRange(LocalDate from, LocalDate to) {
    return toUtcDateRange(from, to, clientTimeZoneResolver.resolve());
  }

  public UtcDateRange toUtcDateRange(
      LocalDate from,
      LocalDate to,
      ResolvedClientTimeZone resolvedClientTimeZone
  ) {
    if (from == null && to == null) {
      return new UtcDateRange(null, null);
    }

    warnIfNeeded(resolvedClientTimeZone);

    LocalDateTime fromUtc = from == null
        ? null
        : toUtcAtStartOfDay(from, resolvedClientTimeZone.zoneId());
    LocalDateTime toUtcExclusive = to == null
        ? null
        : toUtcAtStartOfDay(to.plusDays(1), resolvedClientTimeZone.zoneId());

    return new UtcDateRange(fromUtc, toUtcExclusive);
  }

  public LocalDateTime toUtc(LocalDateTime browserLocalDateTime) {
    return toUtc(browserLocalDateTime, clientTimeZoneResolver.resolve());
  }

  public LocalDateTime toUtc(
      LocalDateTime browserLocalDateTime,
      ResolvedClientTimeZone resolvedClientTimeZone
  ) {
    if (browserLocalDateTime == null) {
      return null;
    }

    warnIfNeeded(resolvedClientTimeZone);

    return browserLocalDateTime.atZone(resolvedClientTimeZone.zoneId())
        .withZoneSameInstant(UTC)
        .toLocalDateTime();
  }

  private LocalDateTime toUtcAtStartOfDay(LocalDate browserDate, ZoneId zoneId) {
    return browserDate.atStartOfDay(zoneId)
        .withZoneSameInstant(UTC)
        .toLocalDateTime();
  }

  private void warnIfNeeded(ResolvedClientTimeZone resolvedClientTimeZone) {
    if (!resolvedClientTimeZone.hasWarning()) {
      return;
    }

    RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    if (requestAttributes instanceof ServletRequestAttributes servletRequestAttributes) {
      HttpServletRequest request = servletRequestAttributes.getRequest();
      if (Boolean.TRUE.equals(request.getAttribute(WARNING_LOGGED_ATTRIBUTE))) {
        return;
      }
      request.setAttribute(WARNING_LOGGED_ATTRIBUTE, Boolean.TRUE);
    }

    log.warn(
        "Client timezone headers are incomplete or invalid. {}. Using zone [{}] from [{}].",
        resolvedClientTimeZone.warningReason(),
        resolvedClientTimeZone.zoneId(),
        resolvedClientTimeZone.source()
    );
  }
}
