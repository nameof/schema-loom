package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.DataRecord;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class JdbcQuerySourcePreviewTest {
    @Test
    public void setMaxRowsLimitsPreviewResult() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:preview;DB_CLOSE_DELAY=-1");
        connection.createStatement().execute("CREATE TABLE orders (id INT)");
        connection.createStatement().execute("INSERT INTO orders VALUES (1), (2), (3)");
        ConnectionProvider provider = new ConnectionProvider() {
            public Connection getConnection() { return connection; }
            public void close() { try { connection.close(); } catch (Exception ignored) { } }
        };
        JdbcQuerySource source = new JdbcQuerySource(provider, "SELECT id FROM orders", Collections.emptyList(), 100);

        List<DataRecord> records = source.readRows(2);

        assertEquals(2, records.size());
        source.close();
    }

    @Test
    public void previewDoesNotMaterializeBinaryColumn() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:preview-binary;DB_CLOSE_DELAY=-1");
        connection.createStatement().execute("CREATE TABLE documents (id INT, content BINARY(2))");
        connection.createStatement().execute("INSERT INTO documents VALUES (1, X'0102')");
        ConnectionProvider provider = new ConnectionProvider() {
            public Connection getConnection() { return connection; }
            public void close() { try { connection.close(); } catch (Exception ignored) { } }
        };
        JdbcQuerySource source = new JdbcQuerySource(provider, "SELECT id, content FROM documents", Collections.emptyList(), 100);

        DataRecord record = source.readRows(1).get(0);

        assertEquals(1, record.get("ID"));
        assertNull(record.get("CONTENT"));
        source.close();
    }
}
