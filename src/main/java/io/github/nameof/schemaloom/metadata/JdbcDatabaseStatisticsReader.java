package io.github.nameof.schemaloom.metadata;

import cn.hutool.core.util.StrUtil;
import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/** 包内统计读取器：只负责方言查询和结果映射，不拥有连接生命周期。 */
final class JdbcDatabaseStatisticsReader {
    private static final Logger log = LoggerFactory.getLogger(JdbcDatabaseStatisticsReader.class);
    private final JdbcTemplate jdbc;
    private final String product;
    private final Connection connection;

    JdbcDatabaseStatisticsReader(ConnectionProvider provider) {
        this.connection = provider.getConnection();
        this.jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        try {
            this.product = connection.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            throw new SchemaLoomException("无法识别数据库产品", e);
        }
    }

    TableStatistics table(QualifiedTableName name) {
        if (StrUtil.containsIgnoreCase(product, "mysql")) return mysqlTable(name);
        if (StrUtil.containsIgnoreCase(product, "oracle")) return oracleTable(name);
        if (StrUtil.containsIgnoreCase(product, "sql server") || StrUtil.containsIgnoreCase(product, "sqlserver")) return sqlServerTable(name);
        if (StrUtil.containsIgnoreCase(product, "postgresql")) return postgresqlTable(name);
        throw unsupported();
    }

    SchemaStatistics schema(SchemaInfo name) {
        if (StrUtil.containsIgnoreCase(product, "mysql")) return mysqlSchema(name);
        if (StrUtil.containsIgnoreCase(product, "oracle")) return oracleSchema(name);
        if (StrUtil.containsIgnoreCase(product, "sql server") || StrUtil.containsIgnoreCase(product, "sqlserver")) return sqlServerSchema(name);
        if (StrUtil.containsIgnoreCase(product, "postgresql")) return postgresqlSchema(name);
        throw unsupported();
    }

    private TableStatistics mysqlTable(QualifiedTableName name) {
        String schema = first(name.getCatalog(), name.getSchema(), currentCatalog());
        Map<String, Object> row = one("SELECT TABLE_ROWS row_count, DATA_LENGTH data_length, INDEX_LENGTH index_length, AVG_ROW_LENGTH avg_row_length "
                + "FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND TABLE_TYPE = 'BASE TABLE'", schema, name.getTable());
        return new TableStatistics(number(row, "row_count"), number(row, "data_length"), number(row, "index_length"), number(row, "avg_row_length"));
    }

    private SchemaStatistics mysqlSchema(SchemaInfo name) {
        String schema = first(name.getCatalog(), name.getName(), currentCatalog());
        Map<String, Object> row = oneAggregate("SELECT COUNT(*) total_tables, COALESCE(SUM(TABLE_ROWS), 0) total_rows, "
                + "COALESCE(SUM(DATA_LENGTH), 0) total_data_length FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'", schema);
        return new SchemaStatistics(number(row, "total_tables"), number(row, "total_rows"), number(row, "total_data_length"));
    }

    private TableStatistics oracleTable(QualifiedTableName name) {
        String owner = first(name.getSchema(), currentSchema());
        Map<String, Object> base = one("SELECT NUM_ROWS row_count, AVG_ROW_LEN avg_row_length FROM ALL_TABLES "
                + "WHERE OWNER = ? AND TABLE_NAME = ?", owner, name.getTable());
        long data = 0, index = 0;
        try {
            Map<String, Object> bytes = oneAggregate("SELECT COALESCE(SUM(CASE WHEN segment_type LIKE 'TABLE%' OR segment_type LIKE 'LOB%' THEN bytes ELSE 0 END), 0) data_length, "
                    + "COALESCE(SUM(CASE WHEN segment_type LIKE 'INDEX%' THEN bytes ELSE 0 END), 0) index_length "
                    + "FROM DBA_SEGMENTS WHERE owner = ? AND (segment_name = ? OR segment_name IN "
                    + "(SELECT index_name FROM ALL_INDEXES WHERE owner = ? AND table_name = ?))", owner, name.getTable(), owner, name.getTable());
            data = number(bytes, "data_length");
            index = number(bytes, "index_length");
        } catch (DataAccessException e) {
            if (!permissionFailure(e)) throw e;
            // DBA_SEGMENTS 可能需要额外权限；容量字段按约定返回 0，基础行统计仍然保留。
            log.warn("Oracle 表容量统计降级 runId={} table={} reason=permission", LoggingSupport.currentRunId(), name.getTable());
        }
        return new TableStatistics(number(base, "row_count"), data, index, number(base, "avg_row_length"));
    }

    private SchemaStatistics oracleSchema(SchemaInfo name) {
        String owner = first(name.getName(), currentSchema());
        Map<String, Object> counts = oneAggregate("SELECT COUNT(*) total_tables, COALESCE(SUM(NUM_ROWS), 0) total_rows "
                + "FROM ALL_TABLES WHERE OWNER = ?", owner);
        long data = 0;
        try {
            Map<String, Object> bytes = oneAggregate("SELECT COALESCE(SUM(bytes), 0) total_data_length FROM DBA_SEGMENTS "
                    + "WHERE owner = ? AND (segment_type LIKE 'TABLE%' OR segment_type LIKE 'LOB%')", owner);
            data = number(bytes, "total_data_length");
        } catch (DataAccessException e) {
            if (!permissionFailure(e)) throw e;
            // DBA_SEGMENTS 无权限时只降级容量，totalTables/totalRows 仍来自 ALL_TABLES。
            log.warn("Oracle Schema 容量统计降级 runId={} reason=permission", LoggingSupport.currentRunId());
        }
        return new SchemaStatistics(number(counts, "total_tables"), number(counts, "total_rows"), data);
    }

