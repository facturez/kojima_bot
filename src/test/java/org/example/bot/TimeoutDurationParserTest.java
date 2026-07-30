package org.example.bot;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeoutDurationParserTest {
    @ParameterizedTest
    @CsvSource({"1m,PT1M", "30m,PT30M", "2h,PT2H", "28d,PT672H"})
    void parsesSupportedDurations(String raw, String expected) {
        assertEquals(Duration.parse(expected), TimeoutDurationParser.parse(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0m", "-1m", "1s", "29d", "abc", "999999999999999999999d"})
    void rejectsUnsupportedDurations(String raw) {
        assertThrows(IllegalArgumentException.class, () -> TimeoutDurationParser.parse(raw));
    }
}
