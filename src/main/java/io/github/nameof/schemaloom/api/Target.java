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

    @Override
    void close();
}
