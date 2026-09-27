package io.github.nameof.schemaloom.execution;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
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
        try {
            return jdbc.query(statement(sql, params, fetchSize, maxRows), handler::extractData);
        } catch (DataAccessException e) {
            throw failed(stage, e);
        } catch (RuntimeException e) {
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
        try {
            jdbc.execute(sql);
        } catch (DataAccessException e) {
            throw failed(stage, e);
        } catch (RuntimeException e) {
            throw convert(stage, e);
        }
    }

    /** 每次调用对应一个短事务；绑定和回调的运行时异常同样会触发回滚。 */
    public int[] batchUpdate(final String stage, final String sql, final BatchSetter setter) {
        try {
            return transaction.execute(status -> {
                int[] counts = jdbc.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    public void setValues(PreparedStatement statement, int row) throws SQLException {
                        setter.setValues(statement, row);
                    }

                    public int getBatchSize() { return setter.getBatchSize(); }
                });
                verifyBatchCounts(counts);
                return counts;
            });
        } catch (DataAccessException e) {
            throw failed(stage, e);
        } catch (RuntimeException e) {
            if (e instanceof SchemaLoomException) throw e;
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
