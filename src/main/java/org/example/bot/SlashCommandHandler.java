package org.example.bot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.example.db.MessageRepository;
import org.example.db.StoredMessage;
import org.example.db.GuildConfigRepository;
import org.example.db.CallSettings;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class SlashCommandHandler {
    private static final String DEFAULT_AUDIT_REASON =
            "Действие выполнено администратором через slash-команду";
    private static final String MODERATION_FAILURE_MESSAGE =
            "Не получилось выполнить действие модерации. Попробуй позже.";
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final MessageRepository repository;
    private final GuildConfigRepository configs;
    private final SetupCommandHandler setupHandler;

    public SlashCommandHandler(MessageRepository repository) {
        this(repository, null, null);
    }

    public SlashCommandHandler(MessageRepository repository, GuildConfigRepository configs,
                               SetupCommandHandler setupHandler) {
        this.repository = repository;
        this.configs = configs;
        this.setupHandler = setupHandler;
    }

    public void handle(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "help" -> reply(event, """
                    Доступные slash-команды:
                    /help - показать список команд
                    /ping - проверить, что бот отвечает
                    /stats - статистика по сохраненным сообщениям
                    /last [n] - последние сообщения из этого канала
                    /зов - тегает всех и призывает БАТВУ на Faceit
                    /clear [n] - админская очистка последних сообщений
                    /deport [чел] [причина] - депортация из Кодзимы(бан)
                    /magadan [чел] [причина] - этап в магадан(кик)
                    /kpz [чел] [срок] [причина] - заключение в обезьянник(тайм-аут)

                    Обычные команды с ! по-прежнему поддерживаются.
                    """);
            case "ping" -> reply(event, "Pong! Бот у апппарата. Слушает батву");
            case "stats" -> sendStats(event);
            case "last" -> sendRecentMessages(event);
            case "зов" -> callEveryone(event);
            case "clear" -> clearMessages(event);
            case "setup", "config" -> {
                if (setupHandler == null) reply(event, "Конфигурация пока недоступна.");
                else setupHandler.handle(event);
            }
            case SlashCommandContract.DEPORT,
                 SlashCommandContract.MAGADAN,
                 SlashCommandContract.KPZ -> moderateMember(event);
            default -> reply(event, "Неизвестная команда. Используй /help");
        }
    }

    private void moderateMember(SlashCommandInteractionEvent event) {
        Permission required = switch (event.getName()) {
            case SlashCommandContract.DEPORT -> Permission.BAN_MEMBERS;
            case SlashCommandContract.MAGADAN -> Permission.KICK_MEMBERS;
            case SlashCommandContract.KPZ -> Permission.MODERATE_MEMBERS;
            default -> throw new IllegalArgumentException("Unknown moderation command");
        };

        OptionMapping userOption = event.getOption(SlashCommandContract.TARGET_OPTION);
        User targetUser = userOption == null ? null : userOption.getAsUser();
        Member target = userOption == null ? null : userOption.getAsMember();
        boolean fromGuild = event.isFromGuild();
        Guild guild = fromGuild ? event.getGuild() : null;
        Member caller = event.getMember();
        Member bot = guild == null ? null : guild.getSelfMember();
        String targetId = targetUser == null ? null : targetUser.getId();

        CommandAuthorization.ModerationDenial denial = CommandAuthorization.checkModeration(
                fromGuild,
                caller != null && caller.hasPermission(Permission.ADMINISTRATOR),
                targetId != null && targetId.equals(event.getUser().getId()),
                targetId != null && guild != null && targetId.equals(guild.getOwnerId()),
                targetId != null && bot != null && targetId.equals(bot.getId()),
                target == null || caller != null && caller.canInteract(target),
                target == null || bot != null && bot.canInteract(target),
                bot != null && bot.hasPermission(required)
        );
        if (denial != CommandAuthorization.ModerationDenial.NONE) {
            reply(event, moderationDenialMessage(denial));
            return;
        }

        if (target == null || targetUser == null) {
            reply(event, "Не удалось найти этого участника на сервере.");
            return;
        }

        Duration timeoutDuration = null;
        if (event.getName().equals(SlashCommandContract.KPZ)) {
            try {
                timeoutDuration = TimeoutDurationParser.parse(
                        event.getOption(SlashCommandContract.DURATION_OPTION, OptionMapping::getAsString)
                );
            } catch (IllegalArgumentException invalidDuration) {
                reply(event, invalidDuration.getMessage());
                return;
            }
        }

        String suppliedReason = event.getOption(
                SlashCommandContract.REASON_OPTION,
                "",
                OptionMapping::getAsString
        );
        String auditReason = suppliedReason == null || suppliedReason.isBlank()
                ? DEFAULT_AUDIT_REASON
                : suppliedReason.trim();
        AuditableRestAction<Void> action;
        String confirmation;
        switch (event.getName()) {
            case SlashCommandContract.DEPORT -> {
                action = guild.ban(targetUser, 0, TimeUnit.DAYS);
                confirmation = "Забанил " + target.getAsMention() + ".";
            }
            case SlashCommandContract.MAGADAN -> {
                action = target.kick();
                confirmation = "Исключил " + target.getAsMention() + " с сервера.";
            }
            case SlashCommandContract.KPZ -> {
                action = target.timeoutFor(timeoutDuration);
                confirmation = "Выдал тайм-аут " + target.getAsMention() + ".";
            }
            default -> throw new IllegalArgumentException("Unknown moderation command");
        }

        event.deferReply().queue(
                hook -> action.reason(auditReason).queue(
                        ignored -> editOriginal(hook, confirmation),
                        failure -> {
                            System.err.println("Failed to execute moderation action: " + failure.getMessage());
                            editOriginal(hook, MODERATION_FAILURE_MESSAGE);
                        }
                ),
                SlashCommandHandler::logReplyFailure
        );
    }

    private String moderationDenialMessage(CommandAuthorization.ModerationDenial denial) {
        return switch (denial) {
            case GUILD_ONLY -> "Эта команда работает только на сервере.";
            case ADMINISTRATOR_REQUIRED -> "Эта команда доступна только администраторам.";
            case SELF_TARGET -> "Нельзя применить модерацию к самому себе.";
            case OWNER_TARGET -> "Нельзя применить модерацию к владельцу сервера.";
            case BOT_SELF_TARGET -> "Нельзя применить модерацию к боту.";
            case CALLER_HIERARCHY -> "Твоя роль недостаточно высока для модерации этого участника.";
            case BOT_HIERARCHY -> "Роль бота недостаточно высока для модерации этого участника.";
            case BOT_PERMISSION -> "У бота нет необходимого права для этой команды.";
            case NONE -> throw new IllegalArgumentException("No denial message for authorized moderation");
        };
    }

    private void sendStats(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            reply(event, "Команда /stats работает только на сервере.");
            return;
        }
        event.deferReply().queue(hook -> {
            try {
                String guildId = event.getGuild().getId();
                long totalMessages = repository.countMessages(guildId);
                long currentUserMessages = repository.countMessagesByAuthor(guildId, event.getUser().getId());
                editOriginal(hook, """
                        Статистика базы:
                        Всего сообщений: %d
                        Твоих сообщений: %d
                        """.formatted(totalMessages, currentUserMessages));
            } catch (RuntimeException failure) {
                System.err.println("Failed to read message archive: " + failure.getMessage());
                editOriginal(hook, "Не получилось прочитать архив сообщений.");
            }
        }, SlashCommandHandler::logReplyFailure);
    }

    private void sendRecentMessages(SlashCommandInteractionEvent event) {
        if (!ensureArchiveReadPermission(event)) {
            return;
        }

        int limit = event.getOption("count", 5, OptionMapping::getAsInt);
        if (limit < 1 || limit > 20) {
            reply(event, "Параметр count команды /last должен быть от 1 до 20.");
            return;
        }

        event.deferReply().queue(hook -> {
            try {
                List<StoredMessage> recentMessages =
                        repository.findRecentMessages(event.getGuild().getId(), event.getChannel().getId(), limit);
                if (recentMessages.isEmpty()) {
                    editOriginal(hook, "В базе пока нет сообщений для этого канала.");
                    return;
                }

                StringBuilder builder = new StringBuilder("Последние сообщения:\n");
                for (StoredMessage storedMessage : recentMessages) {
                    String preview = storedMessage.content().isBlank()
                            ? "[пустое сообщение]"
                            : storedMessage.content();
                    if (preview.length() > 90) {
                        preview = preview.substring(0, 87) + "...";
                    }

                    String line = new StringBuilder()
                            .append("- ")
                            .append(TIME_FORMATTER.format(storedMessage.createdAt()))
                            .append(" | ")
                            .append(storedMessage.authorTag())
                            .append(": ")
                            .append(preview)
                            .append('\n')
                            .toString();

                    if (builder.length() + line.length() > 1800) {
                        builder.append("... список обрезан, чтобы влезть в сообщение Discord.\n");
                        break;
                    }

                    builder.append(line);
                }

                editArchiveOriginal(hook, builder.toString());
            } catch (RuntimeException failure) {
                System.err.println("Failed to read message archive: " + failure.getMessage());
                editOriginal(hook, "Не получилось прочитать архив сообщений.");
            }
        }, SlashCommandHandler::logReplyFailure);
    }

    private void callEveryone(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            reply(event, "Эта команда работает только на сервере.");
            return;
        }
        if (!ensureCallPermission(event)) {
            return;
        }

        if (configs == null) {
            reply(event, "Конфигурация сервера недоступна.");
            return;
        }
        CallSettings settings = configs.requireConfig(event.getGuild().getId()).call();
        if (!settings.enabled()) {
            reply(event, "Команда /зов отключена на этом сервере.");
            return;
        }
        String callText = settings.messageText();
        if (callText == null || callText.isBlank()) {
            reply(event, "Настрой текст команды через /setup call.");
            return;
        }

        String payload = "@everyone " + callText.trim();
        event.reply(payload).queue(hook -> {
            int repeatCount = settings.repeatCount();
            for (int i = 1; i < repeatCount; i++) {
                hook.sendMessage(payload).queue(
                        null,
                        failure -> System.err.println(
                                "Failed to send slash command follow-up: " + failure.getMessage()
                        )
                );
            }
        }, SlashCommandHandler::logReplyFailure);
    }

    private void clearMessages(SlashCommandInteractionEvent event) {
        if (!ensureAdmin(event)) {
            return;
        }

        if (!event.isFromGuild()) {
            reply(event, "Эта команда работает только в серверных текстовых каналах.");
            return;
        }

        int amountToDelete = event.getOption("count", 10, OptionMapping::getAsInt);
        if (amountToDelete < 1 || amountToDelete > 100) {
            reply(event, "Можно удалить только от 1 до 100 сообщений за раз.");
            return;
        }

        TextChannel textChannel = event.getGuild().getTextChannelById(event.getChannel().getId());
        if (textChannel == null) {
            reply(event, "Эта команда сейчас поддерживается только в обычных текстовых каналах сервера.");
            return;
        }

        Member bot = event.getGuild().getSelfMember();
        if (!bot.hasPermission(
                textChannel,
                Permission.MESSAGE_HISTORY,
                Permission.MESSAGE_MANAGE
        )) {
            reply(
                    event,
                    "У бота нет прав для чтения истории и управления сообщениями в этом канале."
            );
            return;
        }

        int requestedAmount = amountToDelete;
        event.deferReply(true).queue(
                hook -> retrieveAndDeleteMessages(textChannel, requestedAmount, hook),
                SlashCommandHandler::logReplyFailure
        );
    }

    private void retrieveAndDeleteMessages(TextChannel textChannel, int amountToDelete, InteractionHook hook) {
        try {
            textChannel.getHistory().retrievePast(amountToDelete).queue(
                    messages -> deleteRetrievedMessages(textChannel, messages, hook),
                    failure -> reportDeletionFailure(hook, failure)
            );
        } catch (RuntimeException failure) {
            reportDeletionFailure(hook, failure);
        }
    }

    private void deleteRetrievedMessages(
            TextChannel textChannel,
            List<Message> messages,
            InteractionHook hook
    ) {
        try {
            OffsetDateTime twoWeeksAgo = OffsetDateTime.now().minusWeeks(2);
            List<Message> recentMessages = new ArrayList<>();
            int skippedOldMessages = 0;

            for (Message historyMessage : messages) {
                if (historyMessage.getTimeCreated().isBefore(twoWeeksAgo)) {
                    skippedOldMessages++;
                } else {
                    recentMessages.add(historyMessage);
                }
            }

            if (recentMessages.isEmpty()) {
                editOriginal(hook, "Не нашлось сообщений моложе 14 дней для удаления.");
                return;
            }

            int skipped = skippedOldMessages;
            if (recentMessages.size() == 1) {
                recentMessages.get(0).delete().queue(
                        ignored -> reportDeletionSuccess(hook, recentMessages.size(), skipped),
                        failure -> reportDeletionFailure(hook, failure)
                );
                return;
            }

            List<CompletableFuture<Void>> purgeOperations = textChannel.purgeMessages(recentMessages);
            CompletableFuture.allOf(purgeOperations.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            reportDeletionSuccess(hook, recentMessages.size(), skipped);
                        } else {
                            reportDeletionFailure(hook, failure);
                        }
                    });
        } catch (RuntimeException failure) {
            reportDeletionFailure(hook, failure);
        }
    }

    private void reportDeletionSuccess(InteractionHook hook, int deletedMessages, int skippedOldMessages) {
        if (skippedOldMessages == 0) {
            editOriginal(hook, "Удалил " + deletedMessages + " сообщений.");
            return;
        }

        editOriginal(
                hook,
                "Удалил " + deletedMessages + " сообщений. "
                        + skippedOldMessages
                        + " старых сообщений старше 14 дней Discord не дал удалить пачкой."
        );
    }

    private void reportDeletionFailure(InteractionHook hook, Throwable failure) {
        System.err.println("Failed to purge Discord messages: " + failure.getMessage());
        editOriginal(hook, "Не получилось очистить сообщения.");
    }

    private boolean ensureAdmin(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null || !member.hasPermission(Permission.ADMINISTRATOR)) {
            reply(event, "Эта команда доступна только администраторам.");
            return false;
        }
        return true;
    }

    private boolean ensureCallPermission(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null) {
            reply(event, "Эта команда работает только на сервере.");
            return false;
        }

        List<String> memberRoleIds = member.getRoles().stream()
                .map(Role::getId)
                .toList();
        if (CommandAuthorization.canCallEveryone(
                member.hasPermission(Permission.ADMINISTRATOR),
                memberRoleIds,
                configs == null ? List.of() : configs.requireConfig(event.getGuild().getId()).call().allowedRoleIds()
        )) {
            return true;
        }

        reply(event, "Эта команда доступна только администраторам и настроенным ролям.");
        return false;
    }

    private boolean ensureArchiveReadPermission(SlashCommandInteractionEvent event) {
        boolean fromGuild = event.isFromGuild();
        Member member = event.getMember();
        boolean canReadArchive = CommandAuthorization.canReadArchive(
                fromGuild,
                fromGuild
                        && member != null
                        && member.hasPermission(event.getGuildChannel(), Permission.MESSAGE_MANAGE)
        );
        if (canReadArchive) {
            return true;
        }

        reply(
                event,
                "Команда /last доступна только на сервере участникам с правом управления сообщениями."
        );
        return false;
    }

    private static void reply(SlashCommandInteractionEvent event, String content) {
        event.reply(content).queue(null, SlashCommandHandler::logReplyFailure);
    }

    private static void editOriginal(InteractionHook hook, String content) {
        hook.editOriginal(content).queue(
                null,
                failure -> System.err.println(
                        "Failed to finish deferred slash command: " + failure.getMessage()
                )
        );
    }

    private static void editArchiveOriginal(InteractionHook hook, String content) {
        hook.editOriginal(content)
                .setAllowedMentions(List.of())
                .queue(
                        null,
                        failure -> System.err.println(
                                "Failed to finish deferred slash command: " + failure.getMessage()
                        )
                );
    }

    private static void logReplyFailure(Throwable failure) {
        System.err.println("Failed to reply to slash command: " + failure.getMessage());
    }
}
