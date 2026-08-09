package io.github.nameof.schemaloom.driver;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.*;

public class JdbcDriverLoaderTest {
    @Test public void acceptsInclusiveAndExclusiveVersionRanges() {
        assertTrue(JdbcDriverLoader.VersionRange.accept("[5.7,8.0)", "5.7.44"));
        assertTrue(JdbcDriverLoader.VersionRange.accept("[5.7,8.0)", "7.9.99"));
        assertFalse(JdbcDriverLoader.VersionRange.accept("[5.7,8.0)", "8.0.0"));
        assertFalse(JdbcDriverLoader.VersionRange.accept("[5.7,8.0)", "5.6.9"));
        assertTrue(JdbcDriverLoader.VersionRange.accept("[,)", "anything"));
    }

    @Test public void isolatesSameNamedDriversAndSelectsByPriority() throws Exception {
        Path root = Files.createTempDirectory("schemaloom-driver-contract");
        writeDriver(root, "fixture5", "5.7.44", 5, "[5.0,8.0)");
        writeDriver(root, "fixture8", "8.0.36", 10, "[8.0,9.0)");
        JdbcDriverLoader loader = new JdbcDriverLoader(root);
        try {
            DatabaseConnectionInfo automatic = new DatabaseConnectionInfo(DatabaseType.MYSQL, "host", 0,
                    "db", "user", "password", null, null);
            ConnectionProvider selected = loader.connect(automatic);
            assertEquals("8.0.36", selected.getConnection().getMetaData().getDatabaseProductVersion());
            selected.close();
            assertEquals(0, cacheSize(loader));

            DatabaseConnectionInfo explicit = new DatabaseConnectionInfo(DatabaseType.MYSQL, "host", 0,
                    "db", "user", "password", "fixture5", null);
            ConnectionProvider selectedFive = loader.connect(explicit);
            assertEquals("5.7.44", selectedFive.getConnection().getMetaData().getDatabaseProductVersion());
            selectedFive.close();
            assertEquals(0, cacheSize(loader));
        } finally {
            loader.close();
        }
    }

    private static void writeDriver(Path root, String id, String version, int priority, String range) throws Exception {
        String jarName = id + ".jar";
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(root.resolve(jarName)))) {
            String[] classNames = {
                    "io/github/nameof/schemaloom/testdriver/FixtureDriver.class",
                    "io/github/nameof/schemaloom/testdriver/FixtureDriver$1.class",
                    "io/github/nameof/schemaloom/testdriver/FixtureDriver$2.class"
            };
            for (String className : classNames) {
                jar.putNextEntry(new JarEntry(className));
                InputStream classBytes = JdbcDriverLoaderTest.class.getClassLoader().getResourceAsStream(className);
                try {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = classBytes.read(buffer)) >= 0) if (n > 0) jar.write(buffer, 0, n);
                } finally {
                    classBytes.close();
                }
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("driver-version.txt"));
            jar.write(version.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        Files.write(root.resolve(id + ".properties"), Arrays.asList(
                "id=" + id,
                "databaseType=MYSQL",
                "driverClass=io.github.nameof.schemaloom.testdriver.FixtureDriver",
                "classpath=" + jarName,
                "urlPrefixes=jdbc:fixture:",
                "urlTemplate=jdbc:fixture://${host}:${port}/${database}",
                "priority=" + priority,
                "serverVersionRange=" + range,
                "driverPackages=io.github.nameof.schemaloom.testdriver"), StandardCharsets.UTF_8);
    }

    private static int cacheSize(JdbcDriverLoader loader) throws Exception {
        java.lang.reflect.Field field = JdbcDriverLoader.class.getDeclaredField("cache");
        field.setAccessible(true);
        return ((java.util.Map<?, ?>) field.get(loader)).size();
    }
}
