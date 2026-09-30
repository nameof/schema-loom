package io.github.nameof.schemaloom.execution;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.internal.LoggingSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

/**
 * JDBC 执行层内部适配器：统一语句释放、参数绑定、异常转换和批次事务。
 * 不拥有 ConnectionProvider 或其连接的生命周期。
 */
public final class JdbcExecutionAdapter {
    private static final Logger log = LoggerFactory.getLogger(JdbcExecutionAdapter.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcExecutionAdapter(ConnectionProvider provider) {
        if (provider == null) throw new IllegalArgumentException("connection provider is required");
        Connection connection = provider.getConnection();
        // suppressClose=true：Spring 只管理语句、结果集和事务，不关闭Connection
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public <T> T query(String stage, String sql, List<Object> params, int fetchSize, Integer maxRows,
                       ResultSetHandler<T> handler) {
        log.debug("JDBC查询开始 runId={} stage={} parameterCount={} fetchSize={} maxRows={}",
                LoggingSupport.currentRunId(), stage, params == null ? 0 : params.size(), fetchSize, maxRows);
        try {
            T result = jdbc.query(statement(sql, params, fetchSize, maxRows), handler::extractData);
            log.debug("JDBC查询完成 runId={} stage={}", LoggingSupport.currentRunId(), stage);
            return result;
        } catch (DataAccessException e) {
            log.warn("JDBC查询失败 runId={} stage={} message={}", LoggingSupport.currentRunId(), stage, LoggingSupport.message(e));
            throw failed(stage, e);
        } catch (RuntimeException e) {
            log.warn("JDBC查询失败 runId={} stage={} message={}", LoggingSupport.currentRunId(), stage, LoggingSupport.message(e));
            throw convert(stage, e);
        }
    }

    public long queryForLong(String stage, String sql) {
        Long value = query(stage, sql, Collections.<Object>emptyList(), 0, null, new ResultSetHandler<Long>() {
            public Long extractData(ResultSet resultSet) throws SQLException {
                if (!resultSet.next()) throw new SQLException("query returned no row");
                return resultSet.getLong(1);
            }
        });
        return value == null ? 0L : value;
    }

    public void execute(String stage, String sql) {
        log.debug("JDBC执行开始 runId={} stage={}", LoggingSupport.currentRunId(), stage);
        try {
            jdbc.execute(sql);
            log.debug("JDBC执行完成 runId={} stage={}", LoggingSupport.currentRunId(), stage);
        } catch (DataAccessException e) {
            log.warn("JDBC执行失败 runId={} stage={} message={}", LoggingSupport.currentRunId(), stage, LoggingSupport.message(e));
            throw failed(stage, e);
        } catch (RuntimeException e) {
            log.warn("JDBC执行失败 runId={} stage={} message={}", LoggingSupport.currentRunId(), stage, LoggingSupport.message(e));
            throw convert(stage, e);
        }
    }

    /** 每次调用对应一个短事务；绑定和回调的运行时异常同样会触发回滚。 */
    public int[] batchUpdate(final String stage, final String sql, final BatchSetter setter) {
        log.debug("JDBC批量写入开始 runId={} stage={} batchSize={}", LoggingSupport.currentRunId(), stage, setter.getBatchSize());
        try {
            int[] resultCounts = transaction.execute(status -> {
                int[] counts = jdbc.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    public void setValues(PreparedStatement statement, int row) throws SQLException {
                        setter.setValues(statement, row);
                    }

                    public int getBatchSize() { return setter.getBatchSize(); }
                });
                verifyBatchCounts(counts);
                return counts;
            });
            log.debug("JDBC批量写入完成 runId={} stage={} rows={}",
                    LoggingSupport.currentRunId(), stage, resultCounts == null ? 0 : resultCounts.length);
            return resultCounts;
        } catch (DataAccessException e) {
            log.warn("JDBC批量写入失败 runId={} stage={} message={}", LoggingSupport.currentRunId(), stage, LoggingSupport.message(e));
            throw failed(stage, e);
        } catch (RuntimeException e) {
            if (e instanceof SchemaLoomException) throw e;
            log.warn("JDBC批量写入失败 runId={} stage={} message={}", LoggingSupport.currentRunId(), stage, LoggingSupport.message(e));
            throw failed(stage, e);
        }
    }

    private PreparedStatementCreator statement(final String sql, final List<Object> params, final int fetchSize,
                                                final Integer maxRows) {
        return connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);
            try {
                if (fetchSize > 0) statement.setFetchSize(fetchSize);
                if (maxRows != null) statement.setMaxRows(maxRows);
                List<Object> values = params == null ? Collections.emptyList() : params;
                for (int i = 0; i < values.size(); i++)
                    statement.setObject(i + 1, values.get(i));
                return statement;
            } catch (SQLException | RuntimeException e) {
                try {
                    statement.close();
                } catch (SQLException closeError) {
                    e.addSuppressed(closeError);
                }
                throw e;
            }
        };
    }

    private void verifyBatchCounts(int[] counts) {
        if (counts == null) throw new SchemaLoomException("JDBC batch returned no update counts");
        for (int count : counts) {
            if (count == Statement.EXECUTE_FAILED)
                throw new SchemaLoomException("JDBC batch contains a failed row");
        }
    }

    private SchemaLoomException failed(String stage, Throwable cause) {
        return new SchemaLoomException("JDBC " + stage + " failed", cause);
    }

    private SchemaLoomException convert(String stage, RuntimeException cause) {
        return cause instanceof SchemaLoomException ? (SchemaLoomException) cause : failed(stage, cause);
    }

    /** 执行层回调契约，避免 Spring JDBC 类型进入调用方。 */
    public interface ResultSetHandler<T> {
        T extractData(ResultSet resultSet) throws SQLException;
    }

    /** 执行层批处理契约，避免 Spring JDBC 类型进入调用方。 */
    public interface BatchSetter {
        void setValues(PreparedStatement statement, int row) throws SQLException;
        int getBatchSize();
    }
}
