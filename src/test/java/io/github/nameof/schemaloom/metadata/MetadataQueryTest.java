package io.github.nameof.schemaloom.metadata;

import io.github.nameof.schemaloom.driver.DatabaseConnectionInfo;
import io.github.nameof.schemaloom.driver.DatabaseType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MetadataQueryTest {
    @Test
    public void mysqlUsesDatabaseAsCatalog() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.MYSQL, "localhost", 3306,
                "dbmask", null, "ignored", "user", "password", null, null);

        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "%");

        assertEquals("dbmask", query.getCatalog());
        assertNull(query.getSchema());
        assertEquals("%", query.getTablePattern());
    }

    @Test
    public void sqlServerUsesConfiguredCatalogAndSchema() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.SQL_SERVER, "localhost", 1433,
                "dbo", "app", "dbo", "user", "password", null, null);

        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "dms_%");

        assertEquals("app", query.getCatalog());
        assertEquals("dbo", query.getSchema());
        assertEquals("dms_%", query.getTablePattern());
    }
}
