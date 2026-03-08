package kr.hvy.blog.infra.time;

import java.time.LocalDateTime;

public record UtcDateRange(LocalDateTime fromInclusive, LocalDateTime toExclusive) {

}
