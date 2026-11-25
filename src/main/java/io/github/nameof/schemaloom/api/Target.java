package io.github.nameof.schemaloom.api;

import java.util.List;

public interface Target extends AutoCloseable {
    /**
     * 准备目标端写入，例如表结构，检查字段兼容、索引、注释等。
     *
     * <p>调用方应在首次 {@link #write(RecordBatch)} 前调用本方法；成功返回后目标才可写入。
     * 返回值只包含准备阶段产生的非数据结构错误，例如索引、注释等迁移错误：
     * 可忽略的错误通过返回值反馈，致命性错误直接抛出异常。
     * 没有错误时必须返回空列表，不得返回 {@code null}。。</p>
     *
     * @param schema 目标字段及可选结构元数据
     * @param mode 目标表处理模式
     * @return 准备阶段被忽略的错误，始终非 {@code null}
     */
    List<EtlError> prepare(SchemaDescriptor schema, TargetMode mode);

    BatchWriteResult write(RecordBatch batch);

    /**
     * 关闭并指定本次写入数据是否提交。
     *
     * <p>commit 是唯一提交决策点：为 true 时实现必须无条件完成提交；为 false 只做
     * 资源清理，保留已有数据。不得依据内部失败状态否决显式传入的 commit；</p>
     *
     * <p>无提交语义的实现可以忽略 commit。</p>
     */
    void close(boolean commit);

    @Override
    void close();
}
