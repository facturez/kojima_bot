package org.example.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GuildConfigRepositoryTest {
    @TempDir Path tempDir;
    GuildConfigRepository repository;

    @BeforeEach void setUp() { repository = new GuildConfigRepository(tempDir.resolve("config.db").toString()); }

    @Test void createsSafeDefaultsAndPreservesThemAcrossLeaveAndJoin() {
        repository.activateGuild("g1", "One");
        GuildConfig initial = repository.requireConfig("g1");
        assertFalse(initial.archiveEnabled());
        assertFalse(initial.daily().enabled());
        assertFalse(initial.call().enabled());
        repository.setArchiveEnabled("g1", true);
        repository.deactivateGuild("g1");
        repository.activateGuild("g1", "Renamed");
        assertTrue(repository.requireConfig("g1").active());
        assertTrue(repository.requireConfig("g1").archiveEnabled());
        assertEquals("Renamed", repository.requireConfig("g1").guildName());
    }

    @Test void partialUpdatesAndStateAreGuildScoped() {
        repository.activateGuild("g1", "One");
        repository.activateGuild("g2", "Two");
        repository.updateDaily("g1", new DailySettingsPatch(Optional.empty(), Optional.of("channel"),
                Optional.of(ZoneId.of("Europe/Berlin")), Optional.empty(), Optional.empty(), Optional.empty()));
        repository.addAllowedRole("g1", "role");
        repository.setLastDailyMessageDate("g1", LocalDate.of(2026, 7, 31));

        assertEquals("channel", repository.requireConfig("g1").daily().channelId());
        assertEquals(GuildConfigRepository.DEFAULT_PREFIX, repository.requireConfig("g1").daily().messagePrefix());
        assertTrue(repository.requireConfig("g1").call().allowedRoleIds().contains("role"));
        assertFalse(repository.requireConfig("g2").call().allowedRoleIds().contains("role"));
        assertEquals(Optional.empty(), repository.getLastDailyMessageDate("g2"));
    }

    @Test void legacyBootstrapRunsOnceWithoutOverwritingChanges() {
        LegacyGuildConfig legacy = new LegacyGuildConfig("channel", ZoneId.of("Europe/Moscow"), "prefix",
                LocalDate.of(2026, 1, 1), 5, "call", 2, Set.of("role"));
        assertTrue(repository.bootstrapLegacy("g", "Legacy", legacy));
        assertTrue(repository.requireConfig("g").archiveEnabled());
        repository.setArchiveEnabled("g", false);
        assertFalse(repository.bootstrapLegacy("g", "Legacy", legacy));
        assertFalse(repository.requireConfig("g").archiveEnabled());
    }
}
