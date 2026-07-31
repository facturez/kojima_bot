package org.example.bot;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;

public final class SetupValidation {
    private SetupValidation() {}

    public static ZoneId parseZone(String value) {
        try { return ZoneId.of(value); }
        catch (ZoneRulesException | NullPointerException failure) {
            throw new IllegalArgumentException("Неизвестный часовой пояс. Используй IANA ID, например Europe/Moscow.");
        }
    }

    public static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Дата должна быть в формате YYYY-MM-DD."); }
    }

    public static String nonblank(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " не может быть пустым.");
        return value.trim();
    }
}
