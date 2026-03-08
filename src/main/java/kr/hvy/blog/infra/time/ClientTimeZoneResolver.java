package kr.hvy.blog.infra.time;

import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ClientTimeZoneResolver {

  public static final String TIMEZONE_HEADER = "X-Client-Timezone";
  public static final String OFFSET_HEADER = "X-Client-Utc-Offset-Minutes";

  private static final String RESOLVED_TIMEZONE_ATTRIBUTE =
      ClientTimeZoneResolver.class.getName() + ".resolvedTimeZone";
  private static final int MAX_OFFSET_MINUTES = 18 * 60;

  public ResolvedClientTimeZone resolve() {
    RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
      return ResolvedClientTimeZone.utcFallback("No servlet request is bound to the current thread");
    }

    return resolve(servletRequestAttributes.getRequest());
  }

  public ResolvedClientTimeZone resolve(HttpServletRequest request) {
    Object cached = request.getAttribute(RESOLVED_TIMEZONE_ATTRIBUTE);
    if (cached instanceof ResolvedClientTimeZone resolvedClientTimeZone) {
      return resolvedClientTimeZone;
    }

    ResolvedClientTimeZone resolvedClientTimeZone = resolve(
        request.getHeader(TIMEZONE_HEADER),
        request.getHeader(OFFSET_HEADER)
    );
    request.setAttribute(RESOLVED_TIMEZONE_ATTRIBUTE, resolvedClientTimeZone);
    return resolvedClientTimeZone;
  }

  ResolvedClientTimeZone resolve(String timeZoneHeader, String offsetHeader) {
    String normalizedTimeZone = normalizeHeader(timeZoneHeader);
    String normalizedOffset = normalizeHeader(offsetHeader);

    if (StringUtils.hasText(normalizedTimeZone)) {
      try {
        return ResolvedClientTimeZone.fromTimeZone(ZoneId.of(normalizedTimeZone));
      } catch (DateTimeException ignored) {
        Integer offsetMinutes = parseOffsetMinutes(normalizedOffset);
        if (offsetMinutes != null) {
          return ResolvedClientTimeZone.fromOffset(
              ZoneOffset.ofTotalSeconds(offsetMinutes * 60),
              "Invalid " + TIMEZONE_HEADER + " header [" + normalizedTimeZone + "]; "
                  + "falling back to " + OFFSET_HEADER + "=" + offsetMinutes
          );
        }

        return ResolvedClientTimeZone.utcFallback(
            "Invalid " + TIMEZONE_HEADER + " header [" + normalizedTimeZone + "] and unusable "
                + OFFSET_HEADER + " header [" + normalizedOffset + "]"
        );
      }
    }

    Integer offsetMinutes = parseOffsetMinutes(normalizedOffset);
    if (offsetMinutes != null) {
      return ResolvedClientTimeZone.fromOffset(
          ZoneOffset.ofTotalSeconds(offsetMinutes * 60),
          "Missing " + TIMEZONE_HEADER + " header; "
              + "falling back to " + OFFSET_HEADER + "=" + offsetMinutes
      );
    }

    if (StringUtils.hasText(normalizedOffset)) {
      return ResolvedClientTimeZone.utcFallback(
          "Missing " + TIMEZONE_HEADER + " header and unusable " + OFFSET_HEADER
              + " header [" + normalizedOffset + "]"
      );
    }

    return ResolvedClientTimeZone.utcFallback(
        "Missing both " + TIMEZONE_HEADER + " and " + OFFSET_HEADER + " headers"
    );
  }

  private Integer parseOffsetMinutes(String offsetHeader) {
    if (!StringUtils.hasText(offsetHeader)) {
      return null;
    }

    try {
      int offsetMinutes = Integer.parseInt(offsetHeader);
      if (Math.abs(offsetMinutes) > MAX_OFFSET_MINUTES) {
        return null;
      }
      return offsetMinutes;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String normalizeHeader(String headerValue) {
    return headerValue == null ? null : headerValue.strip();
  }
}
