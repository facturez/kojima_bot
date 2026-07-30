package org.example.bot;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeoutDurationParser {
    private static final Pattern FORMAT = Pattern.compile("^(\\d+)([mhd])$");
    private static final Duration MINIMUM = Duration.ofMinutes(1);
    private static final Duration MAXIMUM = Duration.ofDays(28);

    private TimeoutDurationParser() {
    }

    public static Duration parse(String raw) {
        Matcher matcher = FORMAT.matcher(raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw invalid();
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            Duration duration = switch (matcher.group(2)) {
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw invalid();
            };
            if (duration.compareTo(MINIMUM) < 0 || duration.compareTo(MAXIMUM) > 0) {
                throw invalid();
            }
            return duration;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Длительность должна быть от 1m до 28d. Доступные единицы: m, h, d.");
    }
}
