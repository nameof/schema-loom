package io.github.nameof.schemaloom.target;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import org.junit.Test;

import java.sql.*;
import java.util.*;

import static org.junit.Assert.*;

public class JdbcTableTargetContractTest {
    @Test public void acceptsSafeAppendAndAllowsNullableExtraColumn() throws Exception {
        JdbcTableTarget target = target("safe", "CREATE TABLE orders (id INT NOT NULL, name VARCHAR(100), created_at TIMESTAMP)");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT16, false, null, null, null),
                new FieldSchema("name", LogicalType.STRING, true, 50, null, null)), TargetMode.APPEND);
        target.close();
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsNullableSourceForNotNullTarget() throws Exception {
        JdbcTableTarget target = target("nullable", "CREATE TABLE orders (name VARCHAR(100) NOT NULL)");
        target.prepare(schema(new FieldSchema("name", LogicalType.STRING, true, 50, null, null)), TargetMode.APPEND);
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsNarrowTargetString() throws Exception {
        JdbcTableTarget target = target("narrow", "CREATE TABLE orders (name VARCHAR(10))");
        target.prepare(schema(new FieldSchema("name", LogicalType.STRING, true, 20, null, null)), TargetMode.APPEND);
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsRequiredExtraTargetColumn() throws Exception {
        JdbcTableTarget target = target("required", "CREATE TABLE orders (id INT NOT NULL, required VARCHAR(10) NOT NULL)");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), TargetMode.APPEND);
    }

    @Test public void replaceDropsAndCreatesExistingTable() throws Exception {
        JdbcTableTarget target = target("replace", "CREATE TABLE orders (old_value VARCHAR(10))");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), TargetMode.REPLACE);
        target.close();
    }

    @Test(expected = SchemaLoomException.class)
    public void rejectsExistingViewAsTarget() throws Exception {
        JdbcTableTarget target = target("view_target", "CREATE VIEW orders AS SELECT 1 AS id");
        target.prepare(schema(new FieldSchema("id", LogicalType.INT32, false, null, null, null)), TargetMode.APPEND);
    }

    private JdbcTableTarget target(String database, String ddl) throws SQLException {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        Statement statement = connection.createStatement();
        statement.execute(ddl);
        statement.close();
        return new JdbcTableTarget(new ConnectionProvider() {
            public Connection getConnection() { return connection; }
            public void close() { try { connection.close(); } catch (SQLException ignored) { } }
        }, new QualifiedTableName(null, null, "ORDERS"), DatabaseType.MYSQL);
    }

    private SchemaDescriptor schema(FieldSchema... fields) {
        return SchemaDescriptor.of(new RecordSchema(Arrays.asList(fields)));
    }
}
