package org.example.db;

import java.time.LocalDate;
import java.time.ZoneId;

public record DailyMessageSettings(String guildId, boolean enabled, String channelId, ZoneId timezone,
                                   String messagePrefix, LocalDate baseDate, int baseDayNumber) {
}
