package org.example.db;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

public record LegacyGuildConfig(String dailyChannelId, ZoneId timezone, String messagePrefix,
                                LocalDate baseDate, int baseDayNumber, String callMessage,
                                int callRepeatCount, Set<String> allowedRoleIds) {
}
