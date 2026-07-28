package org.example.bot;

import org.example.db.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageListenerTest {
    @Test
    void deletesArchivedMessageByDiscordId(@TempDir Path temporaryDirectory) {
        RecordingRepository repository = new RecordingRepository(temporaryDirectory.resolve("archive.db"));

        new MessageListener(repository).deleteArchivedMessage("discord-message-id");

        assertEquals("discord-message-id", repository.deletedMessageId);
    }

    @Test
    void logsAndAbsorbsRepositoryFailureWhenDeletingArchivedMessage(@TempDir Path temporaryDirectory) {
        RecordingRepository repository = new RecordingRepository(temporaryDirectory.resolve("archive.db"));
        repository.failure = new IllegalStateException("database unavailable");
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        PrintStream originalError = System.err;

        try {
            System.setErr(new PrintStream(errorOutput));

            assertDoesNotThrow(() -> new MessageListener(repository).deleteArchivedMessage("discord-message-id"));
        } finally {
            System.setErr(originalError);
        }

        assertTrue(errorOutput.toString().contains("Failed to delete archived message: database unavailable"));
    }

    private static final class RecordingRepository extends MessageRepository {
        private String deletedMessageId;
        private RuntimeException failure;

        private RecordingRepository(Path databasePath) {
            super(databasePath.toString());
        }

        @Override
        public void deleteMessage(String messageId) {
            deletedMessageId = messageId;
            if (failure != null) {
                throw failure;
            }
        }
    }
}
