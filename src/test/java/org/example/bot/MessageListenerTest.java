package org.example.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.example.db.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MessageListenerTest {
    @Test
    void forwardsSlashCommandsToTheSlashHandler(@TempDir Path temporaryDirectory) {
        AtomicReference<String> reply = new AtomicReference<>();
        AtomicInteger acknowledgements = new AtomicInteger();
        ReplyCallbackAction action = (ReplyCallbackAction) Proxy.newProxyInstance(
                ReplyCallbackAction.class.getClassLoader(),
                new Class<?>[]{ReplyCallbackAction.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("setContent")) {
                        reply.set(arguments[0].toString());
                        return proxy;
                    }
                    if (method.getName().equals("queue") && method.getParameterCount() == 2) {
                        acknowledgements.incrementAndGet();
                        return null;
                    }
                    return method.getReturnType().isInstance(proxy) ? proxy : defaultValue(method.getReturnType());
                }
        );
        SlashCommandInteraction interaction = (SlashCommandInteraction) Proxy.newProxyInstance(
                SlashCommandInteraction.class.getClassLoader(),
                new Class<?>[]{SlashCommandInteraction.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "ping";
                    case "deferReply" -> action;
                    default -> defaultValue(method.getReturnType());
                }
        );
        JDA jda = (JDA) Proxy.newProxyInstance(
                JDA.class.getClassLoader(),
                new Class<?>[]{JDA.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );
        SlashCommandInteractionEvent event = new SlashCommandInteractionEvent(jda, 0, interaction);

        new MessageListener(
                new MessageRepository(temporaryDirectory.resolve("archive.db").toString())
        ).onSlashCommandInteraction(event);

        assertEquals("Pong! Бот у апппарата. Слушает батву", reply.get());
        assertEquals(1, acknowledgements.get());
    }

    @Test
    void deletesArchivedMessageByDiscordId(@TempDir Path temporaryDirectory) {
        RecordingRepository repository = new RecordingRepository(temporaryDirectory.resolve("archive.db"));

        new MessageListener(repository).deleteArchivedMessage("guild-id", "discord-message-id");

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

            assertDoesNotThrow(() -> new MessageListener(repository).deleteArchivedMessage("guild-id", "discord-message-id"));
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
        public void deleteMessage(String guildId, String messageId) {
            deletedMessageId = messageId;
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    @Test
    void archivesOnlyEnabledHumanGuildMessages() {
        assertTrue(MessageListener.shouldArchive(true, false, false, true));
        assertFalse(MessageListener.shouldArchive(false, false, false, true));
        assertFalse(MessageListener.shouldArchive(true, false, false, false));
        assertFalse(MessageListener.shouldArchive(true, true, false, true));
        assertFalse(MessageListener.shouldArchive(true, false, true, true));
    }
}
