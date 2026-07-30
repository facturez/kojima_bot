package org.example.bot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.example.db.MessageRepository;
import org.example.db.StoredMessage;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CommandHandler {
    private static final String PREFIX = "!";
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final MessageRepository repository;

    public CommandHandler(MessageRepository repository) {
        this.repository = repository;
    }

    public boolean handle(Message message) {
        String raw = message.getContentRaw();
        if (!raw.startsWith(PREFIX)) {
            return false;
        }

        String[] parts = raw.substring(PREFIX.length()).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return false;
        }

        String command = parts[0].toLowerCase();
        MessageChannel channel = message.getChannel();

        switch (command) {
            case "help" -> sendMessage(channel, """
                    Доступные команды:
                    !help - показать список команд
                    !ping - проверить, что бот отвечает
                    !stats - статистика по сохраненным сообщениям
                    !last [n] - последние n сообщений из этого канала
                    !зов - тегает всех и пишет призывает БАТВУ на Faceit
                    !clear [n] / !очистить [n] - админская очистка последних сообщений

                    Slash-команды (обычные !-команды по-прежнему работают):
                    /help - показать список команд
                    /ping - проверить, что бот отвечает
                    /stats - статистика по сохраненным сообщениям
                    /last [n] - последние сообщения из этого канала
                    /зов - тегает всех и призывает БАТВУ на Faceit
                    /clear [n] - админская очистка последних сообщений
                    /deport [чел] [причина] - депортация из Кодзимы(бан)
                    /magadan [чел] [причина] - этап в магадан(кик)
                    /kpz [чел] [срок] [причина] - заключение в обезьянник(тайм-аут)
                    """);
            case "ping" -> sendMessage(channel, "Pong! Бот на связи.");
            case "stats" -> sendStats(message);
            case "last" -> sendRecentMessages(message, parts);
            case "зов" -> callEveryone(message);
            case "clear", "очистить" -> clearMessages(message, parts);
            default -> sendMessage(channel, "Неизвестная команда. Используй !help");
        }

        return true;
    }

    private void sendStats(Message message) {
        try {
            long totalMessages = repository.countMessages();
            long currentUserMessages = repository.countMessagesByAuthor(message.getAuthor().getId());

            String response = """
                    Статистика базы:
                    Всего сообщений: %d
                    Твоих сообщений: %d
                    """.formatted(totalMessages, currentUserMessages);

            sendMessage(message.getChannel(), response);
        } catch (RuntimeException failure) {
            System.err.println("Failed to read message archive: " + failure.getMessage());
            sendMessage(message.getChannel(), "Не получилось прочитать архив сообщений.");
        }
    }

    private void sendRecentMessages(Message message, String[] parts) {
        if (!ensureArchiveReadPermission(message)) {
            return;
        }

        int limit = 5;
        if (parts.length > 1) {
            try {
                limit = Integer.parseInt(parts[1]);
                if (limit < 1 || limit > 20) {
                    sendMessage(message.getChannel(), "Число после !last должно быть от 1 до 20.");
                    return;
                }
            } catch (NumberFormatException ignored) {
                sendMessage(message.getChannel(), "Число после !last должно быть от 1 до 20.");
                return;
            }
        }

        try {
            List<StoredMessage> recentMessages = repository.findRecentMessages(message.getChannel().getId(), limit);
            if (recentMessages.isEmpty()) {
                sendMessage(message.getChannel(), "В базе пока нет сообщений для этого канала.");
                return;
            }

            StringBuilder builder = new StringBuilder("Последние сообщения:\n");
            for (StoredMessage storedMessage : recentMessages) {
                String preview = storedMessage.content().isBlank() ? "[пустое сообщение]" : storedMessage.content();
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

            sendArchiveMessage(message.getChannel(), builder.toString());
        } catch (RuntimeException failure) {
            System.err.println("Failed to read message archive: " + failure.getMessage());
            sendMessage(message.getChannel(), "Не получилось прочитать архив сообщений.");
        }
    }

    private void callEveryone(Message message) {
        if (!ensureCallPermission(message)) {
            return;
        }

        String callText = AdminCommandConfig.CALL_MESSAGE_TEXT;
        if (callText == null || callText.isBlank()) {
            sendMessage(message.getChannel(), "Заполни текст команды !зов в AdminCommandConfig.java");
            return;
        }

        String payload = "@everyone " + callText.trim();
        for (int i = 0; i < AdminCommandConfig.CALL_REPEAT_COUNT; i++) {
            sendMessage(message.getChannel(), payload);
        }
    }

    private void clearMessages(Message message, String[] parts) {
        if (!ensureAdmin(message)) {
            return;
        }

        if (!message.isFromGuild()) {
            sendMessage(message.getChannel(), "Эта команда работает только в серверных текстовых каналах.");
            return;
        }

        int amountToDelete = 10;
        if (parts.length > 1) {
            try {
                amountToDelete = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                sendMessage(message.getChannel(), "Используй !clear <число от 1 до 100>.");
                return;
            }
        }

        if (amountToDelete < 1 || amountToDelete > 100) {
            sendMessage(message.getChannel(), "Можно удалить только от 1 до 100 сообщений за раз.");
            return;
        }

        TextChannel textChannel = message.getGuild().getTextChannelById(message.getChannel().getId());
        if (textChannel == null) {
            sendMessage(
                    message.getChannel(),
                    "Эта команда сейчас поддерживается только в обычных текстовых каналах сервера."
            );
            return;
        }

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
                sendMessage(message.getChannel(), "Не нашлось сообщений моложе 14 дней для удаления.");
                return;
            }

            if (recentMessages.size() == 1) {
                recentMessages.get(0).delete().queue(
                        null,
                        failure -> System.err.println(
                                "Failed to delete message during clear: " + failure.getMessage()
                        )
                );
            } else {
                observePurgeFailures(textChannel.purgeMessages(recentMessages));
            }
            message.delete().queue(null, failure -> System.err.println(
                    "Failed to delete clear command message: " + failure.getMessage()
            ));

            if (skippedOldMessages > 0) {
                sendMessage(
                        textChannel,
                        "Удалил " + recentMessages.size() + " сообщений. "
                                + skippedOldMessages
                                + " старых сообщений старше 14 дней Discord не дал удалить пачкой."
                );
            }
        }, failure -> sendMessage(
                message.getChannel(),
                "Не получилось очистить сообщения: " + failure.getMessage()
        ));
    }

    private boolean ensureAdmin(Message message) {
        Member member = message.getMember();
        if (member == null || !member.hasPermission(Permission.ADMINISTRATOR)) {
            sendMessage(message.getChannel(), "Эта команда доступна только администраторам.");
            return false;
        }

        return true;
    }

    private boolean ensureCallPermission(Message message) {
        Member member = message.getMember();
        if (member == null) {
            sendMessage(message.getChannel(), "Эта команда работает только на сервере.");
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

        sendMessage(message.getChannel(), "Эта команда доступна только администраторам и настроенным ролям.");
        return false;
    }

    private boolean ensureArchiveReadPermission(Message message) {
        if (!message.isFromGuild()) {
            sendMessage(
                    message.getChannel(),
                    "Команда !last доступна только на сервере участникам с правом управления сообщениями."
            );
            return false;
        }

        Member member = message.getMember();
        boolean canReadArchive = CommandAuthorization.canReadArchive(
                true,
                member != null && member.hasPermission(message.getGuildChannel(), Permission.MESSAGE_MANAGE)
        );
        if (canReadArchive) {
            return true;
        }

        sendMessage(
                message.getChannel(),
                "Команда !last доступна только на сервере участникам с правом управления сообщениями."
        );
        return false;
    }

    static void sendMessage(MessageChannel channel, String content) {
        channel.sendMessage(content).queue(
                null,
                failure -> System.err.println("Failed to send Discord message: " + failure.getMessage())
        );
    }

    private static void sendArchiveMessage(MessageChannel channel, String content) {
        channel.sendMessage(content)
                .setAllowedMentions(List.of())
                .queue(
                        null,
                        failure -> System.err.println(
                                "Failed to send Discord message: " + failure.getMessage()
                        )
                );
    }

    static void observePurgeFailures(List<CompletableFuture<Void>> purgeOperations) {
        for (CompletableFuture<Void> purgeOperation : purgeOperations) {
            purgeOperation.exceptionally(failure -> {
                System.err.println("Failed to purge Discord messages: " + failure.getMessage());
                return null;
            });
        }
    }
}
