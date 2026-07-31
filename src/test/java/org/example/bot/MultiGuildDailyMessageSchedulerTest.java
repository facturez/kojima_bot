package org.example.bot;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiGuildDailyMessageSchedulerTest {
    @Test void calculatesNextMidnightInEachGuildTimezone() {
        ZonedDateTime instant = ZonedDateTime.parse("2026-07-31T20:30:00Z");

        assertEquals("2026-08-01T00:00+03:00[Europe/Moscow]",
                MultiGuildDailyMessageScheduler.nextRunAfter(instant, ZoneId.of("Europe/Moscow")).toString());
        assertEquals("2026-08-01T00:00-04:00[America/New_York]",
                MultiGuildDailyMessageScheduler.nextRunAfter(instant, ZoneId.of("America/New_York")).toString());
    }
}
