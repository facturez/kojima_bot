package org.example.bot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.example.db.MessageRepository;
import org.example.db.StoredMessage;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SlashCommandHandler {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final MessageRepository repository;

    public SlashCommandHandler(MessageRepository repository) {
        this.repository = repository;
    }

    public void handle(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "help" -> reply(event, """
                    Доступные slash-команды:
                    /help - показать список команд
                    /ping - проверить, что бот отвечает
                    /stats - статистика по сохраненным сообщениям
                    /last [count] - последние сообщения из этого канала
                    /зов - тегает всех и призывает БАТВУ на Faceit
                    /clear [count] - админская очистка последних сообщений
                    """);
            case "ping" -> reply(event, "Pong! Бот на связи.");
            case "stats" -> sendStats(event);
            case "last" -> sendRecentMessages(event);
            case "зов" -> callEveryone(event);
            case "clear" -> clearMessages(event);
            default -> reply(event, "Неизвестная команда. Используй /help");
        }
    }

    private void sendStats(SlashCommandInteractionEvent event) {
        event.deferReply().queue(hook -> {
            try {
                long totalMessages = repository.countMessages();
                long currentUserMessages = repository.countMessagesByAuthor(event.getUser().getId());
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
                        repository.findRecentMessages(event.getChannel().getId(), limit);
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

                editOriginal(hook, builder.toString());
            } catch (RuntimeException failure) {
                System.err.println("Failed to read message archive: " + failure.getMessage());
                editOriginal(hook, "Не получилось прочитать архив сообщений.");
            }
        }, SlashCommandHandler::logReplyFailure);
    }

    private void callEveryone(SlashCommandInteractionEvent event) {
        if (!ensureCallPermission(event)) {
            return;
        }

        String callText = AdminCommandConfig.CALL_MESSAGE_TEXT;
        if (callText == null || callText.isBlank()) {
            reply(event, "Заполни текст команды /зов в AdminCommandConfig.java");
            return;
        }

        String payload = "@everyone " + callText.trim();
        event.reply(payload).queue(hook -> {
            for (int i = 1; i < AdminCommandConfig.CALL_REPEAT_COUNT; i++) {
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

        int requestedAmount = amountToDelete;
        event.deferReply().queue(
                hook -> retrieveAndDeleteMessages(textChannel, requestedAmount, hook),
                SlashCommandHandler::logReplyFailure
        );
    }

    private void retrieveAndDeleteMessages(TextChannel textChannel, int amountToDelete, InteractionHook hook) {
        OffsetDateTime twoWeeksAgo = OffsetDateTime.now().minusWeeks(2);
        textChannel.getHistory().retrievePast(amountToDelete).queue(messages -> {
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
        }, failure -> editOriginal(
                hook,
                "Не получилось очистить сообщения: " + failure.getMessage()
        ));
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
                AdminCommandConfig.CALL_ALLOWED_ROLE_IDS
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

    private static void logReplyFailure(Throwable failure) {
        System.err.println("Failed to reply to slash command: " + failure.getMessage());
    }
}