    private TableStatistics sqlServerTable(QualifiedTableName name) {
        checkCatalog(name.getCatalog());
        Map<String, Object> row = oneAggregate("SELECT COUNT(DISTINCT t.object_id) table_count, SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.row_count ELSE 0 END) row_count, "
                + "SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.used_page_count ELSE 0 END) * 8192 data_length, "
                + "SUM(CASE WHEN ps.index_id > 1 THEN ps.used_page_count ELSE 0 END) * 8192 index_length "
                + "FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id "
                + "JOIN sys.dm_db_partition_stats ps ON ps.object_id = t.object_id "
                + "WHERE s.name = ? AND t.name = ?", first(name.getSchema(), currentSchema()), name.getTable());
        if (number(row, "table_count") == 0) throw new SchemaLoomException("table not found");
        long rows = number(row, "row_count"), data = number(row, "data_length"), index = number(row, "index_length");
        return new TableStatistics(rows, data, index, rows == 0 ? 0 : data / rows);
    }

    private SchemaStatistics sqlServerSchema(SchemaInfo name) {
        checkCatalog(name.getCatalog());
        String schema = first(name.getName(), currentSchema());
        Map<String, Object> tables = oneAggregate("SELECT COUNT(*) total_tables FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = ?", schema);
        long rows = 0, data = 0;
        try {
            Map<String, Object> stats = oneAggregate("SELECT COALESCE(SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.row_count ELSE 0 END), 0) total_rows, "
                    + "COALESCE(SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.used_page_count ELSE 0 END), 0) * 8192 total_data_length "
                    + "FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id "
                    + "JOIN sys.dm_db_partition_stats ps ON ps.object_id = t.object_id WHERE s.name = ?", schema);
            rows = number(stats, "total_rows");
            data = number(stats, "total_data_length");
        } catch (DataAccessException e) {
            if (!permissionFailure(e)) throw e;
            // DMV 权限不足时返回 0；普通表数量仍可由 sys.tables 提供。
            log.warn("SQL Server Schema 行数与容量统计降级 runId={} reason=permission", LoggingSupport.currentRunId());
        }
        return new SchemaStatistics(number(tables, "total_tables"), rows, data);
    }

    private TableStatistics postgresqlTable(QualifiedTableName name) {
        String schema = first(name.getSchema(), currentSchema(), "public");
        Map<String, Object> row = one("SELECT COALESCE(s.n_live_tup, 0) row_count, pg_relation_size(c.oid) data_length, pg_indexes_size(c.oid) index_length " +
                "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid " +
                "WHERE n.nspname = ? AND c.relname = ? AND c.relkind = 'r'", schema, name.getTable());
        long rows = number(row, "row_count"), data = number(row, "data_length");
        return new TableStatistics(rows, data, number(row, "index_length"), rows == 0 ? 0 : data / rows);
    }

    private SchemaStatistics postgresqlSchema(SchemaInfo name) {
        String schema = first(name.getName(), currentSchema(), "public");
        Map<String, Object> row = oneAggregate("SELECT COUNT(*) total_tables, COALESCE(SUM(s.n_live_tup), 0) total_rows, " +
                "COALESCE(SUM(pg_relation_size(c.oid)), 0) total_data_length FROM pg_class c " +
                "JOIN pg_namespace n ON n.oid = c.relnamespace LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid " +
                "WHERE n.nspname = ? AND c.relkind = 'r'", schema);
        return new SchemaStatistics(number(row, "total_tables"), number(row, "total_rows"), number(row, "total_data_length"));
    }
    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new SchemaLoomException("table not found");
        return rows.get(0);
    }

    private Map<String, Object> oneAggregate(String sql, Object... args) {
        return jdbc.queryForMap(sql, args);
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        // 目录字段可能为 NULL（未收集统计/引擎不提供/权限不足），统一按约定映射为 0。
        return value instanceof Number ? Math.max(0L, ((Number) value).longValue()) : 0L;
    }

    private String currentCatalog() { try { return connection.getCatalog(); } catch (Exception ignored) { return null; } }
    private String currentSchema() { try { return connection.getSchema(); } catch (Exception ignored) { return null; } }
    private String first(String... values) { for (String value : values) if (StrUtil.isNotBlank(value)) return value; throw new IllegalArgumentException("数据库命名空间不能为空"); }
    private void checkCatalog(String catalog) { if (StrUtil.isNotBlank(catalog) && !StrUtil.equalsIgnoreCase(catalog, currentCatalog())) throw new IllegalArgumentException("SQL Server 统计不支持跨 catalog"); }
    private boolean permissionFailure(DataAccessException e) { String message = e.getMostSpecificCause().getMessage(); return message != null && StrUtil.containsAnyIgnoreCase(message, "permission", "insufficient privilege", "ora-00942", "ora-01031", "error 229", "error 297"); }
    private SchemaLoomException unsupported() { return new SchemaLoomException("unsupported database product for statistics: " + product); }
}
