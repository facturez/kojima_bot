package org.example.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageRetentionConfigTest {
    @Test
    void defaultsToThirtyDays() {
        assertEquals(30, MessageRetentionConfig.parseDays(null));
        assertEquals(30, MessageRetentionConfig.parseDays(" "));
    }

    @Test
    void acceptsPositiveDays() {
        assertEquals(7, MessageRetentionConfig.parseDays("7"));
    }

    @Test
    void rejectsInvalidDays() {
        assertThrows(IllegalArgumentException.class, () -> MessageRetentionConfig.parseDays("0"));
        assertThrows(IllegalArgumentException.class, () -> MessageRetentionConfig.parseDays("-1"));
        assertThrows(IllegalArgumentException.class, () -> MessageRetentionConfig.parseDays("abc"));
    }
}
