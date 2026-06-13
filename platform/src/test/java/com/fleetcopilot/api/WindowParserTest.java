package com.fleetcopilot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WindowParserTest {

  @Test
  void parsesSecondsMinutesHoursDays() {
    assertThat(WindowParser.parse("30s")).isEqualTo(Duration.ofSeconds(30));
    assertThat(WindowParser.parse("15m")).isEqualTo(Duration.ofMinutes(15));
    assertThat(WindowParser.parse("24h")).isEqualTo(Duration.ofHours(24));
    assertThat(WindowParser.parse("7d")).isEqualTo(Duration.ofDays(7));
  }

  @Test
  void rejectsUnknownFormat() {
    assertThatThrownBy(() -> WindowParser.parse("yesterday"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid window");
    assertThatThrownBy(() -> WindowParser.parse("10x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WindowParser.parse("h")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsZeroAndBlank() {
    assertThatThrownBy(() -> WindowParser.parse("0m"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("greater than zero");
    assertThatThrownBy(() -> WindowParser.parse("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WindowParser.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWindowsBeyondSevenDays() {
    assertThatThrownBy(() -> WindowParser.parse("8d"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
    assertThatThrownBy(() -> WindowParser.parse("169h"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
