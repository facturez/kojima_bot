package org.example.bot;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.example.db.MessageRepository;

public class MessageListener extends ListenerAdapter {
    private final MessageRepository repository;
    private final CommandHandler commandHandler;

    public MessageListener(MessageRepository repository) {
        this.repository = repository;
        this.commandHandler = new CommandHandler(repository);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }

        repository.saveMessage(event.getMessage());
        commandHandler.handle(event.getMessage());
    }
}
