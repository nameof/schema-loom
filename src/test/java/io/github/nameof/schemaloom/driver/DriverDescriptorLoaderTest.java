package io.github.nameof.schemaloom.driver;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class DriverDescriptorLoaderTest {
    @Test public void loadsDescriptorAndResolvesRelativeClasspath() throws Exception {
        Path root = Files.createTempDirectory("schemaloom-drivers");
        Path jar = Files.createFile(root.resolve("driver.jar"));
        Files.write(root.resolve("mysql.properties"), Arrays.asList(
                "id=mysql-test", "databaseType=MYSQL", "driverClass=com.example.Driver",
                "classpath=driver.jar", "urlPrefixes=jdbc:mysql:", "priority=10",
                "serverVersionRange=[5.7,9.0)", "driverPackages=com.example", "defaultProperties=useSSL=false;connectTimeout=1000"), StandardCharsets.UTF_8);
        DriverDescriptor descriptor = new DriverDescriptorLoader().load(root).get(0);
        assertEquals("mysql-test", descriptor.getId());
        assertEquals(DatabaseType.MYSQL, descriptor.getDatabaseType());
        assertEquals(jar.toAbsolutePath().normalize(), descriptor.getClasspath().get(0));
        assertEquals("false", descriptor.getDefaultProperties().getProperty("useSSL"));
    }

    @Test public void supportsConnectionConfigDefaults() {
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.MYSQL, "db.example", 0,
                "app", "user", "secret", null, null);
        assertEquals(3306, config.getPort());
        assertEquals("jdbc:mysql://db.example:3306/app",
                JdbcUrlBuilder.build(config.getDatabaseType(), "", config.getHost(), config.getPort(), config.getDatabase()));
    }

    @Test(expected = RuntimeException.class)
    public void rejectsInvalidConnectionPort() {
        new DatabaseConnectionInfo(DatabaseType.MYSQL, "db.example", 70000,
                "app", "user", "secret", null, null);
    }

    @Test public void copiesConnectionProperties() {
        Properties input = new Properties();
        input.setProperty("useSSL", "false");
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.MYSQL, "db.example", 3306,
                "app", "user", "secret", null, input);
        input.setProperty("useSSL", "true");
        assertEquals("false", config.getProperties().getProperty("useSSL"));
    }

    @Test public void expandsDescriptorUrlTemplate() {
        assertEquals("jdbc:test://db.example:1234/app",
                JdbcUrlBuilder.build(DatabaseType.MYSQL, "jdbc:test://${host}:${port}/${database}",
                        "db.example", 1234, "app"));
    }

    @Test(expected = RuntimeException.class)
    public void rejectsUnknownUrlTemplatePlaceholder() {
        JdbcUrlBuilder.build(DatabaseType.MYSQL, "jdbc:test://${host}/${unknown}",
                "db.example", 1234, "app");
    }

    @Test(expected = RuntimeException.class) public void rejectsClasspathOutsideRoot() throws Exception {
        Path root = Files.createTempDirectory("schemaloom-drivers");
        Files.write(root.resolve("bad.properties"), Arrays.asList("id=x", "databaseType=MYSQL", "driverClass=x.Driver", "classpath=..\\outside.jar"), StandardCharsets.UTF_8);
        new DriverDescriptorLoader().load(root);
    }
}
