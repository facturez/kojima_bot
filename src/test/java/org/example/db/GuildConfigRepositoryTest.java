package org.example.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
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
        GuildConfig migrated = repository.requireConfig("g");
        assertTrue(migrated.archiveEnabled());
        assertTrue(migrated.daily().enabled());
        assertEquals("channel", migrated.daily().channelId());
        assertEquals(ZoneId.of("Europe/Moscow"), migrated.daily().timezone());
        assertEquals("prefix", migrated.daily().messagePrefix());
        assertEquals(LocalDate.of(2026, 1, 1), migrated.daily().baseDate());
        assertEquals(5, migrated.daily().baseDayNumber());
        assertTrue(migrated.call().enabled());
        assertEquals("call", migrated.call().messageText());
        assertEquals(2, migrated.call().repeatCount());
        assertEquals(Set.of("role"), migrated.call().allowedRoleIds());
        repository.setArchiveEnabled("g", false);
        repository.updateCall("g", new CallSettingsPatch(Optional.empty(), Optional.of("admin change"), Optional.empty()));
        assertFalse(repository.bootstrapLegacy("g", "Legacy", legacy));
        assertFalse(repository.requireConfig("g").archiveEnabled());
        assertEquals("admin change", repository.requireConfig("g").call().messageText());
    }

    @Test
    void legacyBootstrapCarriesForwardTheLastDailySendDate() throws Exception {
        String databasePath = tempDir.resolve("legacy-state.db").toString();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE scheduler_state(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            sql.execute("INSERT INTO scheduler_state(key,value) VALUES('daily_message_last_sent_date','2026-07-31')");
        }
        GuildConfigRepository legacyRepository = new GuildConfigRepository(databasePath);
        LegacyGuildConfig legacy = new LegacyGuildConfig("channel", ZoneId.of("Europe/Moscow"), "prefix",
                LocalDate.of(2026, 1, 1), 5, "call", 2, Set.of("role"));

        legacyRepository.bootstrapLegacy("legacy-guild", "Legacy", legacy);

        assertEquals(Optional.of(LocalDate.of(2026, 7, 31)),
                legacyRepository.getLastDailyMessageDate("legacy-guild"));
    }

    @Test
    void legacyStateBackfillStillRunsWhenConfigurationBootstrapWasAlreadyMarkedComplete() throws Exception {
        String databasePath = tempDir.resolve("already-bootstrapped.db").toString();
        GuildConfigRepository legacyRepository = new GuildConfigRepository(databasePath);
        LegacyGuildConfig legacy = new LegacyGuildConfig("channel", ZoneId.of("Europe/Moscow"), "prefix",
                LocalDate.of(2026, 1, 1), 5, "call", 2, Set.of("role"));
        assertTrue(legacyRepository.bootstrapLegacy("legacy-guild", "Legacy", legacy));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO scheduler_state(key,value) VALUES('daily_message_last_sent_date','2026-07-31')");
        }

        assertFalse(legacyRepository.bootstrapLegacy("legacy-guild", "Legacy", legacy));

        assertEquals(Optional.of(LocalDate.of(2026, 7, 31)),
                legacyRepository.getLastDailyMessageDate("legacy-guild"));
    }
}
