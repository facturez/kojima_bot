package org.example.bot;

import gnu.trove.map.hash.TLongObjectHashMap;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.example.db.MessageRepository;
import org.example.db.StoredMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandHandlerTest {
    @TempDir
    Path tempDir;

    @Test
    void repliesToPingAndAcknowledgesTheInteractionOnce() {
        RecordingRepository repository = repository();
        InteractionFixture interaction = InteractionFixture.guild("ping");

        new SlashCommandHandler(repository).handle(interaction.event());

        assertEquals(List.of("Pong! Бот на связи."), interaction.responses);
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void routesHelpToTheExistingCommandReference() {
        InteractionFixture interaction = InteractionFixture.guild("help");

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertTrue(interaction.onlyResponse().contains("/ping"));
        assertTrue(interaction.onlyResponse().contains("/last"));
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void defersStatsBeforeReadingTheArchive() {
        RecordingRepository repository = repository();
        InteractionFixture interaction = InteractionFixture.guild("stats");

        new SlashCommandHandler(repository).handle(interaction.event());

        assertTrue(interaction.onlyResponse().contains("Статистика базы:"));
        assertTrue(interaction.onlyResponse().contains("Всего сообщений: 12"));
        assertTrue(interaction.onlyResponse().contains("Твоих сообщений: 3"));
        assertEquals("user-id", repository.countedAuthorId);
        interaction.assertAcknowledgedOnceWithDeferral();
    }

    @Test
    void rejectsLastInDirectMessages() {
        InteractionFixture interaction = InteractionFixture.directMessage("last");

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertTrue(interaction.onlyResponse().contains("только на сервере"));
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void lastUsesTheDefaultCountAndChannelScopedManageMessagesPermission() {
        RecordingRepository repository = repository();
        repository.recentMessages = List.of(
                new StoredMessage("reader", "archived text", Instant.parse("2026-07-30T10:15:30Z"))
        );
        InteractionFixture interaction = InteractionFixture.guild("last");
        interaction.manageMessages = true;

        new SlashCommandHandler(repository).handle(interaction.event());

        assertSame(interaction.channel, interaction.permissionChannel.get());
        assertEquals("channel-id", repository.recentChannelId);
        assertEquals(5, repository.recentLimit);
        assertTrue(interaction.onlyResponse().contains("archived text"));
        interaction.assertAcknowledgedOnceWithDeferral();
    }

    @Test
    void lastUsesTheExplicitCountOption() {
        RecordingRepository repository = repository();
        repository.recentMessages = List.of(
                new StoredMessage("reader", "one result", Instant.parse("2026-07-30T10:15:30Z"))
        );
        InteractionFixture interaction = InteractionFixture.guild("last", 7);
        interaction.manageMessages = true;

        new SlashCommandHandler(repository).handle(interaction.event());

        assertEquals(7, repository.recentLimit);
        interaction.assertAcknowledgedOnceWithDeferral();
    }

    @Test
    void channelOverrideCanDenyLastDespiteGuildWideManageMessagesPermission() {
        InteractionFixture interaction = InteractionFixture.guild("last");
        interaction.guildWidePermissions = true;
        interaction.manageMessages = false;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertSame(interaction.channel, interaction.permissionChannel.get());
        assertTrue(interaction.onlyResponse().contains("управления сообщениями"));
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void callEveryoneKeepsTheConfiguredAuthorizationAndRepeatCount() {
        InteractionFixture interaction = InteractionFixture.guild("зов");
        interaction.administrator = true;

        new SlashCommandHandler(repository()).handle(interaction.event());

        String expected = "@everyone " + AdminCommandConfig.CALL_MESSAGE_TEXT.trim();
        assertEquals(AdminCommandConfig.CALL_REPEAT_COUNT, interaction.responses.size());
        assertTrue(interaction.responses.stream().allMatch(expected::equals));
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void clearRequiresAdministratorPermission() {
        InteractionFixture interaction = InteractionFixture.guild("clear");

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertTrue(interaction.onlyResponse().contains("только администраторам"));
        assertEquals(0, interaction.retrievePastAmount);
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void clearUsesTheDefaultCountAndDefersBeforeHistoryRetrieval() {
        InteractionFixture interaction = InteractionFixture.guild("clear");
        interaction.administrator = true;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(10, interaction.retrievePastAmount);
        assertTrue(interaction.onlyResponse().contains("моложе 14 дней"));
        interaction.assertAcknowledgedOnceWithDeferral();
    }

    @Test
    void clearUsesExplicitCountAndSkipsMessagesOlderThanFourteenDays() {
        InteractionFixture interaction = InteractionFixture.guild("clear", 7);
        interaction.administrator = true;
        Message recentFirst = interaction.historyMessage(OffsetDateTime.now().minusDays(1));
        Message old = interaction.historyMessage(OffsetDateTime.now().minusDays(15));
        Message recentSecond = interaction.historyMessage(OffsetDateTime.now().minusDays(2));
        interaction.historyMessages = List.of(recentFirst, old, recentSecond);

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(7, interaction.retrievePastAmount);
        assertEquals(List.of(recentFirst, recentSecond), interaction.purgedMessages);
        assertTrue(interaction.onlyResponse().contains("Удалил 2 сообщений"));
        assertTrue(interaction.onlyResponse().contains("1 старых сообщений"));
        interaction.assertAcknowledgedOnceWithDeferral();
    }

    private RecordingRepository repository() {
        return new RecordingRepository(tempDir.resolve("archive-" + System.nanoTime() + ".db"));
    }

    private static final class RecordingRepository extends MessageRepository {
        private String countedAuthorId;
        private String recentChannelId;
        private int recentLimit;
        private List<StoredMessage> recentMessages = List.of();

        private RecordingRepository(Path databasePath) {
            super(databasePath.toString());
        }

        @Override
        public long countMessages() {
            return 12;
        }

        @Override
        public long countMessagesByAuthor(String authorId) {
            countedAuthorId = authorId;
            return 3;
        }

        @Override
        public List<StoredMessage> findRecentMessages(String channelId, int limit) {
            recentChannelId = channelId;
            recentLimit = limit;
            return recentMessages;
        }
    }

    private static final class InteractionFixture {
        private static final JDA JDA_PROXY = proxy(JDA.class, (ignored, method, arguments) -> defaultValue(method.getReturnType()));

        private final String name;
        private final Integer count;
        private final boolean fromGuild;
        private final AtomicInteger acknowledgements = new AtomicInteger();
        private final AtomicInteger deferrals = new AtomicInteger();
        private final AtomicReference<GuildChannel> permissionChannel = new AtomicReference<>();
        private final List<String> responses = new ArrayList<>();
        private final MessageChannelUnion channel;
        private final Guild guild;
        private final Member member;
        private final User user;

        private boolean administrator;
        private boolean guildWidePermissions;
        private boolean manageMessages;
        private int retrievePastAmount;
        private List<Message> historyMessages = List.of();
        private List<Message> purgedMessages = List.of();

        private InteractionFixture(String name, Integer count, boolean fromGuild) {
            this.name = name;
            this.count = count;
            this.fromGuild = fromGuild;
            this.user = proxy(User.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "getId", "getAsTag" -> "user-id";
                case "getIdLong" -> 1L;
                default -> defaultValue(method.getReturnType());
            });
            this.channel = createChannel();
            this.guild = fromGuild
                    ? proxy(Guild.class, (ignored, method, arguments) -> switch (method.getName()) {
                        case "getTextChannelById" -> channel;
                        case "getSelfMember" -> botMember();
                        case "getId" -> "guild-id";
                        case "getIdLong" -> 2L;
                        default -> defaultValue(method.getReturnType());
                    })
                    : null;
            this.member = fromGuild
                    ? proxy(Member.class, (ignored, method, arguments) -> switch (method.getName()) {
                        case "hasPermission" -> hasPermission(arguments);
                        case "getRoles" -> List.<Role>of();
                        case "getUser" -> user;
                        case "getId" -> "user-id";
                        case "getIdLong" -> 1L;
                        default -> defaultValue(method.getReturnType());
                    })
                    : null;
        }

        static InteractionFixture guild(String name) {
            return new InteractionFixture(name, null, true);
        }

        static InteractionFixture guild(String name, int count) {
            return new InteractionFixture(name, count, true);
        }

        static InteractionFixture directMessage(String name) {
            return new InteractionFixture(name, null, false);
        }

        SlashCommandInteractionEvent event() {
            ReplyCallbackAction callback = replyCallback();
            SlashCommandInteraction interaction = proxy(
                    SlashCommandInteraction.class,
                    (ignored, method, arguments) -> switch (method.getName()) {
                        case "getName" -> name;
                        case "getOptions" -> count == null ? List.of() : List.of(integerOption("count", count));
                        case "getGuild" -> guild;
                        case "getMember" -> member;
                        case "getUser" -> user;
                        case "getChannel" -> channel;
                        case "getChannelIdLong" -> 3L;
                        case "getIdLong" -> 4L;
                        case "isAcknowledged" -> acknowledgements.get() > 0;
                        case "deferReply" -> callback;
                        default -> defaultValue(method.getReturnType());
                    }
            );
            return new SlashCommandInteractionEvent(JDA_PROXY, 0, interaction);
        }

        Message historyMessage(OffsetDateTime timeCreated) {
            return proxy(Message.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "getTimeCreated" -> timeCreated;
                case "delete" -> queuedAction(AuditableRestAction.class, null);
                default -> defaultValue(method.getReturnType());
            });
        }

        String onlyResponse() {
            assertEquals(1, responses.size());
            return responses.get(0);
        }

        void assertAcknowledgedOnceWithoutDeferral() {
            assertEquals(1, acknowledgements.get());
            assertEquals(0, deferrals.get());
        }

        void assertAcknowledgedOnceWithDeferral() {
            assertEquals(1, acknowledgements.get());
            assertEquals(1, deferrals.get());
        }

        private MessageChannelUnion createChannel() {
            Class<?>[] interfaces = fromGuild
                    ? new Class<?>[]{MessageChannelUnion.class, GuildMessageChannelUnion.class, TextChannel.class}
                    : new Class<?>[]{MessageChannelUnion.class};
            return (MessageChannelUnion) Proxy.newProxyInstance(
                    MessageChannelUnion.class.getClassLoader(),
                    interfaces,
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getId" -> "channel-id";
                        case "getIdLong" -> 3L;
                        case "getGuild" -> guild;
                        case "asTextChannel" -> proxy;
                        case "getHistory" -> history((TextChannel) proxy);
                        case "purgeMessages" -> {
                            @SuppressWarnings("unchecked")
                            List<Message> messages = (List<Message>) arguments[0];
                            purgedMessages = List.copyOf(messages);
                            yield List.<CompletableFuture<Void>>of();
                        }
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        private MessageHistory history(TextChannel textChannel) {
            return new MessageHistory(textChannel) {
                @Override
                public RestAction<List<Message>> retrievePast(int amount) {
                    retrievePastAmount = amount;
                    return queuedAction(RestAction.class, historyMessages);
                }
            };
        }

        private Member botMember() {
            return proxy(Member.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "hasAccess", "hasPermission" -> true;
                default -> defaultValue(method.getReturnType());
            });
        }

        private boolean hasPermission(Object[] arguments) {
            if (arguments.length == 2 && arguments[0] instanceof GuildChannel guildChannel) {
                permissionChannel.set(guildChannel);
                Permission[] permissions = (Permission[]) arguments[1];
                return manageMessages && Arrays.asList(permissions).contains(Permission.MESSAGE_MANAGE);
            }

            Permission[] permissions = (Permission[]) arguments[0];
            if (Arrays.asList(permissions).contains(Permission.ADMINISTRATOR)) {
                return administrator;
            }
            return guildWidePermissions;
        }

        private ReplyCallbackAction replyCallback() {
            AtomicReference<String> replyContent = new AtomicReference<>();
            InteractionHook hook = interactionHook();

            return proxy(ReplyCallbackAction.class, (proxy, method, arguments) -> {
                if (method.getName().equals("setContent")) {
                    replyContent.set(arguments[0].toString());
                    return proxy;
                }
                if (method.getName().equals("queue") && method.getParameterCount() == 2) {
                    acknowledgements.incrementAndGet();
                    if (replyContent.get() == null) {
                        deferrals.incrementAndGet();
                    } else {
                        responses.add(replyContent.get());
                    }
                    invokeSuccess(arguments[0], hook);
                    return null;
                }
                return fluentOrDefault(proxy, method.getReturnType());
            });
        }

        private InteractionHook interactionHook() {
            return proxy(InteractionHook.class, (ignored, method, arguments) -> {
                if (method.getName().equals("editOriginal") && arguments.length == 1) {
                    return queuedAction(method.getReturnType(), arguments[0].toString());
                }
                if (method.getName().equals("sendMessage") && arguments.length == 1) {
                    return queuedAction(method.getReturnType(), arguments[0].toString());
                }
                return defaultValue(method.getReturnType());
            });
        }

        private <T> T queuedAction(Class<T> actionType, Object resultOrContent) {
            return proxy(actionType, (proxy, method, arguments) -> {
                if (method.getName().equals("queue")) {
                    if (resultOrContent instanceof String content) {
                        responses.add(content);
                    }
                    if (method.getParameterCount() == 2) {
                        invokeSuccess(arguments[0], resultOrContent instanceof String ? null : resultOrContent);
                    }
                    return null;
                }
                return fluentOrDefault(proxy, method.getReturnType());
            });
        }

        private static OptionMapping integerOption(String name, int value) {
            DataObject data = DataObject.empty()
                    .put("type", OptionType.INTEGER.getKey())
                    .put("name", name)
                    .put("value", value);
            return new OptionMapping(data, new TLongObjectHashMap<>(), null, null);
        }

        private static void invokeSuccess(Object callback, Object result) {
            if (callback != null) {
                @SuppressWarnings("unchecked")
                Consumer<Object> success = (Consumer<Object>) callback;
                success.accept(result);
            }
        }

        private static Object fluentOrDefault(Object proxy, Class<?> returnType) {
            return returnType.isInstance(proxy) ? proxy : defaultValue(returnType);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
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
    }
}
