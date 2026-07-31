package org.example.bot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.example.db.StoredMessage;
import org.example.db.CallSettingsPatch;
import org.example.db.GuildConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.example.db.MessageRepository;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
    void helpAndPingKeepTheirLegacyReplies() {
        PrefixFixture fixture = new PrefixFixture();
        CommandHandler handler = new CommandHandler(new MessageRepository(tempDir.resolve("simple.db").toString()));

        handler.handle(fixture.message("!help"));
        handler.handle(fixture.message("!ping"));

        assertTrue(fixture.responses.get(0).contains("!stats - статистика по сохраненным сообщениям"));
        assertEquals("Pong! Бот на связи.", fixture.responses.get(1));
    }

    @Test
    void statsUsesOnlyTheCurrentGuildAndAuthor() {
        PrefixFixture fixture = new PrefixFixture();
        AtomicReference<String> countedGuild = new AtomicReference<>();
        AtomicReference<String> countedAuthor = new AtomicReference<>();
        MessageRepository repository = new MessageRepository(tempDir.resolve("stats.db").toString()) {
            @Override public long countMessages(String guildId) {
                countedGuild.set(guildId);
                return 12;
            }
            @Override public long countMessagesByAuthor(String guildId, String authorId) {
                assertEquals("guild-id", guildId);
                countedAuthor.set(authorId);
                return 3;
            }
        };

        new CommandHandler(repository).handle(fixture.message("!stats"));

        assertEquals("guild-id", countedGuild.get());
        assertEquals("user-id", countedAuthor.get());
        assertTrue(fixture.responses.get(0).contains("Всего сообщений: 12"));
        assertTrue(fixture.responses.get(0).contains("Твоих сообщений: 3"));
    }

    @Test
    void callUsesMigratedGuildConfigurationAndRepeatCount() {
        PrefixFixture fixture = new PrefixFixture();
        fixture.administrator = true;
        GuildConfigRepository configs = new GuildConfigRepository(tempDir.resolve("call.db").toString());
        configs.activateGuild("guild-id", "Legacy Guild");
        configs.updateCall("guild-id", new CallSettingsPatch(
                Optional.of(true), Optional.of("legacy call"), Optional.of(3)));

        new CommandHandler(new MessageRepository(tempDir.resolve("call-archive.db").toString()), configs)
                .handle(fixture.message("!зов"));

        assertEquals(List.of("@everyone legacy call", "@everyone legacy call", "@everyone legacy call"),
                fixture.responses);
    }

    @Test
    void clearStillRequiresAdministratorPermission() {
        PrefixFixture fixture = new PrefixFixture();

        new CommandHandler(new MessageRepository(tempDir.resolve("clear.db").toString()))
                .handle(fixture.message("!clear"));

        assertEquals(List.of("Эта команда доступна только администраторам."), fixture.responses);
    }

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
        Guild guild = (Guild) Proxy.newProxyInstance(
                Guild.class.getClassLoader(), new Class<?>[]{Guild.class},
                (proxy, method, arguments) -> method.getName().equals("getId") ? "guild-id" : null
        );
        Message message = (Message) Proxy.newProxyInstance(
                Message.class.getClassLoader(),
                new Class<?>[]{Message.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getContentRaw" -> "!last";
                    case "getChannel", "getGuildChannel" -> channel;
                    case "getMember" -> member;
                    case "getGuild" -> guild;
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
    void lastDisablesEveryAllowedMentionWhenReplayingArchivedText() {
        AtomicReference<String> sentContent = new AtomicReference<>();
        AtomicReference<List<Message.MentionType>> allowedMentions = new AtomicReference<>();
        MessageCreateAction action = (MessageCreateAction) Proxy.newProxyInstance(
                MessageCreateAction.class.getClassLoader(),
                new Class<?>[]{MessageCreateAction.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("setAllowedMentions")) {
                        @SuppressWarnings("unchecked")
                        Collection<Message.MentionType> mentions =
                                (Collection<Message.MentionType>) arguments[0];
                        allowedMentions.set(List.copyOf(mentions));
                        return proxy;
                    }
                    return method.getReturnType().isInstance(proxy) ? proxy : null;
                }
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
                    if (method.getName().equals("hasPermission") && arguments.length == 2) {
                        return true;
                    }
                    return null;
                }
        );
        Guild archiveGuild = (Guild) Proxy.newProxyInstance(
                Guild.class.getClassLoader(), new Class<?>[]{Guild.class},
                (proxy, method, arguments) -> method.getName().equals("getId") ? "guild-id" : null
        );
        Message message = (Message) Proxy.newProxyInstance(
                Message.class.getClassLoader(),
                new Class<?>[]{Message.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getContentRaw" -> "!last";
                    case "getChannel", "getGuildChannel" -> channel;
                    case "getMember" -> member;
                    case "getGuild" -> archiveGuild;
                    case "isFromGuild" -> true;
                    default -> null;
                }
        );
        MessageRepository repository = new MessageRepository(
                tempDir.resolve("mention-archive.db").toString()
        ) {
            @Override
            public List<StoredMessage> findRecentMessages(String guildId, String channelId, int limit) {
                return List.of(new StoredMessage(
                        "reader",
                        "@everyone <@123456789012345678> <@&987654321098765432>",
                        Instant.parse("2026-07-30T10:15:30Z")
                ));
            }
        };

        new CommandHandler(repository).handle(message);

        assertTrue(sentContent.get().contains(
                "@everyone <@123456789012345678> <@&987654321098765432>"
        ));
        assertNotNull(allowedMentions.get());
        assertEquals(List.of(), allowedMentions.get());
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

    private static final class PrefixFixture {
        private final List<String> responses = new java.util.ArrayList<>();
        private boolean administrator;
        private final MessageCreateAction action = (MessageCreateAction) Proxy.newProxyInstance(
                MessageCreateAction.class.getClassLoader(), new Class<?>[]{MessageCreateAction.class},
                (proxy, method, arguments) -> method.getReturnType().isInstance(proxy) ? proxy : null);
        private final Object channel = Proxy.newProxyInstance(
                MessageChannelUnion.class.getClassLoader(),
                new Class<?>[]{MessageChannelUnion.class, GuildMessageChannelUnion.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")) {
                        responses.add(arguments[0].toString());
                        return action;
                    }
                    if (method.getName().equals("getId")) return "channel-id";
                    return null;
                });
        private final Guild guild = (Guild) Proxy.newProxyInstance(
                Guild.class.getClassLoader(), new Class<?>[]{Guild.class},
                (proxy, method, arguments) -> method.getName().equals("getId") ? "guild-id" : null);
        private final User user = (User) Proxy.newProxyInstance(
                User.class.getClassLoader(), new Class<?>[]{User.class},
                (proxy, method, arguments) -> method.getName().equals("getId") ? "user-id" : null);
        private final Member member = (Member) Proxy.newProxyInstance(
                Member.class.getClassLoader(), new Class<?>[]{Member.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hasPermission" -> administrator;
                    case "getRoles" -> List.of();
                    default -> null;
                });

        private Message message(String content) {
            return (Message) Proxy.newProxyInstance(Message.class.getClassLoader(), new Class<?>[]{Message.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getContentRaw" -> content;
                        case "getChannel", "getGuildChannel" -> channel;
                        case "getGuild" -> guild;
                        case "getAuthor" -> user;
                        case "getMember" -> member;
                        case "isFromGuild" -> true;
                        default -> null;
                    });
        }
    }
}
