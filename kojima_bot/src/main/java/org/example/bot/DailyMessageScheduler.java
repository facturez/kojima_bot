package org.example.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class DailyMessageScheduler {
    private final JDA jda;
    private final String channelId;
    private final Supplier<String> messageSupplier;
    private final ZoneId zoneId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public DailyMessageScheduler(JDA jda, String channelId, Supplier<String> messageSupplier, ZoneId zoneId) {
        this.jda = jda;
        this.channelId = channelId;
        this.messageSupplier = messageSupplier;
        this.zoneId = zoneId;
    }

    public void start() {
        if (channelId == null || channelId.isBlank() || channelId.equals("PASTE_CHANNEL_ID_HERE")) {
            System.err.println("Daily message is disabled: configure DAILY_CHANNEL_ID in ScheduledMessageConfig.");
            return;
        }

        String messageText = messageSupplier.get();
        if (messageText == null || messageText.isBlank()) {
            System.err.println("Daily message is disabled: generated message is empty.");
            return;
        }

        scheduleNextRun();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void scheduleNextRun() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime nextRun = now.toLocalDate().plusDays(1).atStartOfDay(zoneId);
        long delayMillis = Duration.between(now, nextRun).toMillis();

        System.out.println("Next daily message at: " + nextRun);
        scheduler.schedule(this::sendAndReschedule, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void sendAndReschedule() {
        try {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                System.err.println("Daily message failed: channel not found for id " + channelId);
                return;
            }

            String messageText = messageSupplier.get();
            if (messageText == null || messageText.isBlank()) {
                System.err.println("Daily message failed: generated message is empty.");
                return;
            }

            channel.sendMessage(messageText).queue(
                    success -> System.out.println("Daily message sent successfully."),
                    failure -> System.err.println("Daily message failed: " + failure.getMessage())
            );
        } finally {
            scheduleNextRun();
        }
    }
}
