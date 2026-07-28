package org.example.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.example.db.MessageRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class DailyMessageScheduler {
    private static final int STARTUP_CATCH_UP_MINUTES = 15;

    private final JDA jda;
    private final String channelId;
    private final Supplier<String> messageSupplier;
    private final ZoneId zoneId;
    private final MessageRepository repository;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public DailyMessageScheduler(
            JDA jda,
            String channelId,
            Supplier<String> messageSupplier,
            ZoneId zoneId,
            MessageRepository repository
    ) {
        this.jda = jda;
        this.channelId = channelId;
        this.messageSupplier = messageSupplier;
        this.zoneId = zoneId;
        this.repository = repository;
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

        sendStartupCatchUpIfNeeded();
        scheduleNextRun();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void scheduleNextRun() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime nextRun = nextRunAfter(now, zoneId);
        long delayMillis = Duration.between(now, nextRun).toMillis();

        System.out.println("Next daily message at: " + nextRun);
        scheduler.schedule(this::sendAndReschedule, delayMillis, TimeUnit.MILLISECONDS);
    }

    static ZonedDateTime nextRunAfter(ZonedDateTime now, ZoneId zoneId) {
        ZonedDateTime zonedNow = now.withZoneSameInstant(zoneId);
        return zonedNow.toLocalDate().plusDays(1).atStartOfDay(zoneId);
    }

    private void sendAndReschedule() {
        try {
            sendDailyMessage(LocalDate.now(zoneId));
        } finally {
            scheduleNextRun();
        }
    }

    private void sendStartupCatchUpIfNeeded() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        if (now.toLocalTime().isAfter(LocalTime.of(0, STARTUP_CATCH_UP_MINUTES))) {
            return;
        }

        LocalDate today = now.toLocalDate();
        if (repository.getLastDailyMessageDate().filter(today::equals).isPresent()) {
            return;
        }

        System.out.println("Sending startup catch-up daily message for " + today);
        sendDailyMessage(today);
    }

    private void sendDailyMessage(LocalDate date) {
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
                success -> {
                    repository.setLastDailyMessageDate(date);
                    System.out.println("Daily message sent successfully for " + date + ".");
                },
                failure -> System.err.println("Daily message failed: " + failure.getMessage())
        );
    }
}
