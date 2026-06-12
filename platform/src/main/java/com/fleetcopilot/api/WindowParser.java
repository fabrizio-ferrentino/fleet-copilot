package com.fleetcopilot.api;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses compact time-window request params such as {@code 30s}, {@code 15m}, {@code 24h}, {@code
 * 7d}.
 */
public final class WindowParser {

  static final Duration MAX_WINDOW = Duration.ofDays(7);
  private static final Pattern FORMAT = Pattern.compile("(\\d{1,6})([smhd])");

  private WindowParser() {}

  public static Duration parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("missing window, use e.g. 30s, 15m, 24h, 7d");
    }
    Matcher matcher = FORMAT.matcher(raw.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "invalid window '" + raw + "', use e.g. 30s, 15m, 24h, 7d");
    }
    long amount = Long.parseLong(matcher.group(1));
    if (amount == 0) {
      throw new IllegalArgumentException("window must be greater than zero");
    }
    Duration window =
        switch (matcher.group(2)) {
          case "s" -> Duration.ofSeconds(amount);
          case "m" -> Duration.ofMinutes(amount);
          case "h" -> Duration.ofHours(amount);
          case "d" -> Duration.ofDays(amount);
          default -> throw new IllegalStateException("unreachable");
        };
    if (window.compareTo(MAX_WINDOW) > 0) {
      throw new IllegalArgumentException("window too large, maximum is 7d");
    }
    return window;
  }
}
