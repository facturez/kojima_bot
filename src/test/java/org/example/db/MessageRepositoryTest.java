package org.example.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    @TempDir
    Path tempDir;

    private MessageRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MessageRepository(
                tempDir.resolve("messages.db").toString(),
                30,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void deletionRemovesOnlyMatchingMessage() {
        repository.saveStoredMessage("m1", "c1", "g1", "a1", "user", "one", NOW.minusSeconds(60));
        repository.saveStoredMessage("m2", "c1", "g1", "a1", "user", "two", NOW.minusSeconds(30));

        repository.deleteMessage("m1");

        assertEquals(List.of("two"), repository.findRecentMessages("c1", 20)
                .stream().map(StoredMessage::content).toList());
    }

    @Test
    void bulkDeletionRemovesOnlySelectedMessages() {
        repository.saveStoredMessage("m1", "c1", "g1", "a1", "user", "one", NOW.minusSeconds(60));
        repository.saveStoredMessage("m2", "c1", "g1", "a1", "user", "two", NOW.minusSeconds(30));
        repository.saveStoredMessage("m3", "c1", "g1", "a1", "user", "three", NOW);

        repository.deleteMessages(List.of("m1", "m3"));

        assertEquals(List.of("two"), repository.findRecentMessages("c1", 20)
                .stream().map(StoredMessage::content).toList());
    }

    @Test
    void expiredRowsAreNotReturned() {
        repository.saveStoredMessage("old", "c1", "g1", "a1", "user", "old", NOW.minus(31, ChronoUnit.DAYS));
        repository.saveStoredMessage("new", "c1", "g1", "a1", "user", "new", NOW.minus(1, ChronoUnit.DAYS));

        assertEquals(List.of("new"), repository.findRecentMessages("c1", 20)
                .stream().map(StoredMessage::content).toList());
    }

    @Test
    void deletesExpiredRowsAndReportsTheirCount() {
        repository.saveStoredMessage("old", "c1", "g1", "a1", "user", "old", NOW.minus(31, ChronoUnit.DAYS));
        repository.saveStoredMessage("new", "c1", "g1", "a1", "user", "new", NOW.minus(1, ChronoUnit.DAYS));

        assertEquals(1, repository.deleteExpiredMessages());
        assertEquals(List.of("new"), repository.findRecentMessages("c1", 20)
                .stream().map(StoredMessage::content).toList());
    }
}
