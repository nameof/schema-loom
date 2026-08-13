package io.github.nameof.schemaloom.integration;

import io.github.nameof.schemaloom.api.*;
import io.github.nameof.schemaloom.driver.*;
import io.github.nameof.schemaloom.engine.EtlTask;
import io.github.nameof.schemaloom.source.XlsxSource;
import io.github.nameof.schemaloom.target.JdbcTableTarget;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Assume;
import org.junit.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * 此测试用于验证LogicalType 未对实际数据做归一化处理时，在异构source target写入时，LogicalType 相同，但接受的数据对象类型不兼容，会导致写入失败
 */
public class XlsxToMysqlBoundaryIntegrationTest {
    @Test
    public void writesXlsxTimestampToMysqlAndExposesUnnormalizedValue() throws Exception {
        String password = System.getenv("SCHEMALOOM_IT_MYSQL_PASSWORD");
        Assume.assumeTrue("set SCHEMALOOM_IT_MYSQL_PASSWORD to run this test", password != null);

        DatabaseConnectionInfo config = new DatabaseConnectionInfo(
                DatabaseType.MYSQL,
                valueOrDefault("SCHEMALOOM_IT_MYSQL_HOST", "localhost"),
                Integer.parseInt(valueOrDefault("SCHEMALOOM_IT_MYSQL_PORT", "3306")),
                valueOrDefault("SCHEMALOOM_IT_MYSQL_DATABASE", "hxl"),
                valueOrDefault("SCHEMALOOM_IT_MYSQL_USER", "root"),
                password,
                valueOrDefault("SCHEMALOOM_IT_MYSQL_DRIVER_ID", "mysql8"),
                null);
        Path xlsx = Files.createTempFile("schemaloom-xlsx-mysql-", ".xlsx");
        String table = "schemaloom_xlsx_timestamp_target";
        JdbcDriverLoader loader = new JdbcDriverLoader();
        try {
            writeDateCell(xlsx);
            ConnectionProvider setup = loader.connect(config);
            try {
                Statement statement = setup.getConnection().createStatement();
                try {
                    statement.executeUpdate("DROP TABLE IF EXISTS " + table);
                    statement.executeUpdate("CREATE TABLE " + table + " (created_at TIMESTAMP NULL)");
                } finally {
                    statement.close();
                }
            } finally {
                setup.close();
            }

            XlsxSource source = new XlsxSource(
                    xlsx, "Sheet1",
                    new RecordSchema(Collections.singletonList(FieldSchema.of("created_at", LogicalType.TIMESTAMP))));
            final Object[] value = new Object[1];
            source.read(batch -> value[0] = batch.getRecords().get(0).get(0));

            EtlResult result = EtlTask.builder()
                    .source(source)
                    .target(new JdbcTableTarget(config, table, loader))
                    .targetMode(TargetMode.APPEND)
                    .build()
                    .run();

            assertEquals(EtlStatus.PARTIAL, result.getStatus());
            assertEquals(1, result.getRead());
            assertEquals(1, result.getFailed());
            assertEquals(0, result.getWritten());
            assertEquals("write", result.getErrors().get(0).getStage());

            ConnectionProvider reader = loader.connect(config);
            try {
                ResultSet rs = reader.getConnection().createStatement()
                        .executeQuery("SELECT created_at FROM " + table);
                assertTrue("target table should contain the written row", rs.next());
                assertNotNull(rs.getObject(1));
            } finally {
                reader.close();
            }
        } finally {
            try {
                ConnectionProvider cleanup = loader.connect(config);
                try {
                    cleanup.getConnection().createStatement().executeUpdate("DROP TABLE IF EXISTS " + table);
                } finally {
                    cleanup.close();
                }
            } finally {
                loader.close();
                Files.deleteIfExists(xlsx);
            }
        }
    }

    private static void writeDateCell(Path path) throws Exception {
        Workbook workbook = new XSSFWorkbook();
        try {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("created_at");
            Row data = sheet.createRow(1);
            // Excel date serial number; the explicit schema says this value is TIMESTAMP.
            data.createCell(0).setCellValue(46243D);
            OutputStream output = Files.newOutputStream(path);
            try {
                workbook.write(output);
            } finally {
                output.close();
            }
        } finally {
            workbook.close();
        }
    }

    private static String valueOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }
}
