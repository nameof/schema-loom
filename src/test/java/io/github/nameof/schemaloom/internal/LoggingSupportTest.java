package io.github.nameof.schemaloom.internal;

import io.github.nameof.schemaloom.api.DataRecord;
import io.github.nameof.schemaloom.api.FieldSchema;
import io.github.nameof.schemaloom.api.LogicalType;
import io.github.nameof.schemaloom.api.RecordSchema;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.source.MemorySource;
import io.github.nameof.schemaloom.target.MemoryTarget;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LoggingSupportTest {
    @Test
    public void sanitizesCredentialsAndKeepsBoundedMessage() {
        String value = LoggingSupport.message("password=secret token:abc123 user=bob jdbc:mysql://alice:secret@host/db");
        assertFalse(value.contains("secret"));
        assertFalse(value.contains("abc123"));
        assertFalse(value.contains("bob"));
        assertFalse(value.contains("alice"));
        assertTrue(value.contains("<redacted>"));
    }

    @Test
    public void copiesSanitizedExceptionChainAndStack() {
        IllegalStateException cause = new IllegalStateException("pwd=inner-secret");
        IllegalArgumentException error = new IllegalArgumentException("password=outer-secret", cause);
        Throwable safe = LoggingSupport.safe(error);
        assertNotNull(safe);
        assertFalse(safe.toString().contains("outer-secret"));
        assertNotNull(safe.getCause());
        assertFalse(safe.getCause().toString().contains("inner-secret"));
        assertTrue(safe.getStackTrace().length > 0);
    }

    @Test
    public void createsShortRunId() {
        String runId = LoggingSupport.runId();
        assertTrue(runId.matches("[0-9a-f]{12}"));
    }

    @Test
    public void restoresPreviousRunId() {
        String first = LoggingSupport.bindRunId("first");
        String nested = LoggingSupport.bindRunId("nested");
        assertTrue("nested".equals(LoggingSupport.currentRunId()));
        LoggingSupport.restoreRunId(nested);
        assertTrue("first".equals(LoggingSupport.currentRunId()));
        LoggingSupport.restoreRunId(first);
    }

    @Test
    public void taskClearsRunIdAfterSuccessAndFailure() {
        RecordSchema schema = new RecordSchema(Collections.singletonList(FieldSchema.of("id", LogicalType.INT32)));
        DataRecord record = new DataRecord(schema, Arrays.<Object>asList(1));
        EtlTask.builder().source(new MemorySource(schema, Collections.singletonList(record), 1))
                .target(new MemoryTarget()).build().run();
        assertNull(LoggingSupport.currentRunId());

        EtlTask.builder().source(new MemorySource(schema, Collections.singletonList(record), 1))
                .target(new MemoryTarget()).transformer(value -> {
                    throw new IllegalStateException("expected failure");
                }).build().run();
        assertNull(LoggingSupport.currentRunId());
    }
}
