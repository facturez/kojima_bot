package org.example.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseMigrator {
    public static final int CURRENT_VERSION = 2;
    private final DatabaseConnectionFactory connections;

    public DatabaseMigrator(DatabaseConnectionFactory connections) {
        this.connections = connections;
    }

    public void migrate() {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS messages (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            message_id TEXT NOT NULL UNIQUE,
                            channel_id TEXT NOT NULL,
                            guild_id TEXT,
                            author_id TEXT NOT NULL,
                            author_tag TEXT NOT NULL,
                            content TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            stored_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS scheduler_state (
                            key TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS guilds (
                            guild_id TEXT PRIMARY KEY,
                            guild_name TEXT NOT NULL,
                            active INTEGER NOT NULL DEFAULT 1,
                            timezone TEXT NOT NULL DEFAULT 'Europe/Moscow',
                            archive_enabled INTEGER NOT NULL DEFAULT 0,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS daily_message_settings (
                            guild_id TEXT PRIMARY KEY,
                            enabled INTEGER NOT NULL DEFAULT 0,
                            channel_id TEXT,
                            timezone TEXT NOT NULL DEFAULT 'Europe/Moscow',
                            message_prefix TEXT NOT NULL,
                            base_date TEXT NOT NULL,
                            base_day_number INTEGER NOT NULL,
                            FOREIGN KEY(guild_id) REFERENCES guilds(guild_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS daily_message_state (
                            guild_id TEXT PRIMARY KEY,
                            last_sent_date TEXT,
                            FOREIGN KEY(guild_id) REFERENCES guilds(guild_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS call_settings (
                            guild_id TEXT PRIMARY KEY,
                            enabled INTEGER NOT NULL DEFAULT 0,
                            message_text TEXT NOT NULL,
                            repeat_count INTEGER NOT NULL,
                            FOREIGN KEY(guild_id) REFERENCES guilds(guild_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS call_allowed_roles (
                            guild_id TEXT NOT NULL,
                            role_id TEXT NOT NULL,
                            PRIMARY KEY(guild_id, role_id),
                            FOREIGN KEY(guild_id) REFERENCES guilds(guild_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS migration_markers (
                            marker TEXT PRIMARY KEY,
                            completed_at TEXT NOT NULL
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_guild_channel_created ON messages(guild_id, channel_id, created_at)");
                statement.execute("DELETE FROM schema_version");
                statement.execute("INSERT INTO schema_version(version) VALUES (2)");
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to migrate database", failure);
        }
    }

    public int version() {
        try (Connection connection = connections.open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to read schema version", failure);
        }
    }
}
