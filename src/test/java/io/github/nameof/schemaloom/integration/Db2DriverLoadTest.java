package io.github.nameof.schemaloom.integration;

import cn.hutool.core.util.StrUtil;
import io.github.nameof.schemaloom.driver.*;
import org.junit.Assume;
import org.junit.Test;

public class Db2DriverLoadTest {
    @Test
    public void testDriverLoad() {
        String host = System.getenv("SCHEMALOOM_IT_DB2_HOST");
        Assume.assumeTrue("配置 DB2 主机后执行真实集成测试", StrUtil.isNotBlank(host));
        String database = System.getenv("SCHEMALOOM_IT_DB2_DATABASE");
        String user = System.getenv("SCHEMALOOM_IT_DB2_USER");
        String password = System.getenv("SCHEMALOOM_IT_DB2_PASSWORD");
        
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DB2, host,
                Integer.parseInt(StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_DB2_PORT"), "50000")),
                database, null, user, user, password,
                StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_DB2_DRIVER_ID"), "db2"), null);
        
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider provider = loader.connect(config)) {
            System.out.println("Driver loaded successfully!");
            System.out.println("Connection: " + provider.getConnection());
            System.out.println("Database version: " + provider.getConnection().getMetaData().getDatabaseProductVersion());
        } catch (Throwable e) {
            System.err.println("Failed to load driver: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
