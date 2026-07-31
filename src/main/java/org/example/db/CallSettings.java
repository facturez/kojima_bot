package org.example.db;

import java.util.Set;

public record CallSettings(String guildId, boolean enabled, String messageText, int repeatCount,
                           Set<String> allowedRoleIds) {
}
