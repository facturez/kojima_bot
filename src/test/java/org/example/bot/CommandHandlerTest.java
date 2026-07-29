package org.example.bot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.example.db.MessageRepository;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandHandlerTest {
    @TempDir
    Path tempDir;

    @Test
    void channelOverrideCanDenyArchiveDespiteGuildWideManageMessagesPermission() {
        AtomicReference<String> sentContent = new AtomicReference<>();
        MessageCreateAction action = (MessageCreateAction) Proxy.newProxyInstance(
                MessageCreateAction.class.getClassLoader(),
                new Class<?>[]{MessageCreateAction.class},
                (proxy, method, arguments) -> null
        );
        Object channel = Proxy.newProxyInstance(
                MessageChannelUnion.class.getClassLoader(),
                new Class<?>[]{MessageChannelUnion.class, GuildMessageChannelUnion.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")) {
                        sentContent.set(arguments[0].toString());
                        return action;
                    }
                    if (method.getName().equals("getId")) {
                        return "123";
                    }
                    return null;
                }
        );
        Member member = (Member) Proxy.newProxyInstance(
                Member.class.getClassLoader(),
                new Class<?>[]{Member.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("hasPermission")) {
                        return null;
                    }
                    if (arguments.length == 1) {
                        return true;
                    }
                    Permission[] permissions = (Permission[]) arguments[1];
                    return arguments[0] != channel
                            || !Arrays.asList(permissions).contains(Permission.MESSAGE_MANAGE);
                }
        );
        Message message = (Message) Proxy.newProxyInstance(
                Message.class.getClassLoader(),
                new Class<?>[]{Message.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getContentRaw" -> "!last";
                    case "getChannel", "getGuildChannel" -> channel;
                    case "getMember" -> member;
                    case "isFromGuild" -> true;
                    default -> null;
                }
        );
        CommandHandler handler = new CommandHandler(
                new MessageRepository(tempDir.resolve("archive.db").toString())
        );

        handler.handle(message);

        assertEquals(
                "Команда !last доступна только на сервере участникам с правом управления сообщениями.",
                sentContent.get()
        );
    }

    @Test
    void observesFailureFromEveryBulkPurgeOperation() {
        CompletableFuture<Void> firstPurge = new CompletableFuture<>();
        CompletableFuture<Void> secondPurge = new CompletableFuture<>();
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        PrintStream originalError = System.err;

        try {
            System.setErr(new PrintStream(errorOutput));
            CommandHandler.observePurgeFailures(List.of(firstPurge, secondPurge));

            firstPurge.completeExceptionally(new IllegalStateException("first request failed"));
            secondPurge.completeExceptionally(new IllegalStateException("second request failed"));
        } finally {
            System.setErr(originalError);
        }

        assertTrue(errorOutput.toString().contains("Failed to purge Discord messages: first request failed"));
        assertTrue(errorOutput.toString().contains("Failed to purge Discord messages: second request failed"));
    }

    @Test
    void queuesDiscordMessageWithExplicitFailureHandler() {
        AtomicReference<String> sentContent = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> failureHandler = new AtomicReference<>();
        MessageCreateAction action = (MessageCreateAction) Proxy.newProxyInstance(
                MessageCreateAction.class.getClassLoader(),
                new Class<?>[]{MessageCreateAction.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("queue") && method.getParameterCount() == 2) {
                        @SuppressWarnings("unchecked")
                        Consumer<Throwable> handler = (Consumer<Throwable>) arguments[1];
                        failureHandler.set(handler);
                    }
                    return null;
                }
        );
        MessageChannel channel = (MessageChannel) Proxy.newProxyInstance(
                MessageChannel.class.getClassLoader(),
                new Class<?>[]{MessageChannel.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")) {
                        sentContent.set(arguments[0].toString());
                        return action;
                    }
                    return null;
                }
        );

        CommandHandler.sendMessage(channel, "hello Discord");

        assertEquals("hello Discord", sentContent.get());
        assertNotNull(failureHandler.get());

        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        PrintStream originalError = System.err;
        try {
            System.setErr(new PrintStream(errorOutput));
            failureHandler.get().accept(new IllegalStateException("network unavailable"));
        } finally {
            System.setErr(originalError);
        }

        assertTrue(errorOutput.toString().contains("Failed to send Discord message: network unavailable"));
    }
}
