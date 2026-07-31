package org.example.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseConnectionFactory {
    private final String databaseUrl;

    public DatabaseConnectionFactory(String databasePath) {
        this.databaseUrl = "jdbc:sqlite:" + databasePath;
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }
}
