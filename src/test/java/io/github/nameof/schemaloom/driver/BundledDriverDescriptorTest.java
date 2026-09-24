package io.github.nameof.schemaloom.driver;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 校验仓库 drivers 目录中的描述文件可被 Loader 解析。 */
public class BundledDriverDescriptorTest {
    @Test
    public void loadsBundledOracleSqlServerAndMysqlDescriptors() {
        List<DriverDescriptor> descriptors = new DriverDescriptorLoader().load(Paths.get("drivers"));
        Map<String, DriverDescriptor> byId = new HashMap<String, DriverDescriptor>();
        for (DriverDescriptor descriptor : descriptors) byId.put(descriptor.getId(), descriptor);

        assertDescriptor(byId.get("mysql8"), DatabaseType.MYSQL, "com.mysql.cj.jdbc.Driver", "mysql-connector-java-8.0.28.jar", "jdbc:mysql:");
        assertDescriptor(byId.get("oracle23"), DatabaseType.ORACLE, "oracle.jdbc.OracleDriver", "ojdbc8-23.26.3.0.0.jar", "jdbc:oracle:");
        assertDescriptor(byId.get("sqlserver2022"), DatabaseType.SQL_SERVER, "com.microsoft.sqlserver.jdbc.SQLServerDriver", "mssql-jdbc-13.6.0.jre8.jar", "jdbc:sqlserver:");
    }

    private void assertDescriptor(DriverDescriptor descriptor, DatabaseType type, String driverClass,
                                  String jarName, String prefix) {
        assertNotNull(descriptor);
        assertEquals(type, descriptor.getDatabaseType());
        assertEquals(driverClass, descriptor.getDriverClass());
        assertEquals(jarName, descriptor.getClasspath().get(0).getFileName().toString());
        assertTrue(descriptor.getUrlPrefixes().contains(prefix));
        assertFalse(descriptor.getUrlTemplate().trim().isEmpty());
    }
}
