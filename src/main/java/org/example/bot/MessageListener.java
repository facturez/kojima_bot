package org.example.bot;

import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.example.db.MessageRepository;
import org.example.db.GuildConfigRepository;

public class MessageListener extends ListenerAdapter {
    private final MessageRepository repository;
    private final CommandHandler commandHandler;
    private final SlashCommandHandler slashCommandHandler;
    private final GuildConfigRepository configs;

    public MessageListener(MessageRepository repository) {
        this(repository, null, new CommandHandler(repository), new SlashCommandHandler(repository));
    }

    public MessageListener(MessageRepository repository, GuildConfigRepository configs,
                           CommandHandler commandHandler, SlashCommandHandler slashCommandHandler) {
        this.repository = repository;
        this.configs = configs;
        this.commandHandler = commandHandler;
        this.slashCommandHandler = slashCommandHandler;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        boolean archiveEnabled = event.isFromGuild() && configs != null
                && configs.isArchiveEnabled(event.getGuild().getId());
        if (event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }

        if (shouldArchive(event.isFromGuild(), event.getAuthor().isBot(), event.isWebhookMessage(), archiveEnabled)) {
            repository.saveGuildMessage(event.getGuild().getId(), event.getMessage());
        }
        commandHandler.handle(event.getMessage());
    }

    static boolean shouldArchive(boolean fromGuild, boolean bot, boolean webhook, boolean enabled) {
        return fromGuild && !bot && !webhook && enabled;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        slashCommandHandler.handle(event);
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        if (event.isFromGuild()) deleteArchivedMessage(event.getGuild().getId(), event.getMessageId());
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        try {
            repository.deleteMessages(event.getGuild().getId(), event.getMessageIds());
        } catch (RuntimeException failure) {
            System.err.println("Failed to delete archived messages: " + failure.getMessage());
        }
    }

    void deleteArchivedMessage(String guildId, String messageId) {
        try {
            repository.deleteMessage(guildId, messageId);
        } catch (RuntimeException failure) {
            System.err.println("Failed to delete archived message: " + failure.getMessage());
        }
    }
}
