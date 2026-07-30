package org.example.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.example.bot.CommandAuthorization.ModerationDenial.ADMINISTRATOR_REQUIRED;
import static org.example.bot.CommandAuthorization.ModerationDenial.BOT_HIERARCHY;
import static org.example.bot.CommandAuthorization.ModerationDenial.BOT_PERMISSION;
import static org.example.bot.CommandAuthorization.ModerationDenial.BOT_SELF_TARGET;
import static org.example.bot.CommandAuthorization.ModerationDenial.CALLER_HIERARCHY;
import static org.example.bot.CommandAuthorization.ModerationDenial.GUILD_ONLY;
import static org.example.bot.CommandAuthorization.ModerationDenial.NONE;
import static org.example.bot.CommandAuthorization.ModerationDenial.OWNER_TARGET;
import static org.example.bot.CommandAuthorization.ModerationDenial.SELF_TARGET;
import static org.example.bot.CommandAuthorization.checkModeration;

class CommandAuthorizationTest {
    @Test
    void sameRoleNameCannotGrantCallAccess() {
        assertFalse(CommandAuthorization.canCallEveryone(false, List.of("222"), List.of("111")));
    }

    @Test
    void configuredRoleIdGrantsCallAccess() {
        assertTrue(CommandAuthorization.canCallEveryone(false, List.of("111"), List.of("111")));
    }

    @Test
    void emptyRoleAllowlistDeniesNonAdministrator() {
        assertFalse(CommandAuthorization.canCallEveryone(false, List.of("111"), List.of()));
    }

    @Test
    void administratorCanCallWithEmptyAllowlist() {
        assertTrue(CommandAuthorization.canCallEveryone(true, List.of(), List.of()));
    }

    @Test
    void archiveRequiresGuildManageMessagesPermission() {
        assertTrue(CommandAuthorization.canReadArchive(true, true));
        assertFalse(CommandAuthorization.canReadArchive(true, false));
        assertFalse(CommandAuthorization.canReadArchive(false, true));
    }

    @Test
    void moderationRequiresEverySafetyCondition() {
        assertEquals(NONE, checkModeration(true, true, false, false, false, true, true, true));
        assertEquals(GUILD_ONLY, checkModeration(false, true, false, false, false, true, true, true));
        assertEquals(ADMINISTRATOR_REQUIRED, checkModeration(true, false, false, false, false, true, true, true));
        assertEquals(SELF_TARGET, checkModeration(true, true, true, false, false, true, true, true));
        assertEquals(OWNER_TARGET, checkModeration(true, true, false, true, false, true, true, true));
        assertEquals(BOT_SELF_TARGET, checkModeration(true, true, false, false, true, true, true, true));
        assertEquals(CALLER_HIERARCHY, checkModeration(true, true, false, false, false, false, true, true));
        assertEquals(BOT_HIERARCHY, checkModeration(true, true, false, false, false, true, false, true));
        assertEquals(BOT_PERMISSION, checkModeration(true, true, false, false, false, true, true, false));
    }
}
