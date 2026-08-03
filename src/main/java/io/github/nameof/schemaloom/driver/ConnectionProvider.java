package io.github.nameof.schemaloom.driver;

import java.sql.Connection;

/**
 * 在 provider 生命周期内持有一个 JDBC 连接。
 * 这不是连接工厂，也不是连接池；多次调用 getConnection() 返回同一个连接，
 * 因此不同并发任务不能共享同一个 provider，除非自行串行化所有 JDBC 操作。
 */
public interface ConnectionProvider extends AutoCloseable {
    /**
     * 返回 provider 所持有的连接，必要时延迟创建。不要直接关闭返回的连接，
     * 应关闭 provider，以便完成 provider 自身的引用计数和资源清理。
     */
    Connection getConnection();

    @Override
    /** 关闭所持有的连接，并释放 provider 相关资源。 */
    void close();
}
