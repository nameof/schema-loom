package io.github.nameof.schemaloom.transform;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.metadata.*;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class FieldMappingTest {
    @Test
    public void preservesAndMapsStructuralMetadata() {
        QualifiedTableName referenced = new QualifiedTableName(null, "APP", "CUSTOMERS");
        TableInfo source = new TableInfo(new QualifiedTableName(null, "APP", "ORDERS"), false, "TABLE",
                Arrays.asList(
                        new ColumnInfo("id", "INT", null, LogicalType.INT32, 1, false, null, null, null, null, null, false, false),
                        new ColumnInfo("customer_id", "INT", null, LogicalType.INT32, 2, false, null, null, null, null, null, false, false)),
                new PrimaryKeyInfo("pk_orders", Collections.singletonList("id")),
                Collections.singletonList(new IndexInfo("ix_customer", false, Collections.singletonList("customer_id"))),
                Collections.singletonList(new ForeignKeyInfo("fk_customer", referenced,
                        Collections.singletonList("customer_id"), Collections.singletonList("id"), "NO ACTION", "CASCADE")),
                Collections.singletonList(new ConstraintInfo("ck_customer", "CHECK", Collections.singletonList("customer_id"))),
                "orders");

        TableInfo mapped = FieldMapping.mapTableInfo(source, Arrays.asList(
                new FieldMapping("id", "order_id"), new FieldMapping("customer_id", "buyer_id")));

        assertEquals(Collections.singletonList("order_id"), mapped.getPrimaryKey().getColumns());
        assertEquals(Collections.singletonList("buyer_id"), mapped.getIndexes().get(0).getColumns());
        assertEquals(Collections.singletonList("buyer_id"), mapped.getForeignKeys().get(0).getColumns());
        assertEquals(Collections.singletonList("id"), mapped.getForeignKeys().get(0).getReferencedColumns());
        assertEquals(Collections.singletonList("buyer_id"), mapped.getConstraints().get(0).getColumns());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsForeignKeyWithUnmappedLocalColumn() {
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "orders"), false, "TABLE",
                Collections.singletonList(new ColumnInfo("customer_id", "INT", null, LogicalType.INT32, 1, true, null, null, null)), null, Collections.<IndexInfo>emptyList(),
                Collections.singletonList(new ForeignKeyInfo("fk", new QualifiedTableName(null, null, "customers"),
                        Collections.singletonList("customer_id"), Collections.singletonList("id"), null, null)),
                Collections.<ConstraintInfo>emptyList(), null);
        FieldMapping.mapTableInfo(source, Collections.singletonList(new FieldMapping("other", "x")));
    }

    @Test
    public void derivesRecordSchemaFromColumns() {
        new TableInfo(new QualifiedTableName(null, null, "orders"), false, "TABLE",
                Collections.singletonList(new ColumnInfo("id", "VARCHAR", null, LogicalType.STRING, 1, true, null, null, null)),
                null, Collections.<IndexInfo>emptyList(), Collections.<ForeignKeyInfo>emptyList(),
                Collections.<ConstraintInfo>emptyList(), null);
        assertEquals(LogicalType.STRING, new TableInfo(new QualifiedTableName(null, null, "orders"), false,
                Collections.singletonList(new ColumnInfo("id", "VARCHAR", null, LogicalType.STRING, 1, true, null, null, null)),
                null, Collections.<IndexInfo>emptyList(), null).getSchema().field("id").getLogicalType());
    }

}
