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

    @Test(expected = RuntimeException.class) public void rejectsClasspathOutsideRoot() throws Exception {
        Path root = Files.createTempDirectory("schemaloom-drivers");
        Files.write(root.resolve("bad.properties"), Arrays.asList("id=x", "databaseType=MYSQL", "driverClass=x.Driver", "classpath=..\\outside.jar"), StandardCharsets.UTF_8);
        new DriverDescriptorLoader().load(root);
    }
}
