package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.SchemaLoomException;

import java.util.Locale;

final class JdbcUrlBuilder {
    private JdbcUrlBuilder() { }

    static int defaultPort(DatabaseType type) {
        switch (type) {
            case MYSQL: return 3306;
            case MARIADB: return 3306;
            case ORACLE: return 1521;
            case SQL_SERVER: return 1433;
            case POSTGRESQL: return 5432;
            case DB2: return 50000;
            case DM: return 5236;
            default: throw new SchemaLoomException("unsupported database type: " + type);
        }
    }

    static String build(DatabaseType type, String template, String host, int port, String database) {
        String t = template == null || template.trim().isEmpty() ? defaultTemplate(type) : template.trim();
        String url = t.replace("${host}", host).replace("${port}", String.valueOf(port)).replace("${database}", database);
        if (url.contains("${")) throw new SchemaLoomException("unresolved JDBC URL template: " + t);
        return url;
    }

    private static String defaultTemplate(DatabaseType type) {
        switch (type) {
            case MYSQL: return "jdbc:mysql://${host}:${port}/${database}";
            case MARIADB: return "jdbc:mariadb://${host}:${port}/${database}";
            case ORACLE: return "jdbc:oracle:thin:@//${host}:${port}/${database}";
            case SQL_SERVER: return "jdbc:sqlserver://${host}:${port};databaseName=${database}";
            case POSTGRESQL: return "jdbc:postgresql://${host}:${port}/${database}";
            case DB2: return "jdbc:db2://${host}:${port}/${database}";
            case DM: return "jdbc:dm://${host}:${port}/${database}";
            default: throw new SchemaLoomException("unsupported database type: " + type.toString().toLowerCase(Locale.ENGLISH));
        }
    }
}
