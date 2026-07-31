package org.example.bot;

import org.example.db.LegacyGuildConfig;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class LegacyConfigLoader {
    public record Bootstrap(String guildId, LegacyGuildConfig config) {}
    private LegacyConfigLoader() {}

    public static Optional<Bootstrap> fromEnvironment(Map<String, String> environment) {
        String guildId = environment.get("LEGACY_GUILD_ID");
        if (guildId == null || guildId.isBlank()) return Optional.empty();
        String channel = environment.getOrDefault("DAILY_CHANNEL_ID", ScheduledMessageConfig.DAILY_CHANNEL_ID);
        Set<String> roles = Arrays.stream(environment.getOrDefault("CALL_ALLOWED_ROLE_IDS", "").split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
        LegacyGuildConfig config = new LegacyGuildConfig(channel, ScheduledMessageConfig.TIME_ZONE,
                ScheduledMessageConfig.DAILY_MESSAGE_PREFIX, ScheduledMessageConfig.BASE_DATE,
                ScheduledMessageConfig.BASE_DAY_NUMBER, AdminCommandConfig.CALL_MESSAGE_TEXT,
                AdminCommandConfig.CALL_REPEAT_COUNT, roles.isEmpty() ? Set.copyOf(AdminCommandConfig.CALL_ALLOWED_ROLE_IDS) : roles);
        return Optional.of(new Bootstrap(guildId.trim(), config));
    }
}
