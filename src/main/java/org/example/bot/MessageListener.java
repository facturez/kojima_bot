package org.example.bot;

import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.example.db.MessageRepository;

public class MessageListener extends ListenerAdapter {
    private final MessageRepository repository;
    private final CommandHandler commandHandler;
    private final SlashCommandHandler slashCommandHandler;

    public MessageListener(MessageRepository repository) {
        this.repository = repository;
        this.commandHandler = new CommandHandler(repository);
        this.slashCommandHandler = new SlashCommandHandler(repository);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }

        repository.saveMessage(event.getMessage());
        commandHandler.handle(event.getMessage());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        slashCommandHandler.handle(event);
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        deleteArchivedMessage(event.getMessageId());
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        try {
            repository.deleteMessages(event.getMessageIds());
        } catch (RuntimeException failure) {
            System.err.println("Failed to delete archived messages: " + failure.getMessage());
        }
    }

    void deleteArchivedMessage(String messageId) {
        try {
            repository.deleteMessage(messageId);
        } catch (RuntimeException failure) {
            System.err.println("Failed to delete archived message: " + failure.getMessage());
        }
    }
}
