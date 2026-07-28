package org.example.bot;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyMessageSchedulerTest {
    @Test
    void schedulesNextMoscowMidnightIndependentlyOfInputOffset() {
        ZonedDateTime now = ZonedDateTime.parse("2026-07-28T22:30:00+02:00[Europe/Berlin]");

        ZonedDateTime next = DailyMessageScheduler.nextRunAfter(now, ZoneId.of("Europe/Moscow"));

        assertEquals(ZonedDateTime.parse("2026-07-29T00:00:00+03:00[Europe/Moscow]"), next);
    }
}
