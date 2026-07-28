package org.example.bot;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledMessageConfigTest {
    @Test
    void usesMoscowTimeZone() {
        assertEquals(ZoneId.of("Europe/Moscow"), ScheduledMessageConfig.TIME_ZONE);
    }

    @Test
    void usesBaseDayNumberOnBaseDate() {
        String message = ScheduledMessageConfig.buildDailyMessageText(LocalDate.of(2026, 4, 23));
        assertEquals("день без сереги шиянова 365", message);
    }

    @Test
    void incrementsDayCounterForNextDay() {
        String message = ScheduledMessageConfig.buildDailyMessageText(LocalDate.of(2026, 4, 24));
        assertEquals("день без сереги шиянова 366", message);
    }
}
