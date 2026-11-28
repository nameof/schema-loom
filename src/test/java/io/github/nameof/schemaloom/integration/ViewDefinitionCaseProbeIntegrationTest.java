package io.github.nameof.schemaloom.integration;

import cn.hutool.core.util.StrUtil;
import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.migration.JdbcViewMigrationTask;
import org.junit.Assume;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.Assert.assertEquals;

/**
 * B15 复现与回归探针：加引号的小写视图名能否被 viewDefinitionQuery 查到（DB2 / 达梦 / Oracle 各一次）。
 * <p>
 * B5 修复后 {@code DatabaseConnectionInfo#normalizeIdentifier} 保留「双引号包裹即精确保留大小写」的逃生口，
 * 但三个方言的 {@code viewDefinitionQuery} 仍无条件 toUpperCase —— 加引号小写视图在视图迁移路径上查不到定义。
 * <ul>
 *   <li>正向对照：未加引号视图（输入层已按方言惯例大写化或本身大写），修复前后都必须成功；</li>
 *   <li>缺陷场景：{@code "v_lower"} 加引号传入，修复前报 view definition is unavailable，修复后应成功。</li>
 * </ul>
 */
public class ViewDefinitionCaseProbeIntegrationTest {

    @Test
    public void migratesQuotedLowercaseViewOnDb2() {
        String host = System.getenv("SCHEMALOOM_IT_DB2_HOST");
        Assume.assumeTrue("配置 DB2 主机后执行真实集成测试", StrUtil.isNotBlank(host));
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DB2, host,
                Integer.parseInt(StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_DB2_PORT"), "50000")),
                requiredDb2("DATABASE"), null, null, requiredDb2("USER"), requiredDb2("PASSWORD"), "db2", null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider setup = loader.connect(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(setup.getConnection(), true));
            cleanDb2(jdbc);
            try {
                jdbc.execute("CREATE TABLE sl_b15_src (c INT)");
                jdbc.execute("INSERT INTO sl_b15_src VALUES (1)");
                jdbc.execute("CREATE VIEW sl_b15_upper_view AS SELECT c FROM sl_b15_src");
                jdbc.execute("CREATE VIEW \"v_lower\" AS SELECT c FROM sl_b15_src");

                // 正向对照：未加引号视图，输入层 normalizeIdentifier 大写化后命中
                assertSuccess(new JdbcViewMigrationTask(config, config, "sl_b15_upper_view", "sl_b15_upper_tgt", loader).run());
                assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM sl_b15_upper_tgt", Integer.class));
                // 缺陷场景：加引号小写视图
                assertSuccess(new JdbcViewMigrationTask(config, config, "\"v_lower\"", "\"v_lower_tgt\"", loader).run());
                assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM \"v_lower_tgt\"", Integer.class));
            } finally {
                cleanDb2(jdbc);
            }
        }
    }

    @Test
    public void migratesQuotedLowercaseViewOnDm() {
        String host = System.getenv("SCHEMALOOM_IT_DM_HOST");
        Assume.assumeTrue("配置达梦主机后执行真实集成测试", StrUtil.isNotBlank(host));
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.DM, host,
                Integer.parseInt(StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_DM_PORT"), "5236")),
                requiredDm("DATABASE"), null, requiredDm("USER"), requiredDm("USER"), requiredDm("PASSWORD"),
                StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_DM_DRIVER_ID"), "dm"), null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider setup = loader.connect(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(setup.getConnection(), true));
            cleanDm(jdbc);
            try {
                jdbc.execute("CREATE TABLE sl_b15_src (c INT)");
                jdbc.execute("INSERT INTO sl_b15_src VALUES (1)");
                jdbc.execute("CREATE VIEW sl_b15_upper_view AS SELECT c FROM sl_b15_src");
                jdbc.execute("CREATE VIEW \"v_lower\" AS SELECT c FROM sl_b15_src");

                assertSuccess(new JdbcViewMigrationTask(config, config, "sl_b15_upper_view", "sl_b15_upper_tgt", loader).run());
                assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM sl_b15_upper_tgt", Integer.class));
                assertSuccess(new JdbcViewMigrationTask(config, config, "\"v_lower\"", "\"v_lower_tgt\"", loader).run());
                assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM \"v_lower_tgt\"", Integer.class));
            } finally {
                cleanDm(jdbc);
            }
        }
    }

    @Test
    public void migratesQuotedLowercaseViewOnOracle() {
        String host = System.getenv("SCHEMALOOM_IT_ORACLE_HOST");
        Assume.assumeTrue("配置 Oracle 主机后执行真实集成测试", StrUtil.isNotBlank(host));
        String user = System.getenv("SCHEMALOOM_IT_ORACLE_USER");
        String schema = StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_ORACLE_SCHEMA"),
                user == null ? null : user.toUpperCase(java.util.Locale.ENGLISH));
        DatabaseConnectionInfo config = new DatabaseConnectionInfo(DatabaseType.ORACLE, host,
                Integer.parseInt(StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_ORACLE_PORT"), "1521")),
                StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_ORACLE_DATABASE"), "FREEPDB1"),
                null, schema, user, System.getenv("SCHEMALOOM_IT_ORACLE_PASSWORD"),
                StrUtil.blankToDefault(System.getenv("SCHEMALOOM_IT_ORACLE_DRIVER_ID"), "oracle23"), null);
        try (JdbcDriverLoader loader = new JdbcDriverLoader();
             ConnectionProvider setup = loader.connect(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(setup.getConnection(), true));
            cleanOracle(jdbc);
            try {
                jdbc.execute("CREATE TABLE sl_b15_src (c NUMBER(5))");
                jdbc.execute("INSERT INTO sl_b15_src VALUES (1)");
                jdbc.execute("CREATE VIEW SL_B15_UPPER_VIEW AS SELECT c FROM sl_b15_src");
                jdbc.execute("CREATE VIEW \"v_lower\" AS SELECT c FROM sl_b15_src");

                // 正向对照：大写输入（修复前后都应成功）
                assertSuccess(new JdbcViewMigrationTask(config, config, "SL_B15_UPPER_VIEW", "SL_B15_UPPER_TGT", loader).run());
                assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM SL_B15_UPPER_TGT", Integer.class));
                // 缺陷场景：加引号小写视图
                assertSuccess(new JdbcViewMigrationTask(config, config, "\"v_lower\"", "\"v_lower_tgt\"", loader).run());
                assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM \"v_lower_tgt\"", Integer.class));
            } finally {
                cleanOracle(jdbc);
            }
        }
    }

    private void assertSuccess(EtlResult result) {
        String message = result.getErrors().isEmpty() ? "" : result.getErrors().get(0).getStage() + ": " + result.getErrors().get(0).getMessage();
        assertEquals(message, EtlStatus.SUCCESS, result.getStatus());
    }

    private String requiredDb2(String suffix) {
        String value = System.getenv("SCHEMALOOM_IT_DB2_" + suffix);
        if (StrUtil.isBlank(value)) throw new IllegalArgumentException("缺少 DB2 测试配置：" + suffix);
        return value;
    }

    private String requiredDm(String suffix) {
        String value = System.getenv("SCHEMALOOM_IT_DM_" + suffix);
        if (StrUtil.isBlank(value)) throw new IllegalArgumentException("缺少达梦测试配置：" + suffix);
        return value;
    }

    private void cleanDb2(JdbcTemplate jdbc) {
        for (String view : new String[]{"\"v_lower_tgt\"", "\"v_lower\"", "sl_b15_upper_tgt", "sl_b15_upper_view"}) {
            try { jdbc.execute("DROP VIEW " + view); } catch (Exception ignored) {}
        }
        jdbc.execute("DROP TABLE IF EXISTS sl_b15_src");
    }

    private void cleanDm(JdbcTemplate jdbc) {
        for (String view : new String[]{"\"v_lower_tgt\"", "\"v_lower\"", "sl_b15_upper_tgt", "sl_b15_upper_view"}) {
            try { jdbc.execute("DROP VIEW " + view); } catch (Exception ignored) {}
        }
        try { jdbc.execute("DROP TABLE sl_b15_src"); } catch (Exception ignored) {}
    }

    private void cleanOracle(JdbcTemplate jdbc) {
        for (String view : new String[]{"\"v_lower_tgt\"", "\"v_lower\"", "SL_B15_UPPER_TGT", "SL_B15_UPPER_VIEW"}) {
            try { jdbc.execute("DROP VIEW " + view); } catch (Exception ignored) {}
        }
        try { jdbc.execute("DROP TABLE sl_b15_src"); } catch (Exception ignored) {}
    }
}
