package org.example.db;

import java.time.Instant;

public record StoredMessage(String authorTag, String content, Instant createdAt) {
}
