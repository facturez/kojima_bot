package org.example.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.example.db.MessageRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class DailyMessageScheduler {
    private static final long[] RETRY_DELAYS_MINUTES = {1, 2, 4};

    private final String channelId;
    private final Supplier<String> messageSupplier;
    private final ZoneId zoneId;
    private final MessageRepository repository;
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final DailyMessageSender messageSender;

    public DailyMessageScheduler(
            JDA jda,
            String channelId,
            Supplier<String> messageSupplier,
            ZoneId zoneId,
            MessageRepository repository
    ) {
        this(
                channelId,
                messageSupplier,
                zoneId,
                repository,
                Clock.systemUTC(),
                new ExecutorTaskScheduler(),
                createJdaMessageSender(jda, channelId)
        );
    }

    DailyMessageScheduler(
            String channelId,
            Supplier<String> messageSupplier,
            ZoneId zoneId,
            MessageRepository repository,
            Clock clock,
            TaskScheduler scheduler,
            DailyMessageSender messageSender
    ) {
        this.channelId = channelId;
        this.messageSupplier = messageSupplier;
        this.zoneId = zoneId;
        this.repository = repository;
        this.clock = clock;
        this.scheduler = scheduler;
        this.messageSender = messageSender;
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
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zoneId);
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
            sendDailyMessage(ZonedDateTime.now(clock).withZoneSameInstant(zoneId).toLocalDate(), 0);
        } finally {
            scheduleNextRun();
        }
    }

    private void sendStartupCatchUpIfNeeded() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zoneId);
        LocalDate today = now.toLocalDate();
        if (repository.getLastDailyMessageDate().filter(today::equals).isPresent()) {
            return;
        }

        System.out.println("Sending startup catch-up daily message for " + today);
        sendDailyMessage(today, 0);
    }

    private void sendDailyMessage(LocalDate date, int retryIndex) {
        if (repository.getLastDailyMessageDate().filter(date::equals).isPresent()) {
            return;
        }

        String messageText = messageSupplier.get();
        if (messageText == null || messageText.isBlank()) {
            System.err.println("Daily message failed: generated message is empty.");
            return;
        }

        messageSender.send(
                messageText,
                () -> {
                    repository.setLastDailyMessageDate(date);
                    System.out.println("Daily message sent successfully for " + date + ".");
                },
                failure -> scheduleRetry(date, retryIndex, failure)
        );
    }

    private void scheduleRetry(LocalDate date, int retryIndex, Throwable failure) {
        if (retryIndex >= RETRY_DELAYS_MINUTES.length) {
            System.err.println("Daily message failed after all retries: " + failure.getMessage());
            return;
        }

        long delayMinutes = RETRY_DELAYS_MINUTES[retryIndex];
        System.err.println(
                "Daily message failed: " + failure.getMessage()
                        + ". Retrying in " + delayMinutes + " minute(s)."
        );
        scheduler.schedule(
                () -> sendDailyMessage(date, retryIndex + 1),
                delayMinutes,
                TimeUnit.MINUTES
        );
    }

    private static DailyMessageSender createJdaMessageSender(JDA jda, String channelId) {
        return (messageText, success, failure) -> {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                System.err.println("Daily message failed: channel not found for id " + channelId);
                return;
            }

            channel.sendMessage(messageText).queue(ignored -> success.run(), failure);
        };
    }

    @FunctionalInterface
    interface DailyMessageSender {
        void send(String messageText, Runnable success, Consumer<Throwable> failure);
    }

    interface TaskScheduler {
        void schedule(Runnable task, long delay, TimeUnit unit);

        void shutdownNow();
    }

    private static final class ExecutorTaskScheduler implements TaskScheduler {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        @Override
        public void schedule(Runnable task, long delay, TimeUnit unit) {
            executor.schedule(task, delay, unit);
        }

        @Override
        public void shutdownNow() {
            executor.shutdownNow();
        }
    }
}
