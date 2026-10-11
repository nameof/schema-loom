package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.dialect.*;
import io.github.nameof.schemaloom.metadata.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 校验 MariaDB 独立驱动、命名空间和 SQL 方言。 */
public class MariaDbContractTest {
    @Test
    public void usesIndependentUrlAndCatalog() {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.MARIADB, "localhost", 0, "shop", "user", "password");
        assertEquals(3306, config.getPort());
        assertEquals("jdbc:mariadb://localhost:3306/shop", JdbcUrlBuilder.build(DatabaseType.MARIADB, null, config.getHost(), config.getPort(), config.getDatabase()));
        MetadataQuery query = MetadataQuery.fromConnectionInfo(config, "%");
        assertEquals("shop", query.getCatalog());
        assertNull(query.getSchema());
        DatabaseDialect dialect = new DialectRegistry().get(DatabaseType.MARIADB);
        assertNotSame(new DialectRegistry().get(DatabaseType.MYSQL).getClass(), dialect.getClass());
        assertEquals("`shop`.`order`", dialect.quote(new QualifiedTableName("shop", null, "order")));
        assertEquals("DECIMAL(65,38)", dialect.mapping(LogicalType.DECIMAL).getDdlType(new FieldSchema("amount", LogicalType.DECIMAL, true, null, 65, 38)));
        for (LogicalType type : LogicalType.values()) assertNotNull(dialect.mapping(type));
        ViewDefinitionQuery view = dialect.viewDefinitionQuery(config, new QualifiedTableName("shop", null, "orders"));
        assertTrue(view.getSql().contains("INFORMATION_SCHEMA.VIEWS"));
        assertTrue(dialect.indexSql("`orders`", java.util.Collections.singletonList(
                new IndexInfo("PRIMARY", "btree", true, java.util.Collections.singletonList("id")))).isEmpty());
    }
}
