package io.github.nameof.schemaloom.driver;

import java.sql.Connection;

public interface ConnectionProvider extends AutoCloseable {
    Connection getConnection();

    @Override
    void close();
}
