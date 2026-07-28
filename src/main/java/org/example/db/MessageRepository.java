package org.example.db;

import net.dv8tion.jda.api.entities.Message;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class MessageRepository {
    private final String databaseUrl;
    private final int retentionDays;
    private final Clock clock;

    public MessageRepository(String databasePath) {
        this(databasePath, 30, Clock.systemUTC());
    }

    public MessageRepository(String databasePath, int retentionDays, Clock clock) {
        this.databaseUrl = "jdbc:sqlite:" + databasePath;
        this.retentionDays = retentionDays;
        this.clock = clock;
        initialize();
        deleteExpiredMessages();
    }

    public void saveMessage(Message message) {
        saveStoredMessage(
                message.getId(),
                message.getChannel().getId(),
                message.isFromGuild() ? message.getGuild().getId() : null,
                message.getAuthor().getId(),
                message.getAuthor().getAsTag(),
                message.getContentRaw(),
                message.getTimeCreated().toInstant()
        );
    }

    void saveStoredMessage(
            String messageId,
            String channelId,
            String guildId,
            String authorId,
            String authorTag,
            String content,
            Instant createdAt
    ) {
        String sql = """
                INSERT OR IGNORE INTO messages (
                    message_id, channel_id, guild_id, author_id, author_tag, content, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            statement.setString(2, channelId);
            statement.setString(3, guildId);
            statement.setString(4, authorId);
            statement.setString(5, authorTag);
            statement.setString(6, content);
            statement.setString(7, createdAt.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to save message: " + e.getMessage());
        }
    }

    public void deleteMessage(String messageId) {
        deleteMessages(List.of(messageId));
    }

    public void deleteMessages(Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }

        String sql = "DELETE FROM messages WHERE message_id = ?";
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (String messageId : messageIds) {
                    statement.setString(1, messageId);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete messages", e);
        }
    }

    public int deleteExpiredMessages() {
        String sql = "DELETE FROM messages WHERE created_at < ?";
        Instant cutoff = clock.instant().minus(retentionDays, ChronoUnit.DAYS);

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cutoff.toString());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete expired messages", e);
        }
    }

    public long countMessages() {
        String sql = "SELECT COUNT(*) FROM messages";
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count messages", e);
        }
    }

    public long countMessagesByAuthor(String authorId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE author_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, authorId);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count messages for author", e);
        }
    }

    public List<StoredMessage> findRecentMessages(String channelId, int limit) {
        deleteExpiredMessages();

        String sql = """
                SELECT author_tag, content, created_at
                FROM messages
                WHERE channel_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;

        List<StoredMessage> messages = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, channelId);
            statement.setInt(2, limit);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(new StoredMessage(
                            resultSet.getString("author_tag"),
                            resultSet.getString("content"),
                            Instant.parse(resultSet.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fetch recent messages", e);
        }

        return messages;
    }

    public Optional<LocalDate> getLastDailyMessageDate() {
        String sql = "SELECT value FROM scheduler_state WHERE key = 'daily_message_last_sent_date'";
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return Optional.empty();
            }

            return Optional.of(LocalDate.parse(resultSet.getString("value")));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read daily message state", e);
        }
    }

    public void setLastDailyMessageDate(LocalDate date) {
        String sql = """
                INSERT INTO scheduler_state(key, value)
                VALUES ('daily_message_last_sent_date', ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, date.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save daily message state", e);
        }
    }

    private void initialize() {
        String messagesSql = """
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
                """;
        String schedulerStateSql = """
                CREATE TABLE IF NOT EXISTS scheduler_state (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """;

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(messagesSql);
            statement.execute(schedulerStateSql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize database", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(databaseUrl);
    }
}
