package io.github.nameof.schemaloom.source;

import io.github.nameof.schemaloom.api.DataRecord;
import io.github.nameof.schemaloom.api.EtlResult;
import io.github.nameof.schemaloom.api.RecordBatch;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.target.MemoryTarget;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class JdbcLargeFieldPolicyTest {
    @Test
    public void defaultsSkipBlobAndCopyClobAndReportFieldStatistics() throws Exception {
        ConnectionProvider provider = provider("default");
        setup(provider.getConnection());
        JdbcQuerySource source = new JdbcQuerySource(provider, "SELECT id, content, attachment, note FROM documents",
                Collections.<Object>emptyList(), 10);
        List<DataRecord> rows = read(source);

        assertEquals(1, rows.size());
        assertEquals("large text", rows.get(0).get("CONTENT"));
        assertNull(rows.get(0).get("ATTACHMENT"));
        assertEquals("short", rows.get(0).get("NOTE"));
        assertEquals(1, source.getReadStatistics().getReadRows());
        assertEquals(Long.valueOf(1), source.getReadStatistics().getSkippedFieldRows().get("ATTACHMENT"));
        source.close();
    }

    @Test
    public void fieldActionOverridesTypeAndThresholdWritesNull() throws Exception {
        ConnectionProvider provider = provider("override");
        setup(provider.getConnection());
        LargeFieldPolicy policy = LargeFieldPolicy.builder().typeName("CLOB", LargeFieldPolicy.Action.COPY)
                .field("note", LargeFieldPolicy.Action.SKIP).maxTextChars(4).build();
        JdbcQuerySource source = new JdbcQuerySource(provider, "SELECT id, content, attachment, note FROM documents",
                Collections.<Object>emptyList(), 10, policy);
        List<DataRecord> rows = read(source);

        assertNull(rows.get(0).get("CONTENT"));
        assertNull(rows.get(0).get("NOTE"));
        assertArrayEquals(new byte[] {1, 2}, (byte[]) rows.get(0).get("ATTACHMENT"));
        assertEquals(Long.valueOf(1), source.getReadStatistics().getSkippedFieldRows().get("CONTENT"));
        assertEquals(Long.valueOf(1), source.getReadStatistics().getSkippedFieldRows().get("NOTE"));
        source.close();
    }

    @Test
    public void skipLargeFieldsSkipsBinaryAndLongTextButCopiesShortText() throws Exception {
        ConnectionProvider provider = provider("skip-large-fields");
        setup(provider.getConnection());
        LargeFieldPolicy policy = LargeFieldPolicy.skipAllLargeFields();
        JdbcQuerySource source = new JdbcQuerySource(provider, "SELECT id, content, attachment, note FROM documents",
                Collections.<Object>emptyList(), 10, policy);
        List<DataRecord> rows = read(source);

        assertNull(rows.get(0).get("CONTENT"));
        assertNull(rows.get(0).get("ATTACHMENT"));
        assertEquals("short", rows.get(0).get("NOTE"));
        source.close();
    }

    @Test
    public void etlResultCollectsReadStatistics() throws Exception {
        ConnectionProvider provider = provider("result");
        setup(provider.getConnection());
        JdbcQuerySource source = new JdbcQuerySource(provider, "SELECT id, content, attachment, note FROM documents",
                Collections.<Object>emptyList(), 10);
        EtlResult result = EtlTask.builder().source(source).target(new MemoryTarget()).build().run();

        assertEquals(1, result.getReadStatistics().getReadRows());
        assertEquals(Long.valueOf(1), result.getReadStatistics().getSkippedFieldRows().get("ATTACHMENT"));
        assertEquals(1, result.getWriteStatistics().getWrittenRows());
    }

    private static List<DataRecord> read(JdbcQuerySource source) {
        List<DataRecord> rows = new ArrayList<DataRecord>();
        source.read((RecordBatch batch) -> rows.addAll(batch.getRecords()));
        return rows;
    }

    private static ConnectionProvider provider(String name) throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:lob_" + name + ";DB_CLOSE_DELAY=-1");
        return new ConnectionProvider() {
            public Connection getConnection() { return connection; }
            public void close() {
                try { connection.close(); } catch (Exception e) { throw new IllegalStateException(e); }
            }
        };
    }

    private static void setup(Connection connection) throws Exception {
        Statement statement = connection.createStatement();
        statement.execute("CREATE TABLE documents (id INT PRIMARY KEY, content CLOB, attachment BLOB, note VARCHAR(20))");
        statement.close();
        PreparedStatement insert = connection.prepareStatement("INSERT INTO documents VALUES (?, ?, ?, ?)");
        insert.setInt(1, 1);
        insert.setString(2, "large text");
        insert.setBytes(3, new byte[] {1, 2});
        insert.setString(4, "short");
        insert.executeUpdate();
        insert.close();
    }
}
