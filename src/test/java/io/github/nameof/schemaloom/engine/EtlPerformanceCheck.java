package io.github.nameof.schemaloom.engine;

import io.github.nameof.schemaloom.api.*;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;

/**
 * 独立性能 Profile 使用的批处理检查，不参加默认单元测试。
 * 通过生成式 Source 和计数 Target 验证内存不会保存全部输入记录。
 */
public class EtlPerformanceCheck {
    @Test(timeout = 120000)
    public void processesMillionRowsInBatches() {
        final int total = 1_000_000;
        final RecordSchema schema = new RecordSchema(Collections.singletonList(FieldSchema.of("id", LogicalType.INT64)));
        CountingTarget target = new CountingTarget();
        EtlResult result = EtlTask.builder()
                .source(new GeneratedSource(schema, total, 1000))
                .target(target)
                .batchSize(1000)
                .build()
                .run();
        assertEquals(EtlStatus.SUCCESS, result.getStatus());
        assertEquals(total, result.getRead());
        assertEquals(total, target.count);
    }

    private static final class GeneratedSource implements Source {
        private final RecordSchema schema;
        private final int total, batchSize;

        GeneratedSource(RecordSchema schema, int total, int batchSize) {
            this.schema = schema;
            this.total = total;
            this.batchSize = batchSize;
        }

        public RecordSchema schema() { return schema; }

        public void read(BatchConsumer consumer) {
            for (int offset = 0; offset < total; offset += batchSize) {
                int size = Math.min(batchSize, total - offset);
                List<DataRecord> records = new ArrayList<DataRecord>(size);
                for (int i = 0; i < size; i++)
                    records.add(new DataRecord(schema, Collections.<Object>singletonList((long) offset + i)));
                consumer.accept(new RecordBatch(schema, records));
            }
        }

        public void close() { }
    }

    private static final class CountingTarget implements Target {
        int count;

        public void prepare(RecordSchema schema, TargetMode mode) { }

        public BatchWriteResult write(RecordBatch batch) {
            count += batch.size();
            return new BatchWriteResult(batch.size(), 0);
        }

        public void close() { }
    }
}
