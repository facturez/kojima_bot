package org.example.bot;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyConfigLoaderTest {
    @Test void requiresExplicitLegacyGuildId() {
        assertTrue(LegacyConfigLoader.fromEnvironment(Map.of()).isEmpty());
        assertTrue(LegacyConfigLoader.fromEnvironment(Map.of("LEGACY_GUILD_ID", " ")).isEmpty());
    }

    @Test void loadsExplicitGuildAndOverridesDailyChannel() {
        var bootstrap = LegacyConfigLoader.fromEnvironment(Map.of(
                "LEGACY_GUILD_ID", "123", "DAILY_CHANNEL_ID", "456", "CALL_ALLOWED_ROLE_IDS", "7, 8"
        )).orElseThrow();
        assertEquals("123", bootstrap.guildId());
        assertEquals("456", bootstrap.config().dailyChannelId());
        assertEquals(java.util.Set.of("7", "8"), bootstrap.config().allowedRoleIds());
    }
}
