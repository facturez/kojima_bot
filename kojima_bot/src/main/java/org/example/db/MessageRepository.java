package org.example.db;

import net.dv8tion.jda.api.entities.Message;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class MessageRepository {
    private final String databaseUrl;

    public MessageRepository(String databasePath) {
        this.databaseUrl = "jdbc:sqlite:" + databasePath;
        initialize();
    }

    public void saveMessage(Message message) {
        String sql = """
                INSERT OR IGNORE INTO messages (
                    message_id, channel_id, guild_id, author_id, author_tag, content, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, message.getId());
            statement.setString(2, message.getChannel().getId());
            statement.setString(3, message.isFromGuild() ? message.getGuild().getId() : null);
            statement.setString(4, message.getAuthor().getId());
            statement.setString(5, message.getAuthor().getAsTag());
            statement.setString(6, message.getContentRaw());
            statement.setString(7, message.getTimeCreated().toInstant().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to save message: " + e.getMessage());
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

    private void initialize() {
        String sql = """
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

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize database", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(databaseUrl);
    }
}
