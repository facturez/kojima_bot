package org.example.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
