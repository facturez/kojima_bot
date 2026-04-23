package org.example.bot;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledMessageConfigTest {
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
