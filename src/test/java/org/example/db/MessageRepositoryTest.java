package org.example.db;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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

        repository.deleteMessage("g1", "m1");

        assertEquals(List.of("two"), repository.findRecentMessages("g1", "c1", 20)
                .stream().map(StoredMessage::content).toList());
    }

    @Test
    void savesARealGuildMessageThroughThePublicArchiveBoundary() {
        Guild guild = proxy(Guild.class, (method, arguments) -> method.getName().equals("getId") ? "g1" : null);
        User author = proxy(User.class, (method, arguments) -> switch (method.getName()) {
            case "getId" -> "a1";
            case "getAsTag" -> "legacy-user";
            default -> null;
        });
        MessageChannelUnion channel = proxy(MessageChannelUnion.class,
                (method, arguments) -> method.getName().equals("getId") ? "c1" : null);
        Message message = proxy(Message.class, (method, arguments) -> switch (method.getName()) {
            case "isFromGuild" -> true;
            case "getGuild" -> guild;
            case "getAuthor" -> author;
            case "getChannel" -> channel;
            case "getId" -> "m1";
            case "getContentRaw" -> "legacy content";
            case "getTimeCreated" -> OffsetDateTime.parse("2026-07-28T11:00:00Z");
            default -> null;
        });

        repository.saveGuildMessage("g1", message);

        assertEquals(1, repository.countMessages("g1"));
        assertEquals(List.of("legacy content"), repository.findRecentMessages("g1", "c1", 20)
                .stream().map(StoredMessage::content).toList());
    }

    @Test
    void bulkDeletionRemovesOnlySelectedMessages() {
        repository.saveStoredMessage("m1", "c1", "g1", "a1", "user", "one", NOW.minusSeconds(60));
        repository.saveStoredMessage("m2", "c1", "g1", "a1", "user", "two", NOW.minusSeconds(30));
        repository.saveStoredMessage("m3", "c1", "g1", "a1", "user", "three", NOW);

        repository.deleteMessages("g1", List.of("m1", "m3"));

        assertEquals(List.of("two"), repository.findRecentMessages("g1", "c1", 20)
                .stream().map(StoredMessage::content).toList());
    }

    @Test
    void expiredRowsAreNotReturned() {
        repository.saveStoredMessage("old", "c1", "g1", "a1", "user", "old", NOW.minus(31, ChronoUnit.DAYS));
        repository.saveStoredMessage("new", "c1", "g1", "a1", "user", "new", NOW.minus(1, ChronoUnit.DAYS));

        assertEquals(List.of("new"), repository.findRecentMessages("g1", "c1", 20)
                .stream().map(StoredMessage::content).toList());
    }

    @Test
    void deletesExpiredRowsAndReportsTheirCount() {
        repository.saveStoredMessage("old", "c1", "g1", "a1", "user", "old", NOW.minus(31, ChronoUnit.DAYS));
        repository.saveStoredMessage("new", "c1", "g1", "a1", "user", "new", NOW.minus(1, ChronoUnit.DAYS));

        assertEquals(1, repository.deleteExpiredMessages());
        assertEquals(List.of("new"), repository.findRecentMessages("g1", "c1", 20)
                .stream().map(StoredMessage::content).toList());
    }

    @Test
    void retentionKeepsMessagesExactlyOnTheConfiguredCutoff() {
        repository.saveStoredMessage("boundary", "c1", "g1", "a1", "user", "kept",
                NOW.minus(30, ChronoUnit.DAYS));

        assertEquals(0, repository.deleteExpiredMessages());
        assertEquals(List.of("kept"), repository.findRecentMessages("g1", "c1", 20)
                .stream().map(StoredMessage::content).toList());
    }

    @Test
    void scopesEveryArchiveOperationToGuild() {
        repository.saveStoredMessage("a", "same", "g1", "author", "user", "one", NOW);
        repository.saveStoredMessage("b", "same", "g2", "author", "user", "two", NOW);

        assertEquals(1, repository.countMessages("g1"));
        assertEquals(1, repository.countMessagesByAuthor("g2", "author"));
        repository.deleteMessage("g1", "b");

        assertEquals(List.of("two"), repository.findRecentMessages("g2", "same", 20)
                .stream().map(StoredMessage::content).toList());
    }

    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> invocation.invoke(method, arguments));
    }
}
