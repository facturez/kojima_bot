package org.example.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.example.db.MessageRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyMessageSchedulerTest {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant AFTER_CATCH_UP_WINDOW = Instant.parse("2026-07-29T09:00:00Z");

    @Test
    void schedulesNextMoscowMidnightIndependentlyOfInputOffset() {
        ZonedDateTime now = ZonedDateTime.parse("2026-07-28T22:30:00+02:00[Europe/Berlin]");

        ZonedDateTime next = DailyMessageScheduler.nextRunAfter(now, MOSCOW);

        assertEquals(ZonedDateTime.parse("2026-07-29T00:00:00+03:00[Europe/Moscow]"), next);
    }

    @Test
    void sendsCatchUpWhenStartedAfter0015Moscow(@TempDir Path temporaryDirectory) {
        MessageRepository repository = new MessageRepository(temporaryDirectory.resolve("bot.db").toString());
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        AtomicInteger sends = new AtomicInteger();
        DailyMessageScheduler scheduler = scheduler(
                repository,
                taskScheduler,
                (message, success, failure) -> {
                    sends.incrementAndGet();
                    success.run();
                }
        );

        scheduler.start();

        assertEquals(1, sends.get());
        assertEquals("2026-07-29", repository.getLastDailyMessageDate().orElseThrow().toString());
    }

    @Test
    void doesNotSendCatchUpAgainAfterSuccessfulSend(@TempDir Path temporaryDirectory) {
        MessageRepository repository = new MessageRepository(temporaryDirectory.resolve("bot.db").toString());
        AtomicInteger sends = new AtomicInteger();
        DailyMessageScheduler.DailyMessageSender sender = (message, success, failure) -> {
            sends.incrementAndGet();
            success.run();
        };

        scheduler(repository, new RecordingTaskScheduler(), sender).start();
        scheduler(repository, new RecordingTaskScheduler(), sender).start();

        assertEquals(1, sends.get());
    }

    @Test
    void retriesDiscordFailuresWithExponentialBackoffUntilSuccess(@TempDir Path temporaryDirectory) {
        MessageRepository repository = new MessageRepository(temporaryDirectory.resolve("bot.db").toString());
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        AtomicInteger sends = new AtomicInteger();
        DailyMessageScheduler scheduler = scheduler(
                repository,
                taskScheduler,
                (message, success, failure) -> {
                    if (sends.incrementAndGet() < 4) {
                        failure.accept(new RuntimeException("temporary Discord failure"));
                    } else {
                        success.run();
                    }
                }
        );

        scheduler.start();
        taskScheduler.runNextRetry();
        taskScheduler.runNextRetry();
        taskScheduler.runNextRetry();

        assertEquals(4, sends.get());
        assertEquals(List.of(1L, 2L, 4L), taskScheduler.retryDelaysMinutes);
        assertEquals("2026-07-29", repository.getLastDailyMessageDate().orElseThrow().toString());
    }

    private static DailyMessageScheduler scheduler(
            MessageRepository repository,
            RecordingTaskScheduler taskScheduler,
            DailyMessageScheduler.DailyMessageSender sender
    ) {
        return new DailyMessageScheduler(
                "123456789012345678",
                () -> "daily message",
                MOSCOW,
                repository,
                Clock.fixed(AFTER_CATCH_UP_WINDOW, ZoneId.of("UTC")),
                taskScheduler,
                sender
        );
    }

    private static final class RecordingTaskScheduler implements DailyMessageScheduler.TaskScheduler {
        private final Queue<Runnable> retryTasks = new ArrayDeque<>();
        private final List<Long> retryDelaysMinutes = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delay, TimeUnit unit) {
            long delayMinutes = unit.toMinutes(delay);
            if (delayMinutes <= 4) {
                retryDelaysMinutes.add(delayMinutes);
                retryTasks.add(task);
            }
        }

        @Override
        public void shutdownNow() {
        }

        void runNextRetry() {
            retryTasks.remove().run();
        }
    }
}
