package org.example.db;

import net.dv8tion.jda.api.entities.Message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MessageRepository {
    private final DatabaseConnectionFactory connections;
    private final int retentionDays;
    private final Clock clock;

    public MessageRepository(String databasePath) {
        this(databasePath, 30, Clock.systemUTC());
    }

    public MessageRepository(String databasePath, int retentionDays, Clock clock) {
        this.connections = new DatabaseConnectionFactory(databasePath);
        this.retentionDays = retentionDays;
        this.clock = clock;
        new DatabaseMigrator(connections).migrate();
        deleteExpiredMessages();
    }

    public void saveGuildMessage(String guildId, Message message) {
        if (!message.isFromGuild() || !message.getGuild().getId().equals(guildId)) {
            throw new IllegalArgumentException("Message does not belong to guild " + guildId);
        }
        saveStoredMessage(
                message.getId(),
                message.getChannel().getId(),
                guildId,
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

    public void deleteMessage(String guildId, String messageId) {
        deleteMessages(guildId, List.of(messageId));
    }

    public void deleteMessages(String guildId, Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }

        String sql = "DELETE FROM messages WHERE guild_id = ? AND message_id = ?";
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (String messageId : messageIds) {
                    statement.setString(1, guildId);
                    statement.setString(2, messageId);
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

    public long countMessages(String guildId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE guild_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, guildId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count messages", e);
        }
    }

    public long countMessagesByAuthor(String guildId, String authorId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE guild_id = ? AND author_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, guildId);
            statement.setString(2, authorId);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count messages for author", e);
        }
    }

    public List<StoredMessage> findRecentMessages(String guildId, String channelId, int limit) {
        deleteExpiredMessages();

        String sql = """
                SELECT author_tag, content, created_at
                FROM messages
                WHERE guild_id = ? AND channel_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;

        List<StoredMessage> messages = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, guildId);
            statement.setString(2, channelId);
            statement.setInt(3, limit);

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

    private Connection openConnection() throws SQLException {
        return connections.open();
    }
}
