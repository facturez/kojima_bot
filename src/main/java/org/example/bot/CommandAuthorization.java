package org.example.bot;

import java.util.Collection;

public final class CommandAuthorization {
    public enum ModerationDenial {
        NONE,
        GUILD_ONLY,
        ADMINISTRATOR_REQUIRED,
        SELF_TARGET,
        OWNER_TARGET,
        BOT_SELF_TARGET,
        CALLER_HIERARCHY,
        BOT_HIERARCHY,
        BOT_PERMISSION
    }

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

    public static ModerationDenial checkModeration(
            boolean fromGuild,
            boolean administrator,
            boolean selfTarget,
            boolean ownerTarget,
            boolean botSelfTarget,
            boolean callerCanInteract,
            boolean botCanInteract,
            boolean botHasPermission
    ) {
        if (!fromGuild) {
            return ModerationDenial.GUILD_ONLY;
        }
        if (!administrator) {
            return ModerationDenial.ADMINISTRATOR_REQUIRED;
        }
        if (selfTarget) {
            return ModerationDenial.SELF_TARGET;
        }
        if (ownerTarget) {
            return ModerationDenial.OWNER_TARGET;
        }
        if (botSelfTarget) {
            return ModerationDenial.BOT_SELF_TARGET;
        }
        if (!callerCanInteract) {
            return ModerationDenial.CALLER_HIERARCHY;
        }
        if (!botCanInteract) {
            return ModerationDenial.BOT_HIERARCHY;
        }
        if (!botHasPermission) {
            return ModerationDenial.BOT_PERMISSION;
        }
        return ModerationDenial.NONE;
    }
}
