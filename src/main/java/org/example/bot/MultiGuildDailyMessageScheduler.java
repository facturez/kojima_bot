package org.example.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.example.db.DailyMessageSettings;
import org.example.db.GuildConfigRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MultiGuildDailyMessageScheduler {
    private static final long[] RETRIES = {1, 2, 4};
    private final JDA jda;
    private final GuildConfigRepository configs;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final Map<String, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();
    private final Map<String, Long> generations = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public MultiGuildDailyMessageScheduler(JDA jda, GuildConfigRepository configs) {
        this(jda, configs, Clock.systemUTC(), Executors.newSingleThreadScheduledExecutor());
    }

    MultiGuildDailyMessageScheduler(JDA jda, GuildConfigRepository configs, Clock clock,
                                    ScheduledExecutorService executor) {
        this.jda = jda;
        this.configs = configs;
        this.clock = clock;
        this.executor = executor;
    }

    public void start() {
        for (DailyMessageSettings settings : configs.findActiveDailySettings()) refreshGuild(settings.guildId());
    }

    public synchronized void refreshGuild(String guildId) {
        removeGuild(guildId);
        long generation = generations.get(guildId);
        configs.findGuild(guildId).filter(config -> config.active() && config.daily().enabled())
                .ifPresent(config -> {
                    DailyMessageSettings settings = config.daily();
                    LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(settings.timezone()).toLocalDate();
                    if (configs.getLastDailyMessageDate(guildId).filter(today::equals).isEmpty()) {
                        send(settings, today, 0, generation);
                    }
                    scheduleNext(settings, generation);
                });
    }

    public synchronized void removeGuild(String guildId) {
        generations.merge(guildId, 1L, Long::sum);
        ScheduledFuture<?> old = jobs.remove(guildId);
        if (old != null) old.cancel(false);
    }

    public void shutdown() {
        jobs.values().forEach(job -> job.cancel(false));
        executor.shutdownNow();
    }

    private synchronized void scheduleNext(DailyMessageSettings settings, long generation) {
        if (generations.getOrDefault(settings.guildId(), 0L) != generation) return;
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(settings.timezone());
        ZonedDateTime next = nextRunAfter(now, settings.timezone());
        long delay = Math.max(0, Duration.between(now, next).toMillis());
        jobs.put(settings.guildId(), executor.schedule(() -> {
            LocalDate date = ZonedDateTime.now(clock).withZoneSameInstant(settings.timezone()).toLocalDate();
            send(settings, date, 0, generation);
            scheduleNext(settings, generation);
        }, delay, TimeUnit.MILLISECONDS));
    }

    static ZonedDateTime nextRunAfter(ZonedDateTime now, ZoneId zone) {
        ZonedDateTime local = now.withZoneSameInstant(zone);
        return local.toLocalDate().plusDays(1).atStartOfDay(zone);
    }

    private synchronized void send(DailyMessageSettings settings, LocalDate date, int retry, long generation) {
        if (generations.getOrDefault(settings.guildId(), 0L) != generation) return;
        boolean settingsAreCurrent = configs.findGuild(settings.guildId())
                .filter(config -> config.active() && config.daily().enabled())
                .map(config -> config.daily().equals(settings))
                .orElse(false);
        if (!settingsAreCurrent) return;
        if (configs.getLastDailyMessageDate(settings.guildId()).filter(date::equals).isPresent()) return;
        if (!inFlight.add(settings.guildId())) return;
        TextChannel channel = settings.channelId() == null ? null : jda.getTextChannelById(settings.channelId());
        if (channel == null || !channel.getGuild().getId().equals(settings.guildId())) {
            inFlight.remove(settings.guildId());
            retry(settings, date, retry, generation,
                    new IllegalStateException("Configured channel is unavailable or belongs to another guild"));
            return;
        }
        long day = settings.baseDayNumber() + ChronoUnit.DAYS.between(settings.baseDate(), date);
        channel.sendMessage(settings.messagePrefix() + " " + day).queue(success -> {
            configs.setLastDailyMessageDate(settings.guildId(), date);
            inFlight.remove(settings.guildId());
        }, failure -> {
            inFlight.remove(settings.guildId());
            retry(settings, date, retry, generation, failure);
        });
    }

    private void retry(DailyMessageSettings settings, LocalDate date, int retry, long generation, Throwable failure) {
        if (retry >= RETRIES.length) {
            System.err.println("Daily message failed for guild " + settings.guildId() + ": " + failure.getMessage());
            return;
        }
        executor.schedule(() -> send(settings, date, retry + 1, generation), RETRIES[retry], TimeUnit.MINUTES);
    }
}
