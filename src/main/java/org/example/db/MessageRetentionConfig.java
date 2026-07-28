package org.example.db;

public final class MessageRetentionConfig {
    private static final int DEFAULT_RETENTION_DAYS = 30;
    private static final String INVALID_RETENTION_DAYS_MESSAGE =
            "MESSAGE_RETENTION_DAYS must be a positive integer";

    private MessageRetentionConfig() {
    }

    public static int parseDays(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT_RETENTION_DAYS;
        }

        try {
            int days = Integer.parseInt(rawValue.trim());
            if (days <= 0) {
                throw new IllegalArgumentException(INVALID_RETENTION_DAYS_MESSAGE);
            }
            return days;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(INVALID_RETENTION_DAYS_MESSAGE, e);
        }
    }
}
