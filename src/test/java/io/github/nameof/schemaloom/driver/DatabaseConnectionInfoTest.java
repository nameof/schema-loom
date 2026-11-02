package io.github.nameof.schemaloom.driver;

import org.junit.Test;
import java.util.Properties;
import static org.junit.Assert.*;

public class DatabaseConnectionInfoTest {
    @Test public void keepsNamespaceAndCreatesQualifiedTable() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.MYSQL, " host ", 0, "db",
                "shop", "sales", "user", "secret", null, null);
        assertEquals(3306, info.getPort());
        assertEquals("shop", info.getCatalog());
        assertEquals("sales", info.getSchema());
        assertEquals("orders", info.table("orders").getTable());
        assertFalse(info.toString().contains("secret"));
    }

    @Test public void postgresDefaultsToPublicSchemaAndPort() {
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.POSTGRESQL, "host", 0, "db", "u", "p");
        assertEquals(5432, info.getPort());
        assertEquals("public", info.getSchema());
        assertEquals("jdbc", "jdbc");
    }

    @Test public void normalizesIdentifierCaseForUppercaseDatabases() {
        // DB2 / 达梦未加引号的标识符按大写存储，用户输入的表名规范化为大写
        DatabaseConnectionInfo db2 = new DatabaseConnectionInfo(DatabaseType.DB2, "host", 50000, "BLUDB", "u", "p");
        assertEquals("ORDERS", db2.table("orders").getTable());
        // 已被引号包裹表示调用方要精确保留大小写，用于命中库里已存在的大小写敏感对象
        assertEquals("orders", db2.table("\"orders\"").getTable());
        DatabaseConnectionInfo dm = new DatabaseConnectionInfo(DatabaseType.DM, "host", 5236, "SYSDBA", "u", "p");
        assertEquals("ORDERS", dm.table("orders").getTable());
        assertEquals("orders", dm.table("\"orders\"").getTable());
        // 其它数据库保持原样
        DatabaseConnectionInfo mysql = new DatabaseConnectionInfo(DatabaseType.MYSQL, "host", 3306, "db", "u", "p");
        assertEquals("orders", mysql.table("orders").getTable());
    }

    @Test public void copiesProperties() {
        Properties input = new Properties();
        input.setProperty("ssl", "true");
        DatabaseConnectionInfo info = new DatabaseConnectionInfo(DatabaseType.MYSQL, "host", 3306, "db", "u", "p", null, input);
        input.setProperty("ssl", "false");
        assertEquals("true", info.getProperties().getProperty("ssl"));
        Properties copy = info.getProperties();
        copy.setProperty("ssl", "false");
        assertEquals("true", info.getProperties().getProperty("ssl"));
    }
}
