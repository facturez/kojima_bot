package org.example.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class GuildConfigRepository {
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");
    public static final String DEFAULT_PREFIX = "день без сиеги шиянова";
    public static final LocalDate DEFAULT_BASE_DATE = LocalDate.of(2026, 7, 29);
    public static final int DEFAULT_BASE_DAY = 107;
    private final DatabaseConnectionFactory connections;

    public GuildConfigRepository(String databasePath) {
        this(new DatabaseConnectionFactory(databasePath));
    }

    public GuildConfigRepository(DatabaseConnectionFactory connections) {
        this.connections = connections;
        new DatabaseMigrator(connections).migrate();
    }

    public void activateGuild(String guildId, String guildName) {
        String now = Instant.now().toString();
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        INSERT INTO guilds(guild_id,guild_name,active,timezone,archive_enabled,created_at,updated_at)
                        VALUES(?,?,1,?,0,?,?)
                        ON CONFLICT(guild_id) DO UPDATE SET guild_name=excluded.guild_name,active=1,updated_at=excluded.updated_at
                        """, guildId, nonblank(guildName, guildId), DEFAULT_ZONE.getId(), now, now);
                execute(connection, """
                        INSERT OR IGNORE INTO daily_message_settings
                        (guild_id,enabled,channel_id,timezone,message_prefix,base_date,base_day_number)
                        VALUES(?,0,NULL,?,?,?,?)
                        """, guildId, DEFAULT_ZONE.getId(), DEFAULT_PREFIX, DEFAULT_BASE_DATE.toString(), DEFAULT_BASE_DAY);
                execute(connection, "INSERT OR IGNORE INTO call_settings(guild_id,enabled,message_text,repeat_count) VALUES(?,0,'',3)", guildId);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw databaseFailure("activate guild", failure);
        }
    }

    public void deactivateGuild(String guildId) {
        update("UPDATE guilds SET active=0,updated_at=? WHERE guild_id=?", Instant.now().toString(), guildId);
    }

    public Optional<GuildConfig> findGuild(String guildId) {
        String sql = """
                SELECT g.guild_id,g.guild_name,g.active,g.archive_enabled,
                       d.enabled daily_enabled,d.channel_id,d.timezone,d.message_prefix,d.base_date,d.base_day_number,
                       c.enabled call_enabled,c.message_text,c.repeat_count
                FROM guilds g JOIN daily_message_settings d ON d.guild_id=g.guild_id
                JOIN call_settings c ON c.guild_id=g.guild_id WHERE g.guild_id=?
                """;
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, guildId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                DailyMessageSettings daily = new DailyMessageSettings(guildId, result.getBoolean("daily_enabled"),
                        result.getString("channel_id"), ZoneId.of(result.getString("timezone")),
                        result.getString("message_prefix"), LocalDate.parse(result.getString("base_date")),
                        result.getInt("base_day_number"));
                CallSettings call = new CallSettings(guildId, result.getBoolean("call_enabled"),
                        result.getString("message_text"), result.getInt("repeat_count"), allowedRoles(connection, guildId));
                return Optional.of(new GuildConfig(guildId, result.getString("guild_name"), result.getBoolean("active"),
                        result.getBoolean("archive_enabled"), daily, call));
            }
        } catch (SQLException failure) {
            throw databaseFailure("read guild", failure);
        }
    }

    public GuildConfig requireConfig(String guildId) {
        return findGuild(guildId).orElseThrow(() -> new IllegalStateException("Guild is not configured: " + guildId));
    }

    public boolean isArchiveEnabled(String guildId) {
        return findGuild(guildId).map(GuildConfig::archiveEnabled).orElse(false);
    }

    public List<DailyMessageSettings> findActiveDailySettings() {
        String sql = """
                SELECT d.guild_id,d.enabled,d.channel_id,d.timezone,d.message_prefix,d.base_date,d.base_day_number
                FROM daily_message_settings d JOIN guilds g ON g.guild_id=d.guild_id
                WHERE g.active=1 AND d.enabled=1
                """;
        List<DailyMessageSettings> settings = new ArrayList<>();
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) settings.add(readDaily(result));
            return settings;
        } catch (SQLException failure) {
            throw databaseFailure("list daily settings", failure);
        }
    }

    public void updateDaily(String guildId, DailySettingsPatch patch) {
        DailyMessageSettings old = requireConfig(guildId).daily();
        boolean enabled = patch.enabled().orElse(old.enabled());
        String channel = patch.channelId().orElse(old.channelId());
        if (enabled && (channel == null || channel.isBlank())) throw new IllegalArgumentException("Daily channel is required when enabled");
        update("""
                UPDATE daily_message_settings SET enabled=?,channel_id=?,timezone=?,message_prefix=?,base_date=?,base_day_number=?
                WHERE guild_id=?
                """, enabled, channel, patch.timezone().orElse(old.timezone()).getId(),
                nonblank(patch.messagePrefix().orElse(old.messagePrefix()), old.messagePrefix()),
                patch.baseDate().orElse(old.baseDate()).toString(), patch.baseDayNumber().orElse(old.baseDayNumber()), guildId);
        patch.timezone().ifPresent(zone -> update("UPDATE guilds SET timezone=?,updated_at=? WHERE guild_id=?",
                zone.getId(), Instant.now().toString(), guildId));
    }

    public void updateCall(String guildId, CallSettingsPatch patch) {
        CallSettings old = requireConfig(guildId).call();
        boolean enabled = patch.enabled().orElse(old.enabled());
        String message = patch.messageText().orElse(old.messageText());
        int repeat = patch.repeatCount().orElse(old.repeatCount());
        if (repeat < 1 || repeat > 10) throw new IllegalArgumentException("Repeat count must be between 1 and 10");
        if (enabled && message.isBlank()) throw new IllegalArgumentException("Call message is required when enabled");
        update("UPDATE call_settings SET enabled=?,message_text=?,repeat_count=? WHERE guild_id=?",
                enabled, message, repeat, guildId);
    }

    public void setArchiveEnabled(String guildId, boolean enabled) {
        update("UPDATE guilds SET archive_enabled=?,updated_at=? WHERE guild_id=?", enabled, Instant.now().toString(), guildId);
    }

    public void addAllowedRole(String guildId, String roleId) {
        update("INSERT OR IGNORE INTO call_allowed_roles(guild_id,role_id) VALUES(?,?)", guildId, roleId);
    }

    public void removeAllowedRole(String guildId, String roleId) {
        update("DELETE FROM call_allowed_roles WHERE guild_id=? AND role_id=?", guildId, roleId);
    }

    public void clearAllowedRoles(String guildId) {
        update("DELETE FROM call_allowed_roles WHERE guild_id=?", guildId);
    }

    public Optional<LocalDate> getLastDailyMessageDate(String guildId) {
        String sql = "SELECT last_sent_date FROM daily_message_state WHERE guild_id=?";
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, guildId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getString(1) == null) return Optional.empty();
                return Optional.of(LocalDate.parse(result.getString(1)));
            }
        } catch (SQLException failure) {
            throw databaseFailure("read daily state", failure);
        }
    }

    public void setLastDailyMessageDate(String guildId, LocalDate date) {
        update("""
                INSERT INTO daily_message_state(guild_id,last_sent_date) VALUES(?,?)
                ON CONFLICT(guild_id) DO UPDATE SET last_sent_date=excluded.last_sent_date
                """, guildId, date.toString());
    }

    public synchronized boolean bootstrapLegacy(String guildId, String guildName, LegacyGuildConfig legacy) {
        String marker = "legacy-config-v1:" + guildId;
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                if (exists(connection, "SELECT 1 FROM migration_markers WHERE marker=?", marker)) {
                    backfillLegacyDailyState(connection, guildId);
                    connection.commit();
                    return false;
                }
                String now = Instant.now().toString();
                boolean dailyEnabled = legacy.dailyChannelId() != null && !legacy.dailyChannelId().isBlank()
                        && !legacy.dailyChannelId().equals("PASTE_CHANNEL_ID_HERE");
                boolean callEnabled = legacy.callMessage() != null && !legacy.callMessage().isBlank() && !legacy.callMessage().contains("!зов");
                execute(connection, """
                        INSERT INTO guilds(guild_id,guild_name,active,timezone,archive_enabled,created_at,updated_at)
                        VALUES(?,?,1,?,1,?,?)
                        ON CONFLICT(guild_id) DO UPDATE SET guild_name=excluded.guild_name,active=1,
                        timezone=excluded.timezone,archive_enabled=1,updated_at=excluded.updated_at
                        """, guildId, nonblank(guildName, guildId), legacy.timezone().getId(), now, now);
                execute(connection, """
                        INSERT INTO daily_message_settings(guild_id,enabled,channel_id,timezone,message_prefix,base_date,base_day_number)
                        VALUES(?,?,?,?,?,?,?) ON CONFLICT(guild_id) DO UPDATE SET enabled=excluded.enabled,
                        channel_id=excluded.channel_id,timezone=excluded.timezone,message_prefix=excluded.message_prefix,
                        base_date=excluded.base_date,base_day_number=excluded.base_day_number
                        """, guildId, dailyEnabled, legacy.dailyChannelId(), legacy.timezone().getId(),
                        legacy.messagePrefix(), legacy.baseDate().toString(), legacy.baseDayNumber());
                execute(connection, """
                        INSERT INTO call_settings(guild_id,enabled,message_text,repeat_count) VALUES(?,?,?,?)
                        ON CONFLICT(guild_id) DO UPDATE SET enabled=excluded.enabled,message_text=excluded.message_text,
                        repeat_count=excluded.repeat_count
                        """, guildId, callEnabled, legacy.callMessage(), legacy.callRepeatCount());
                backfillLegacyDailyState(connection, guildId);
                for (String role : legacy.allowedRoleIds())
                    execute(connection, "INSERT OR IGNORE INTO call_allowed_roles(guild_id,role_id) VALUES(?,?)", guildId, role);
                execute(connection, "INSERT INTO migration_markers(marker,completed_at) VALUES(?,?)", marker, now);
                connection.commit();
                return true;
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw databaseFailure("bootstrap legacy guild", failure);
        }
    }

    private void backfillLegacyDailyState(Connection connection, String guildId) throws SQLException {
        String marker = "legacy-daily-state-v1:" + guildId;
        if (exists(connection, "SELECT 1 FROM migration_markers WHERE marker=?", marker)
                || !exists(connection, "SELECT 1 FROM scheduler_state WHERE key='daily_message_last_sent_date'")) {
            return;
        }
        execute(connection, """
                INSERT INTO daily_message_state(guild_id,last_sent_date)
                SELECT ?,value FROM scheduler_state WHERE key='daily_message_last_sent_date'
                ON CONFLICT(guild_id) DO NOTHING
                """, guildId);
        execute(connection, "INSERT INTO migration_markers(marker,completed_at) VALUES(?,?)",
                marker, Instant.now().toString());
    }

    private DailyMessageSettings readDaily(ResultSet result) throws SQLException {
        return new DailyMessageSettings(result.getString("guild_id"), result.getBoolean("enabled"), result.getString("channel_id"),
                ZoneId.of(result.getString("timezone")), result.getString("message_prefix"),
                LocalDate.parse(result.getString("base_date")), result.getInt("base_day_number"));
    }

    private Set<String> allowedRoles(Connection connection, String guildId) throws SQLException {
        Set<String> roles = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT role_id FROM call_allowed_roles WHERE guild_id=?")) {
            statement.setString(1, guildId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) roles.add(result.getString(1));
            }
        }
        return Set.copyOf(roles);
    }

    private boolean exists(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private void update(String sql, Object... values) {
        try (Connection connection = connections.open()) { execute(connection, sql, values); }
        catch (SQLException failure) { throw databaseFailure("update guild configuration", failure); }
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    private static String nonblank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static IllegalStateException databaseFailure(String action, SQLException failure) {
        return new IllegalStateException("Failed to " + action, failure);
    }
}
