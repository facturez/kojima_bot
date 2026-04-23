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
            case "help" -> channel.sendMessage("""
                    Доступные команды:
                    !help - показать список команд
                    !ping - проверить, что бот отвечает
                    !stats - статистика по сохраненным сообщениям
                    !last [n] - последние n сообщений из этого канала
                    !зов - команда для админов и роли "я ухожу", тегает всех и пишет текст 3 раза
                    !clear [n] / !очистить [n] - админская очистка последних сообщений
                    """).queue();
            case "ping" -> channel.sendMessage("Pong! Бот на связи.").queue();
            case "stats" -> sendStats(message);
            case "last" -> sendRecentMessages(message, parts);
            case "зов" -> callEveryone(message);
            case "clear", "очистить" -> clearMessages(message, parts);
            default -> channel.sendMessage("Неизвестная команда. Используй !help").queue();
        }

        return true;
    }

    private void sendStats(Message message) {
        long totalMessages = repository.countMessages();
        long currentUserMessages = repository.countMessagesByAuthor(message.getAuthor().getId());

        String response = """
                Статистика базы:
                Всего сообщений: %d
                Твоих сообщений: %d
                """.formatted(totalMessages, currentUserMessages);

        message.getChannel().sendMessage(response).queue();
    }

    private void sendRecentMessages(Message message, String[] parts) {
        int limit = 5;
        if (parts.length > 1) {
            try {
                limit = Integer.parseInt(parts[1]);
                if (limit < 1 || limit > 20) {
                    message.getChannel().sendMessage("Число после !last должно быть от 1 до 20.").queue();
                    return;
                }
            } catch (NumberFormatException ignored) {
                message.getChannel().sendMessage("Число после !last должно быть от 1 до 20.").queue();
                return;
            }
        }

        List<StoredMessage> recentMessages = repository.findRecentMessages(message.getChannel().getId(), limit);
        if (recentMessages.isEmpty()) {
            message.getChannel().sendMessage("В базе пока нет сообщений для этого канала.").queue();
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

        message.getChannel().sendMessage(builder.toString()).queue();
    }

    private void callEveryone(Message message) {
        if (!ensureCallPermission(message)) {
            return;
        }

        String callText = AdminCommandConfig.CALL_MESSAGE_TEXT;
        if (callText == null || callText.isBlank()) {
            message.getChannel().sendMessage("Заполни текст команды !зов в AdminCommandConfig.java").queue();
            return;
        }

        String payload = "@everyone " + callText.trim();
        for (int i = 0; i < AdminCommandConfig.CALL_REPEAT_COUNT; i++) {
            message.getChannel().sendMessage(payload).queue();
        }
    }

    private void clearMessages(Message message, String[] parts) {
        if (!ensureAdmin(message)) {
            return;
        }

        if (!message.isFromGuild()) {
            message.getChannel().sendMessage("Эта команда работает только в серверных текстовых каналах.").queue();
            return;
        }

        int amountToDelete = 10;
        if (parts.length > 1) {
            try {
                amountToDelete = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                message.getChannel().sendMessage("Используй !clear <число от 1 до 100>.").queue();
                return;
            }
        }

        if (amountToDelete < 1 || amountToDelete > 100) {
            message.getChannel().sendMessage("Можно удалить только от 1 до 100 сообщений за раз.").queue();
            return;
        }

        TextChannel textChannel = message.getGuild().getTextChannelById(message.getChannel().getId());
        if (textChannel == null) {
            message.getChannel().sendMessage("Эта команда сейчас поддерживается только в обычных текстовых каналах сервера.").queue();
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
                message.getChannel().sendMessage("Не нашлось сообщений моложе 14 дней для удаления.").queue();
                return;
            }

            if (recentMessages.size() == 1) {
                recentMessages.get(0).delete().queue();
            } else {
                textChannel.purgeMessages(recentMessages);
            }
            message.delete().queue(null, failure -> {
            });

            if (skippedOldMessages > 0) {
                textChannel.sendMessage("Удалил " + recentMessages.size() + " сообщений. "
                        + skippedOldMessages + " старых сообщений старше 14 дней Discord не дал удалить пачкой.").queue();
            }
        }, failure -> message.getChannel().sendMessage("Не получилось очистить сообщения: " + failure.getMessage()).queue());
    }

    private boolean ensureAdmin(Message message) {
        Member member = message.getMember();
        if (member == null || !member.hasPermission(Permission.ADMINISTRATOR)) {
            message.getChannel().sendMessage("Эта команда доступна только администраторам.").queue();
            return false;
        }

        return true;
    }

    private boolean ensureCallPermission(Message message) {
        Member member = message.getMember();
        if (member == null) {
            message.getChannel().sendMessage("Эта команда работает только на сервере.").queue();
            return false;
        }

        if (member.hasPermission(Permission.ADMINISTRATOR)) {
            return true;
        }

        for (Role role : member.getRoles()) {
            if (AdminCommandConfig.CALL_ALLOWED_ROLE_IDS.contains(role.getId())) {
                return true;
            }

            for (String allowedRoleName : AdminCommandConfig.CALL_ALLOWED_ROLE_NAMES) {
                if (role.getName().equalsIgnoreCase(allowedRoleName)) {
                    return true;
                }
            }
        }

        message.getChannel().sendMessage("Эта команда доступна только администраторам и роли \"управленец\".").queue();
        return false;
    }
}
