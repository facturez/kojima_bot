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
    void usesDay107OnJuly29MoscowDate() {
        String message = ScheduledMessageConfig.buildDailyMessageText(LocalDate.of(2026, 7, 29));
        assertEquals("день без сиеги шиянова 107", message);
    }

    @Test
    void incrementsDayCounterForEachFollowingCalendarDay() {
        assertEquals(
                "день без сиеги шиянова 108",
                ScheduledMessageConfig.buildDailyMessageText(LocalDate.of(2026, 7, 30))
        );
        assertEquals(
                "день без сиеги шиянова 109",
                ScheduledMessageConfig.buildDailyMessageText(LocalDate.of(2026, 7, 31))
        );
    }
}
