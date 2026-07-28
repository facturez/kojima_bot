package org.example;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.example.bot.DailyMessageScheduler;
import org.example.bot.MessageListener;
import org.example.bot.ScheduledMessageConfig;
import org.example.db.MessageRetentionConfig;
import org.example.db.MessageRepository;

import java.util.function.Supplier;

public class Main {
    public static void main(String[] args) {
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Missing DISCORD_TOKEN environment variable.");
            System.err.println("Set it before running the bot.");
            return;
        }

        String databasePath = System.getenv().getOrDefault("BOT_DB_PATH", "bot-data.db");
        int retentionDays = MessageRetentionConfig.parseDays(System.getenv("MESSAGE_RETENTION_DAYS"));
        MessageRepository repository = new MessageRepository(databasePath, retentionDays, java.time.Clock.systemUTC());

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.DIRECT_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT
                    )
                    .addEventListeners(new MessageListener(repository))
                    .build()
                    .awaitReady();

            Supplier<String> dailyMessageSupplier = ScheduledMessageConfig::buildDailyMessageText;

            DailyMessageScheduler scheduler = new DailyMessageScheduler(
                    jda,
                    ScheduledMessageConfig.DAILY_CHANNEL_ID,
                    dailyMessageSupplier,
                    ScheduledMessageConfig.TIME_ZONE,
                    repository
            );
            scheduler.start();

            Runtime.getRuntime().addShutdownHook(new Thread(scheduler::shutdown));
            System.out.println("Bot is ready.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Bot startup was interrupted.");
        }
    }
}
