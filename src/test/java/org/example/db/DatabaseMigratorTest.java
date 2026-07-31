package org.example.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigratorTest {
    @TempDir Path tempDir;

    @Test void preservesLegacyArchiveAndIsRepeatable() throws Exception {
        String path = tempDir.resolve("legacy.db").toString();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,message_id TEXT NOT NULL UNIQUE,channel_id TEXT NOT NULL,guild_id TEXT,author_id TEXT NOT NULL,author_tag TEXT NOT NULL,content TEXT NOT NULL,created_at TEXT NOT NULL,stored_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            sql.execute("INSERT INTO messages(message_id,channel_id,guild_id,author_id,author_tag,content,created_at) VALUES('m','c','g','a','u','kept','2026-01-01T00:00:00Z')");
        }
        DatabaseConnectionFactory factory = new DatabaseConnectionFactory(path);
        DatabaseMigrator migrator = new DatabaseMigrator(factory);
        migrator.migrate();
        migrator.migrate();

        assertEquals(2, migrator.version());
        try (var connection = factory.open(); Statement sql = connection.createStatement()) {
            assertEquals(1, scalar(sql, "SELECT COUNT(*) FROM messages WHERE content='kept'"));
            assertEquals(1, scalar(sql, "PRAGMA foreign_keys"));
            assertTrue(scalar(sql, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_messages_guild_channel_created'") > 0);
            assertEquals(5, scalar(sql, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('guilds','daily_message_settings','daily_message_state','call_settings','call_allowed_roles')"));
        }
    }

    private static int scalar(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) { return result.getInt(1); }
    }
}
