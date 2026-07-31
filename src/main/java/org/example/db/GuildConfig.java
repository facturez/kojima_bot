package org.example.db;

public record GuildConfig(String guildId, String guildName, boolean active, boolean archiveEnabled,
                          DailyMessageSettings daily, CallSettings call) {
}
