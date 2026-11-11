package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.DataRecord;
import io.github.nameof.schemaloom.driver.ConnectionProvider;
import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;
import io.github.nameof.schemaloom.driver.JdbcDriverLoader;
import io.github.nameof.schemaloom.metadata.DatabaseMetadataService;
import io.github.nameof.schemaloom.metadata.MetadataQuery;
import io.github.nameof.schemaloom.metadata.TableInfo;
import io.github.nameof.schemaloom.source.JdbcTableSource;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DB2「只配 schema」下推的实机回归。
 * <p>
 * 现场问题：数据资产探索页选 DB2 数据源、只给 {@code schemaName=SL_SOURCE}，SchemaCrawler 却把
 * {@code SYSCAT / SYSIBMADM / SYSIBM / SYSSTAT / SYSTOOLS} 里几百张系统表全爬一遍，
 * 每张表打一行 {@code IndexRetriever - Retrieving indexes from metadata}，页面等 20 秒以上才出表。
 * <p>
 * 实测（BLUDB，7 个 schema / 343 张表）：不下推 23.6 秒，下推 0.64 秒。本用例锁定「只返回配置 schema 的表」
 * 与「耗时不再随实例表数膨胀」两条结论。
 */
public class Db2SchemaPushdownProbeIntegrationTest {

    private static final String SCHEMA = "SL_SOURCE";
    private static final String TABLE = "USER_INFO";

    /** 列一份 schema 下的表：必须只命中该 schema，且不能退化成全实例扫描。 */
    @Test
    public void listsOnlyConfiguredSchemaTables() throws Exception {
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(db2())) {
            long start = System.currentTimeMillis();
            List<TableInfo> tables = new DatabaseMetadataService()
                    .listTables(provider, new MetadataQuery(null, SCHEMA, "%"));
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  [DB2-PUSH] schema=" + SCHEMA + " tables=" + tables.size() + " elapsedMs=" + elapsed);
            for (TableInfo table : tables)
                System.out.println("  [DB2-PUSH] table=" + table.getName().getSchema() + "." + table.getName().getTable());

            assertFalse("配置的 schema 下应至少读到 1 张表", tables.isEmpty());
            for (TableInfo table : tables)
                assertEquals("只能返回配置 schema 的表", SCHEMA, table.getName().getSchema());
            // 下推失效时这里会扫 343 张表、稳定跑到 20 秒以上；10 秒上限留了 15 倍余量
            assertTrue("schema 下推疑似失效，耗时 " + elapsed + "ms（修复前基线约 23600ms）", elapsed < 10_000);
        }
    }

    /** 按表名精确查询与预览：确认下推没有把目标表过滤掉。 */
    @Test
    public void findsTableByExactNameAndPreviews() throws Exception {
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(db2())) {
            TableInfo table = new DatabaseMetadataService()
                    .getTable(provider, new MetadataQuery(null, SCHEMA, TABLE));
            System.out.println("  [DB2-TABLE] " + table.getName().getSchema() + "." + table.getName().getTable()
                    + " columns=" + table.getColumns().size());
            assertEquals(TABLE, table.getName().getTable());
        }

        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             JdbcTableSource source = new JdbcTableSource(db2(), TABLE, loader, 10)) {
            List<DataRecord> rows = source.preview(3);
            System.out.println("  [DB2-PREVIEW] rows=" + rows.size());
            assertFalse("预览应至少返回 1 行", rows.isEmpty());
        }
    }

    private static DatabaseConnectionInfo db2() {
        // 与既有集成测试保持一致：localhost:15000 / BLUDB / db2inst1
        return new DatabaseConnectionInfo(DatabaseType.DB2, "localhost", 15000, "BLUDB", null, SCHEMA,
                "db2inst1", "db2inst1", "db2", null);
    }
}
