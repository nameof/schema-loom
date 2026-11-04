package io.github.nameof.schemaloom.transform;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.metadata.*;
import org.junit.Test;

import java.util.*;
import java.sql.Types;

import static org.junit.Assert.*;

public class FieldMappingTest {
    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingJdbcType() {
        new ColumnInfo("id", "INT", null, LogicalType.INT32, Types.NULL, 1, true, null, null, null);
    }

    @Test
    public void preservesAndMapsStructuralMetadata() {
        QualifiedTableName referenced = new QualifiedTableName(null, "APP", "CUSTOMERS");
        TableInfo source = new TableInfo(new QualifiedTableName(null, "APP", "ORDERS"), false, "TABLE",
                Arrays.asList(
                        new ColumnInfo("id", "INT", null, LogicalType.INT32, Types.INTEGER, 1, false, null, null, null, null, null, false, false),
                        new ColumnInfo("customer_id", "INT", null, LogicalType.INT32, Types.INTEGER, 2, false, null, null, null, null, null, false, false)),
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
        assertEquals(Types.INTEGER, mapped.getColumns().get(0).getJdbcType());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsForeignKeyWithUnmappedLocalColumn() {
        // 映射只覆盖 id，外键列 customer_id 未被映射 —— 必须显式失败。
        // 注意：E4 修复后映射里的源字段必须真实存在，所以这里要给出一个合法的 id 映射，
        // 否则会先在列循环报「源表中不存在映射字段」，测不到外键分支。
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "orders"), false, "TABLE",
                Arrays.asList(
                        new ColumnInfo("id", "INT", null, LogicalType.INT32, Types.INTEGER, 1, true, null, null, null),
                        new ColumnInfo("customer_id", "INT", null, LogicalType.INT32, Types.INTEGER, 2, true, null, null, null)),
                null, Collections.<IndexInfo>emptyList(),
                Collections.singletonList(new ForeignKeyInfo("fk", new QualifiedTableName(null, null, "customers"),
                        Collections.singletonList("customer_id"), Collections.singletonList("id"), null, null)),
                Collections.<ConstraintInfo>emptyList(), null);
        FieldMapping.mapTableInfo(source, Collections.singletonList(new FieldMapping("id", "order_id")));
    }

    @Test
    public void rejectsMappingToMissingSourceColumn() {
        TableInfo source = new TableInfo(new QualifiedTableName(null, null, "orders"), false, "TABLE",
                Collections.singletonList(new ColumnInfo("id", "INT", null, LogicalType.INT32, Types.INTEGER, 1, false, null, null, null)),
                null, Collections.<IndexInfo>emptyList(), Collections.<ForeignKeyInfo>emptyList(),
                Collections.<ConstraintInfo>emptyList(), null);
        // 与 mapSchema 对齐：源字段不存在时必须显式失败，不能静默跳过导致目标 Schema 少列
        try {
            FieldMapping.mapTableInfo(source, Collections.singletonList(new FieldMapping("missing", "x")));
            fail("映射到源表不存在的字段必须抛异常");
        } catch (IllegalArgumentException e) {
            assertEquals("源表中不存在映射字段: missing", e.getMessage());
        }
    }

    @Test
    public void derivesRecordSchemaFromColumns() {
        new TableInfo(new QualifiedTableName(null, null, "orders"), false, "TABLE",
                Collections.singletonList(new ColumnInfo("id", "VARCHAR", null, LogicalType.STRING, Types.VARCHAR, 1, true, null, null, null)),
                null, Collections.<IndexInfo>emptyList(), Collections.<ForeignKeyInfo>emptyList(),
                Collections.<ConstraintInfo>emptyList(), null);
        assertEquals(LogicalType.STRING, new TableInfo(new QualifiedTableName(null, null, "orders"), false,
                Collections.singletonList(new ColumnInfo("id", "VARCHAR", null, LogicalType.STRING, Types.VARCHAR, 1, true, null, null, null)),
                null, Collections.<IndexInfo>emptyList(), null).toRecordSchema().field("id").getLogicalType());
    }

}
