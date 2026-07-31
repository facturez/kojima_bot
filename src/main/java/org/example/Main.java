package org.example;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.example.bot.MultiGuildDailyMessageScheduler;
import org.example.bot.CommandHandler;
import org.example.bot.SlashCommandHandler;
import org.example.bot.SetupCommandHandler;
import org.example.bot.GuildLifecycleListener;
import org.example.bot.LegacyConfigLoader;
import org.example.bot.MessageListener;
import org.example.bot.SlashCommandDefinitions;
import org.example.db.MessageRetentionConfig;
import org.example.db.MessageRepository;
import org.example.db.DatabaseConnectionFactory;
import org.example.db.DatabaseMigrator;
import org.example.db.GuildConfigRepository;

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
        DatabaseConnectionFactory connections = new DatabaseConnectionFactory(databasePath);
        new DatabaseMigrator(connections).migrate();
        MessageRepository repository = new MessageRepository(databasePath, retentionDays, java.time.Clock.systemUTC());
        GuildConfigRepository configs = new GuildConfigRepository(connections);

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.DIRECT_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT
                    )
                    .build()
                    .awaitReady();

            LegacyConfigLoader.fromEnvironment(System.getenv()).ifPresent(bootstrap -> {
                String name = jda.getGuildById(bootstrap.guildId()) == null
                        ? bootstrap.guildId() : jda.getGuildById(bootstrap.guildId()).getName();
                configs.bootstrapLegacy(bootstrap.guildId(), name, bootstrap.config());
            });
            jda.getGuilds().forEach(guild -> configs.activateGuild(guild.getId(), guild.getName()));

            MultiGuildDailyMessageScheduler scheduler = new MultiGuildDailyMessageScheduler(jda, configs);
            SetupCommandHandler setup = new SetupCommandHandler(configs, scheduler::refreshGuild);
            CommandHandler prefixCommands = new CommandHandler(repository, configs);
            SlashCommandHandler slashCommands = new SlashCommandHandler(repository, configs, setup);
            jda.addEventListener(
                    new MessageListener(repository, configs, prefixCommands, slashCommands),
                    new GuildLifecycleListener(configs, scheduler::refreshGuild, scheduler::removeGuild)
            );

            jda.updateCommands()
                    .addCommands(SlashCommandDefinitions.all())
                    .queue(
                            commands -> System.out.println("Registered " + commands.size() + " global slash commands."),
                            failure -> System.err.println("Failed to register slash commands: " + failure.getMessage())
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
