package org.example.bot;

import java.util.Collection;

public final class CommandAuthorization {
    private CommandAuthorization() {
    }

    public static boolean canCallEveryone(
            boolean administrator,
            Collection<String> memberRoleIds,
            Collection<String> allowedRoleIds
    ) {
        return administrator || memberRoleIds.stream().anyMatch(allowedRoleIds::contains);
    }

    public static boolean canReadArchive(boolean fromGuild, boolean manageMessages) {
        return fromGuild && manageMessages;
    }
}
