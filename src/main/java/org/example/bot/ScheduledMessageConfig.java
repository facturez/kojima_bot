package org.example.bot;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class ScheduledMessageConfig {
    public static final ZoneId TIME_ZONE = ZoneId.of("Europe/Moscow");

    public static final String DAILY_CHANNEL_ID = getEnvOrDefault("DAILY_CHANNEL_ID", "PASTE_CHANNEL_ID_HERE");
    public static final String DAILY_MESSAGE_PREFIX = "день без сиеги шиянова";
    public static final LocalDate BASE_DATE = LocalDate.of(2026, 7, 29);
    public static final int BASE_DAY_NUMBER = 107;

    private ScheduledMessageConfig() {
    }

    public static String buildDailyMessageText() {
        return buildDailyMessageText(LocalDate.now(TIME_ZONE));
    }

    public static String buildDailyMessageText(LocalDate date) {
        long daysPassed = ChronoUnit.DAYS.between(BASE_DATE, date);
        long currentDayNumber = BASE_DAY_NUMBER + daysPassed;
        return DAILY_MESSAGE_PREFIX + " " + currentDayNumber;
    }

    private static String getEnvOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
