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
import net.dv8tion.jda.api.entities.UserSnowflake;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandHandlerTest {
    private enum ModerationAction {
        BAN,
        KICK,
        TIMEOUT
    }

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

    @Test
    void moderationRejectsDirectMessagesWithoutQueueingAnAction() {
        InteractionFixture interaction = InteractionFixture.directMessageModeration("ban");

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals("Эта команда работает только на сервере.", interaction.onlyResponse());
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void moderationRequiresAdministratorWithoutQueueingAnAction() {
        InteractionFixture interaction = InteractionFixture.moderation("ban");
        interaction.administrator = false;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals("Эта команда доступна только администраторам.", interaction.onlyResponse());
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void moderationRejectsTheCallerAsTargetWithoutQueueingAnAction() {
        InteractionFixture interaction = InteractionFixture.moderation("ban");
        interaction.targetId = "user-id";
        interaction.targetIdLong = 1L;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals("Нельзя применить модерацию к самому себе.", interaction.onlyResponse());
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void moderationRejectsTheGuildOwnerWithoutQueueingAnAction() {
        InteractionFixture interaction = InteractionFixture.moderation("ban");
        interaction.targetId = "owner-id";
        interaction.targetIdLong = 5L;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals("Нельзя применить модерацию к владельцу сервера.", interaction.onlyResponse());
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void moderationRejectsTheBotAsTargetWithoutQueueingAnAction() {
        InteractionFixture interaction = InteractionFixture.moderation("ban");
        interaction.targetId = "bot-id";
        interaction.targetIdLong = 6L;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals("Нельзя применить модерацию к боту.", interaction.onlyResponse());
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void moderationRequiresCallerHierarchyWithoutQueueingAnAction() {
        InteractionFixture interaction = InteractionFixture.moderation("kick");
        interaction.callerCanInteract = false;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(
                "Твоя роль недостаточно высока для модерации этого участника.",
                interaction.onlyResponse()
        );
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void moderationRequiresBotHierarchyWithoutQueueingAnAction() {
        InteractionFixture interaction = InteractionFixture.moderation("kick");
        interaction.botCanInteract = false;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(
                "Роль бота недостаточно высока для модерации этого участника.",
                interaction.onlyResponse()
        );
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void moderationRequiresTheCommandSpecificBotPermissionWithoutQueueingAnAction() {
        InteractionFixture interaction = InteractionFixture.moderation("timeout");
        interaction.botHasModerationPermission = false;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals("У бота нет необходимого права для этой команды.", interaction.onlyResponse());
        assertEquals(Permission.MODERATE_MEMBERS, interaction.checkedBotPermission.get());
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void moderationReportsAUserOptionWithoutAResolvedGuildMemberSafely() {
        InteractionFixture interaction = InteractionFixture.moderation("kick");
        interaction.targetMemberResolved = false;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals("Не удалось найти этого участника на сервере.", interaction.onlyResponse());
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void banUsesTheMentionedUserRequiredPermissionAndTrimmedAuditReason() {
        InteractionFixture interaction = InteractionFixture.moderation("ban");
        interaction.reason = "  спам  ";

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(ModerationAction.BAN, interaction.moderationAction);
        assertSame(interaction.targetUser, interaction.moderationTarget);
        assertEquals(0, interaction.banDeleteAmount);
        assertEquals(TimeUnit.DAYS, interaction.banDeleteUnit);
        assertEquals(Permission.BAN_MEMBERS, interaction.checkedBotPermission.get());
        assertEquals("спам", interaction.recordedAuditReason.get());
        assertTrue(interaction.onlyResponse().contains(interaction.targetMember.getAsMention()));
        interaction.assertModerationActionQueuedOnce();
        interaction.assertAcknowledgedOnceWithDeferral();
    }

    @Test
    void kickUsesTheMentionedMemberAndDefaultAuditReason() {
        InteractionFixture interaction = InteractionFixture.moderation("kick");
        interaction.reason = "   ";

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(ModerationAction.KICK, interaction.moderationAction);
        assertSame(interaction.targetMember, interaction.moderationTarget);
        assertEquals(Permission.KICK_MEMBERS, interaction.checkedBotPermission.get());
        assertEquals(
                "Действие выполнено администратором через slash-команду",
                interaction.recordedAuditReason.get()
        );
        assertTrue(interaction.onlyResponse().contains(interaction.targetMember.getAsMention()));
        interaction.assertModerationActionQueuedOnce();
        interaction.assertAcknowledgedOnceWithDeferral();
    }

    @Test
    void timeoutUsesTheMentionedMemberAndParsedDuration() {
        InteractionFixture interaction = InteractionFixture.moderation("timeout");
        interaction.duration = " 2H ";
        interaction.reason = "спам";

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(ModerationAction.TIMEOUT, interaction.moderationAction);
        assertSame(interaction.targetMember, interaction.moderationTarget);
        assertEquals(Duration.ofHours(2), interaction.recordedTimeoutDuration.get());
        assertEquals(Permission.MODERATE_MEMBERS, interaction.checkedBotPermission.get());
        assertEquals("спам", interaction.recordedAuditReason.get());
        assertTrue(interaction.onlyResponse().contains(interaction.targetMember.getAsMention()));
        interaction.assertModerationActionQueuedOnce();
        interaction.assertAcknowledgedOnceWithDeferral();
    }

    @Test
    void timeoutReturnsTheParserErrorWithoutQueueingAnAction() {
        InteractionFixture interaction = InteractionFixture.moderation("timeout");
        interaction.duration = "forever";

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(
                "Длительность должна быть от 1m до 28d. Доступные единицы: m, h, d.",
                interaction.onlyResponse()
        );
        interaction.assertNoModerationAction();
        interaction.assertAcknowledgedOnceWithoutDeferral();
    }

    @Test
    void moderationDefersImmediatelyAndEditsTheReplyOnlyAfterSuccess() {
        InteractionFixture interaction = InteractionFixture.moderation("ban");
        interaction.completeModerationAutomatically = false;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(List.of(), interaction.responses);
        interaction.assertModerationActionQueuedOnce();
        interaction.assertAcknowledgedOnceWithDeferral();

        interaction.completeModerationSuccessfully();

        assertTrue(interaction.onlyResponse().contains(interaction.targetMember.getAsMention()));
        interaction.assertModerationActionQueuedOnce();
        interaction.assertAcknowledgedOnceWithDeferral();
    }

    @Test
    void moderationDefersImmediatelyAndEditsASanitizedFailureReply() {
        InteractionFixture interaction = InteractionFixture.moderation("timeout");
        interaction.completeModerationAutomatically = false;

        new SlashCommandHandler(repository()).handle(interaction.event());

        assertEquals(List.of(), interaction.responses);
        interaction.assertModerationActionQueuedOnce();
        interaction.assertAcknowledgedOnceWithDeferral();

        interaction.failModeration(new IllegalStateException("Discord unavailable"));

        String response = interaction.onlyResponse();
        assertEquals("Не получилось выполнить действие модерации. Попробуй позже.", response);
        assertFalse(response.contains("Discord unavailable"));
        interaction.assertModerationActionQueuedOnce();
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
        private final AtomicReference<Permission> checkedBotPermission = new AtomicReference<>();
        private final AtomicReference<String> recordedAuditReason = new AtomicReference<>();
        private final AtomicReference<Duration> recordedTimeoutDuration = new AtomicReference<>();
        private final List<String> responses = new ArrayList<>();
        private final MessageChannelUnion channel;
        private final Guild guild;
        private final Member member;
        private final User user;
        private final User targetUser;
        private final Member targetMember;

        private boolean administrator;
        private boolean guildWidePermissions;
        private boolean manageMessages;
        private boolean callerCanInteract = true;
        private boolean botCanInteract = true;
        private boolean botHasModerationPermission = true;
        private boolean targetMemberResolved = true;
        private boolean completeModerationAutomatically = true;
        private String targetId = "target-id";
        private long targetIdLong = 99L;
        private String reason;
        private String duration = "2h";
        private int retrievePastAmount;
        private int moderationQueueCount;
        private int acknowledgementsWhenModerationQueued;
        private int banDeleteAmount;
        private TimeUnit banDeleteUnit;
        private ModerationAction moderationAction;
        private UserSnowflake moderationTarget;
        private Consumer<Void> moderationSuccess;
        private Consumer<Throwable> moderationFailure;
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
            this.targetUser = proxy(User.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "getId" -> targetId;
                case "getIdLong" -> targetIdLong;
                case "getAsMention" -> "<@" + targetIdLong + ">";
                default -> defaultValue(method.getReturnType());
            });
            this.channel = createChannel();
            this.guild = fromGuild
                    ? proxy(Guild.class, (ignored, method, arguments) -> switch (method.getName()) {
                        case "getTextChannelById" -> channel;
                        case "getSelfMember" -> botMember();
                        case "getOwnerId" -> "owner-id";
                        case "ban" -> {
                            banDeleteAmount = (int) arguments[1];
                            banDeleteUnit = (TimeUnit) arguments[2];
                            yield moderationAction(ModerationAction.BAN, (UserSnowflake) arguments[0]);
                        }
                        case "getId" -> "guild-id";
                        case "getIdLong" -> 2L;
                        default -> defaultValue(method.getReturnType());
                    })
                    : null;
            this.targetMember = proxy(Member.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "getUser" -> targetUser;
                case "getId" -> targetId;
                case "getIdLong" -> targetIdLong;
                case "getAsMention" -> "<@" + targetIdLong + ">";
                case "getGuild" -> guild;
                case "kick" -> moderationAction(ModerationAction.KICK, (Member) ignored);
                case "timeoutFor" -> {
                    recordedTimeoutDuration.set((Duration) arguments[0]);
                    yield moderationAction(ModerationAction.TIMEOUT, (Member) ignored);
                }
                default -> defaultValue(method.getReturnType());
            });
            this.member = fromGuild
                    ? proxy(Member.class, (ignored, method, arguments) -> switch (method.getName()) {
                        case "hasPermission" -> hasPermission(arguments);
                        case "canInteract" -> callerCanInteract;
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

        static InteractionFixture moderation(String name) {
            InteractionFixture interaction = new InteractionFixture(name, null, true);
            interaction.administrator = true;
            return interaction;
        }

        static InteractionFixture directMessageModeration(String name) {
            InteractionFixture interaction = new InteractionFixture(name, null, false);
            interaction.administrator = true;
            return interaction;
        }

        SlashCommandInteractionEvent event() {
            ReplyCallbackAction callback = replyCallback();
            SlashCommandInteraction interaction = proxy(
                    SlashCommandInteraction.class,
                    (ignored, method, arguments) -> switch (method.getName()) {
                        case "getName" -> name;
                        case "getOptions" -> options();
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

        private List<OptionMapping> options() {
            List<OptionMapping> options = new ArrayList<>();
            if (count != null) {
                options.add(integerOption("count", count));
            }
            if (isModerationCommand()) {
                options.add(userOption());
                if (name.equals("timeout")) {
                    options.add(stringOption("duration", duration));
                }
                if (reason != null) {
                    options.add(stringOption("reason", reason));
                }
            }
            return options;
        }

        private boolean isModerationCommand() {
            return name.equals("ban") || name.equals("kick") || name.equals("timeout");
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

        void assertNoModerationAction() {
            assertEquals(0, moderationQueueCount);
            assertEquals(null, moderationAction);
        }

        void assertModerationActionQueuedOnce() {
            assertEquals(1, moderationQueueCount);
            assertEquals(1, acknowledgementsWhenModerationQueued);
        }

        void completeModerationSuccessfully() {
            assertNotNull(moderationSuccess);
            moderationSuccess.accept(null);
        }

        void failModeration(Throwable failure) {
            assertNotNull(moderationFailure);
            moderationFailure.accept(failure);
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
                case "hasAccess" -> true;
                case "hasPermission" -> {
                    if (arguments.length == 2 && arguments[0] instanceof GuildChannel) {
                        yield true;
                    }
                    Permission[] permissions = (Permission[]) arguments[0];
                    checkedBotPermission.set(permissions[0]);
                    yield botHasModerationPermission;
                }
                case "canInteract" -> botCanInteract;
                case "getId" -> "bot-id";
                case "getIdLong" -> 6L;
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

        private AuditableRestAction<Void> moderationAction(
                ModerationAction action,
                UserSnowflake target
        ) {
            moderationAction = action;
            moderationTarget = target;
            return proxy(AuditableRestAction.class, (proxy, method, arguments) -> {
                if (method.getName().equals("reason")) {
                    recordedAuditReason.set(arguments[0].toString());
                    return proxy;
                }
                if (method.getName().equals("queue") && method.getParameterCount() == 2) {
                    acknowledgementsWhenModerationQueued = acknowledgements.get();
                    moderationQueueCount++;
                    @SuppressWarnings("unchecked")
                    Consumer<Void> success = (Consumer<Void>) arguments[0];
                    @SuppressWarnings("unchecked")
                    Consumer<Throwable> failure = (Consumer<Throwable>) arguments[1];
                    moderationSuccess = success;
                    moderationFailure = failure;
                    if (completeModerationAutomatically) {
                        success.accept(null);
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

        private OptionMapping userOption() {
            DataObject data = DataObject.empty()
                    .put("type", OptionType.USER.getKey())
                    .put("name", "user")
                    .put("value", targetIdLong);
            TLongObjectHashMap<Object> resolved = new TLongObjectHashMap<>();
            resolved.put(targetIdLong, targetMemberResolved ? targetMember : targetUser);
            return new OptionMapping(data, resolved, null, null);
        }

        private static OptionMapping stringOption(String name, String value) {
            DataObject data = DataObject.empty()
                    .put("type", OptionType.STRING.getKey())
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
