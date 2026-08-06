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
    public void listenerReceivesProgressAndElapsedResult() {
        RecordSchema s = schema();
        MemoryTarget t = new MemoryTarget();
        final List<String> events = new ArrayList<String>();
        final List<EtlProgress> progress = new ArrayList<EtlProgress>();
        final EtlResult[] completed = {null};
        final Object context = new Object();
        EtlResult result = EtlTask.builder()
                .source(new MemorySource(s, rows(s), 2))
                .target(t)
                .context(context)
                .listener(new EtlTaskListener() {
                    public void onStarted(Object c, EtlProgress p) {
                        events.add("started");
                        progress.add(p);
                        assertSame(context, c);
                    }

                    public void onProgress(Object c, EtlProgress p) {
                        events.add("progress");
                        progress.add(p);
                        assertSame(context, c);
                    }

                    public void onCompleted(Object c, EtlResult r) {
                        events.add("completed");
                        completed[0] = r;
                        assertSame(context, c);
                    }
                }).build().run();
        assertEquals(Arrays.asList("started", "progress", "progress", "completed"), events);
        assertEquals(3, progress.get(0).getTotal());
        assertEquals(1, progress.get(1).getBatchIndex());
        assertEquals(3, progress.get(2).getRead());
        assertSame(result, completed[0]);
        assertNotNull(result.getStarted());
        assertNotNull(result.getEnded());
        assertTrue(result.getElapsedMillis() >= 0);
    }

    @Test
    public void listenerFailureDoesNotFailTask() {
        RecordSchema s = schema();
        MemoryTarget t = new MemoryTarget();
        final List<ListenerCallback> failures = new ArrayList<ListenerCallback>();
        EtlResult result = EtlTask.builder()
                .source(new MemorySource(s, rows(s), 3))
                .target(t)
                .listener(new EtlTaskListener() {
                    public void onProgress(Object context, EtlProgress p) {
                        throw new IllegalStateException("observer failed");
                    }
                })
                .listenerErrorHandler((callback, context, error) -> failures.add(callback))
                .build().run();
        assertEquals(EtlStatus.SUCCESS, result.getStatus());
        assertEquals(Collections.singletonList(ListenerCallback.PROGRESS), failures);
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
        JdbcDriverLoader loader = new JdbcDriverLoader();

        JdbcConnectionConfig sourceConfig = new JdbcConnectionConfig(
                DatabaseType.MYSQL, "localhost", 3306, "hxl", "root", "root");
        JdbcConnectionConfig targetConfig = new JdbcConnectionConfig(
                DatabaseType.MYSQL, "localhost", 3306, "hxl2", "root", "root");
        try {
            EtlResult result = EtlTask.builder()
                    .source(new JdbcTableSource(loader.connect(sourceConfig),
                            new QualifiedTableName(null, null, "jsh_account")))
                    .target(new JdbcTableTarget(loader.connect(targetConfig),
                            "a", DatabaseType.MYSQL))
                    .targetMode(TargetMode.REPLACE)
                    .build().run();
            assertSame(result.getStatus(), EtlStatus.SUCCESS);
        } finally {
            loader.close();
        }
    }
}
