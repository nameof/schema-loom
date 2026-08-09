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
