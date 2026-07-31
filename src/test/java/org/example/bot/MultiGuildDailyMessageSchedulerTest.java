package org.example.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.example.db.DailySettingsPatch;
import org.example.db.GuildConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiGuildDailyMessageSchedulerTest {
    private static final Instant TODAY = Instant.parse("2026-07-31T09:00:00Z");

    @Test
    void calculatesNextMidnightInEachGuildTimezone() {
        ZonedDateTime instant = ZonedDateTime.parse("2026-07-31T20:30:00Z");

        assertEquals("2026-08-01T00:00+03:00[Europe/Moscow]",
                MultiGuildDailyMessageScheduler.nextRunAfter(instant, ZoneId.of("Europe/Moscow")).toString());
        assertEquals("2026-08-01T00:00-04:00[America/New_York]",
                MultiGuildDailyMessageScheduler.nextRunAfter(instant, ZoneId.of("America/New_York")).toString());
    }

    @Test
    void startupCatchUpPersistsStateAndRestartDoesNotSendAgain(@TempDir Path temporaryDirectory) {
        GuildConfigRepository configs = configuredGuild(temporaryDirectory);
        RecordingDiscord discord = new RecordingDiscord(0);

        scheduler(configs, discord, new RecordingExecutor()).start();
        scheduler(configs, discord, new RecordingExecutor()).start();

        assertEquals(List.of("daily 109"), discord.messages);
        assertEquals(LocalDate.of(2026, 7, 31), configs.getLastDailyMessageDate("guild").orElseThrow());
    }

    @Test
    void retriesFailuresAtOneTwoAndFourMinutesUntilSuccess(@TempDir Path temporaryDirectory) {
        GuildConfigRepository configs = configuredGuild(temporaryDirectory);
        RecordingDiscord discord = new RecordingDiscord(3);
        RecordingExecutor executor = new RecordingExecutor();

        scheduler(configs, discord, executor).start();
        executor.runNextRetry();
        executor.runNextRetry();
        executor.runNextRetry();

        assertEquals(4, discord.attempts.get());
        assertEquals(List.of(1L, 2L, 4L), executor.retryDelaysMinutes);
        assertEquals(LocalDate.of(2026, 7, 31), configs.getLastDailyMessageDate("guild").orElseThrow());
    }

    @Test
    void failedDeliveryDoesNotAdvanceState(@TempDir Path temporaryDirectory) {
        GuildConfigRepository configs = configuredGuild(temporaryDirectory);
        RecordingDiscord discord = new RecordingDiscord(Integer.MAX_VALUE);
        RecordingExecutor executor = new RecordingExecutor();

        scheduler(configs, discord, executor).start();
        executor.runNextRetry();
        executor.runNextRetry();
        executor.runNextRetry();

        assertEquals(4, discord.attempts.get());
        assertTrue(configs.getLastDailyMessageDate("guild").isEmpty());
    }

    @Test
    void retryQueuedBeforeDailyIsDisabledCannotSendStaleMessage(@TempDir Path temporaryDirectory) {
        GuildConfigRepository configs = configuredGuild(temporaryDirectory);
        RecordingDiscord discord = new RecordingDiscord(1);
        RecordingExecutor executor = new RecordingExecutor();
        MultiGuildDailyMessageScheduler scheduler = scheduler(configs, discord, executor);
        scheduler.start();

        configs.updateDaily("guild", new DailySettingsPatch(Optional.of(false), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()));
        scheduler.refreshGuild("guild");
        executor.runNextRetry();

        assertEquals(1, discord.attempts.get());
        assertTrue(configs.getLastDailyMessageDate("guild").isEmpty());
    }

    @Test
    void normalMidnightDeliveryStillRetriesAfterAnAsynchronousFailure(@TempDir Path temporaryDirectory) {
        GuildConfigRepository configs = configuredGuild(temporaryDirectory);
        configs.setLastDailyMessageDate("guild", LocalDate.of(2026, 7, 31));
        RecordingDiscord discord = new RecordingDiscord(1);
        RecordingExecutor executor = new RecordingExecutor();
        MutableClock clock = new MutableClock(TODAY);
        new MultiGuildDailyMessageScheduler(discord.jda, configs, clock, executor).start();

        clock.advance(Duration.ofDays(1));
        executor.runNextDaily();
        assertEquals(1, discord.attempts.get());
        assertEquals(List.of(1L), executor.retryDelaysMinutes);
        executor.runNextRetry();

        assertEquals(2, discord.attempts.get());
        assertEquals(Optional.of(LocalDate.of(2026, 8, 1)), configs.getLastDailyMessageDate("guild"));
    }

    @Test
    void invalidatedMidnightTaskCannotOverwriteTheRefreshedSchedule(@TempDir Path temporaryDirectory) {
        GuildConfigRepository configs = configuredGuild(temporaryDirectory);
        configs.setLastDailyMessageDate("guild", LocalDate.of(2026, 7, 31));
        RecordingExecutor executor = new RecordingExecutor();
        MultiGuildDailyMessageScheduler scheduler = scheduler(configs, new RecordingDiscord(0), executor);
        scheduler.start();
        ScheduledTask invalidated = executor.dailyTasks.get(0);

        scheduler.refreshGuild("guild");
        invalidated.runIgnoringCancellation();

        assertEquals(2, executor.dailyTasks.size());
    }

    private static GuildConfigRepository configuredGuild(Path temporaryDirectory) {
        GuildConfigRepository configs = new GuildConfigRepository(temporaryDirectory.resolve("config.db").toString());
        configs.activateGuild("guild", "Legacy Guild");
        configs.updateDaily("guild", new DailySettingsPatch(Optional.of(true), Optional.of("channel"),
                Optional.of(ZoneId.of("Europe/Moscow")), Optional.of("daily"),
                Optional.of(LocalDate.of(2026, 7, 29)), Optional.of(107)));
        return configs;
    }

    private static MultiGuildDailyMessageScheduler scheduler(
            GuildConfigRepository configs, RecordingDiscord discord, RecordingExecutor executor) {
        return new MultiGuildDailyMessageScheduler(discord.jda, configs,
                Clock.fixed(TODAY, ZoneId.of("UTC")), executor);
    }

    private static final class RecordingDiscord {
        private final AtomicInteger attempts = new AtomicInteger();
        private final List<String> messages = new ArrayList<>();
        private final JDA jda;

        private RecordingDiscord(int failuresBeforeSuccess) {
            Guild guild = proxy(Guild.class, (method, arguments) ->
                    method.getName().equals("getId") ? "guild" : defaultValue(method.getReturnType()));
            MessageCreateAction action = proxy(MessageCreateAction.class, (method, arguments) -> {
                if (method.getName().equals("queue") && arguments != null && arguments.length == 2) {
                    int attempt = attempts.incrementAndGet();
                    @SuppressWarnings("unchecked") Consumer<Object> success = (Consumer<Object>) arguments[0];
                    @SuppressWarnings("unchecked") Consumer<Throwable> failure = (Consumer<Throwable>) arguments[1];
                    if (attempt <= failuresBeforeSuccess) failure.accept(new IllegalStateException("temporary failure"));
                    else success.accept(null);
                }
                return defaultValue(method.getReturnType());
            });
            TextChannel channel = proxy(TextChannel.class, (method, arguments) -> switch (method.getName()) {
                case "getGuild" -> guild;
                case "sendMessage" -> {
                    messages.add(arguments[0].toString());
                    yield action;
                }
                default -> defaultValue(method.getReturnType());
            });
            jda = proxy(JDA.class, (method, arguments) ->
                    method.getName().equals("getTextChannelById") ? channel : defaultValue(method.getReturnType()));
        }
    }

    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> invocation.invoke(method, arguments));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }

    private static final class RecordingExecutor extends AbstractExecutorService implements ScheduledExecutorService {
        private final List<ScheduledTask> retryTasks = new ArrayList<>();
        private final List<ScheduledTask> dailyTasks = new ArrayList<>();
        private final List<Long> retryDelaysMinutes = new ArrayList<>();
        private boolean shutdown;

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ScheduledTask task = new ScheduledTask(command, delay, unit);
            if (unit == TimeUnit.MINUTES) {
                retryDelaysMinutes.add(delay);
                retryTasks.add(task);
            } else {
                dailyTasks.add(task);
            }
            return task;
        }

        void runNextRetry() {
            retryTasks.remove(0).run();
        }

        void runNextDaily() {
            dailyTasks.remove(0).run();
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
        @Override public void execute(Runnable command) { command.run(); }
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final class ScheduledTask implements ScheduledFuture<Object> {
        private final Runnable command;
        private final long delay;
        private final TimeUnit unit;
        private boolean cancelled;

        private ScheduledTask(Runnable command, long delay, TimeUnit unit) {
            this.command = command;
            this.delay = delay;
            this.unit = unit;
        }

        void run() { if (!cancelled) command.run(); }
        void runIgnoringCancellation() { command.run(); }
        @Override public long getDelay(TimeUnit target) { return target.convert(delay, unit); }
        @Override public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS)); }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }
}
