package io.github.nameof.schemaloom.engine;

import cn.hutool.core.bean.BeanUtil;
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.metadata.QualifiedTableName;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import io.github.nameof.schemaloom.source.MemorySource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import io.github.nameof.schemaloom.target.MemoryTarget;
import io.github.nameof.schemaloom.transform.FieldMapping;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class EtlTaskTest {
    private RecordSchema schema() {
        return new RecordSchema(Arrays.asList(FieldSchema.of("id", LogicalType.INT32), FieldSchema.of("name", LogicalType.STRING)));
    }

    private List<DataRecord> rows(RecordSchema s) {
        return Arrays.asList(new DataRecord(s, Arrays.<Object>asList(1, "a")), new DataRecord(s, Arrays.<Object>asList(2, "b")), new DataRecord(s, Arrays.<Object>asList(3, "c")));
    }

    @Test
    public void identityCopiesBatches() {
        RecordSchema s = schema();
        MemoryTarget t = new MemoryTarget();
        EtlResult r = EtlTask.builder().source(new MemorySource(s, rows(s), 2)).target(t).build().run();
        assertEquals(EtlStatus.SUCCESS, r.getStatus());
        assertEquals(3, r.getWritten());
        assertEquals(3, t.getRecords().size());
    }

    @Test
    public void dropAndMapAreCounted() {
        RecordSchema s = schema();
        MemoryTarget t = new MemoryTarget();
        EtlResult r = EtlTask.builder().source(new MemorySource(s, rows(s), 3)).target(t).mappings(Collections.singletonList(new FieldMapping("name", "label"))).transformer(new Transformer() {
            public TransformResult transform(DataRecord x) {
                return "b".equals(x.get("name")) ? TransformResult.drop() : TransformResult.keep(x);
            }
        }).build().run();
        assertEquals(2, r.getWritten());
        assertEquals(1, r.getFiltered());
        assertEquals("label", t.getRecords().get(0).getSchema().getFields().get(0).getName());
    }

    @Test
    public void isolateContinuesTransformErrors() {
        RecordSchema s = schema();
        MemoryTarget t = new MemoryTarget();
        EtlResult r = EtlTask.builder().source(new MemorySource(s, rows(s), 3)).target(t).transformer(new Transformer() {
            public TransformResult transform(DataRecord x) {
                if (((Integer) x.get("id")) == 2) throw new IllegalStateException("bad");
                return TransformResult.keep(x);
            }
        }).build().run();
        assertEquals(EtlStatus.PARTIAL, r.getStatus());
        assertEquals(2, r.getWritten());
        assertEquals(1, r.getFailed());
        assertEquals(1, r.getErrors().size());
    }

    @Test
    public void failFastStopsTaskAndClosesResources() {
        RecordSchema s = schema();
        final boolean[] closed = {false};
        final MemorySource delegate = new MemorySource(s, rows(s), 3);
        Source source = new Source() {
            public RecordSchema schema() {
                return delegate.schema();
            }

            public void read(BatchConsumer c) {
                delegate.read(c);
            }

            public void close() {
                closed[0] = true;
            }
        };
        Target target = new MemoryTarget();
        EtlResult r = EtlTask.builder().source(source).target(target).errorPolicy(ErrorPolicy.FAIL_FAST).transformer(new Transformer() {
            public TransformResult transform(DataRecord x) {
                throw new IllegalArgumentException("bad");
            }
        }).build().run();
        assertEquals(EtlStatus.FAILED, r.getStatus());
        assertTrue(closed[0]);
    }


    @Test
    public void localTest() {
        String driverId = "mysql8";
        JdbcDriverLoader loader = new JdbcDriverLoader();

        Properties properties = new Properties();
        properties.setProperty("user", "root");
        properties.setProperty("password", "root");
        try {
            EtlResult result = EtlTask.builder()
                    .source(new JdbcTableSource(loader.connect(DatabaseType.MYSQL, "jdbc:mysql://localhost:3306/hxl", driverId, properties),
                            new QualifiedTableName(null, null, "jsh_account")))
                    .target(new JdbcTableTarget(loader.connect(DatabaseType.MYSQL, "jdbc:mysql://localhost:3306/hxl2", driverId, properties),
                            "a", DatabaseType.MYSQL))
                    .targetMode(TargetMode.REPLACE)
                    .build().run();
            assertSame(result.getStatus(), EtlStatus.SUCCESS);
        } finally {
            loader.close();
        }
    }
}
