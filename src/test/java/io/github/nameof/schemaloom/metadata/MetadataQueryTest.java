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
    @Test
    public void postgresDefaultsToPublicSchema() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.POSTGRESQL, "localhost", 5432,
                "db", "user", "password");
        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "%");
        assertEquals("db", query.getCatalog());
        assertEquals("public", query.getSchema());
    }

    @Test
    public void oracleUsesExplicitUserAsSchema() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.ORACLE, "localhost", 1521,
                "FREEPDB1", null, "SCHEMALOOM", "user", "password", null, null);

        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "ORD_%");

        assertNull(query.getCatalog());
        assertEquals("SCHEMALOOM", query.getSchema());
        assertEquals("ORD_%", query.getTablePattern());
    }

    @Test
    public void db2DefaultsToNullSchema() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.DB2, "localhost", 50000,
                "BLUDB", "user", "password");

        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "%");

        assertNull(query.getCatalog());
        assertNull(query.getSchema());
        assertEquals("%", query.getTablePattern());
    }

    @Test
    public void db2UsesExplicitSchema() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.DB2, "localhost", 50000,
                "BLUDB", null, "MYSCHEMA", "user", "password", null, null);

        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "SL_%");

        assertNull(query.getCatalog());
        assertEquals("MYSCHEMA", query.getSchema());
        assertEquals("SL_%", query.getTablePattern());
    }

    @Test
    public void dmDefaultsToDatabaseAsCatalog() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.DM, "localhost", 5236,
                "DAMENG", "user", "password");

        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "%");

        assertEquals("DAMENG", query.getCatalog());
        assertNull(query.getSchema());
        assertEquals("%", query.getTablePattern());
    }

    @Test
    public void dmUsesExplicitSchema() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.DM, "localhost", 5236,
                "DAMENG", null, "SCHEMALOOM", "SYSDBA", "password", null, null);

        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "SL_%");

        assertNull(query.getCatalog());
        assertEquals("SCHEMALOOM", query.getSchema());
        assertEquals("SL_%", query.getTablePattern());
    }

    @Test
    public void kingbaseDefaultsToPublicSchema() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.KINGBASE, "localhost", 54321,
                "kingbase", "user", "password");
        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "%");
        assertNull(query.getCatalog());
        assertEquals("public", query.getSchema());
    }

    @Test
    public void kingbaseUsesExplicitSchema() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.KINGBASE, "localhost", 54321,
                "kingbase", null, "myschema", "user", "password", null, null);

        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "SL_%");

        assertNull(query.getCatalog());
        assertEquals("myschema", query.getSchema());
        assertEquals("SL_%", query.getTablePattern());
    }

    @Test
    public void gbaseUsesDatabaseAsCatalog() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.GBASE, "localhost", 5258,
                "gbase", "user", "password");
        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "%");
        assertEquals("gbase", query.getCatalog());
        assertNull(query.getSchema());
        assertEquals("%", query.getTablePattern());
    }

    @Test
    public void gbaseIgnoresExplicitSchema() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.GBASE, "localhost", 5258,
                "gbase", null, "myschema", "user", "password", null, null);
        MetadataQuery query = MetadataQuery.fromConnectionInfo(info, "sl_%");
        assertEquals("gbase", query.getCatalog());
        assertNull(query.getSchema());
        assertEquals("sl_%", query.getTablePattern());
    }
}