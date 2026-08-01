package io.github.nameof.schemaloom.driver;

import java.nio.file.Path;
import java.util.*;

public final class DriverDescriptor {
    private final String id, driverClass, versionRange, urlTemplate;
    private final DatabaseType databaseType;
    private final int priority;
    private final List<Path> classpath;
    private final List<String> prefixes, packages;
    private final Properties defaults;

    DriverDescriptor(String id, DatabaseType type, String driverClass, int priority, String range, String urlTemplate, List<Path> cp, List<String> prefixes, List<String> packages, Properties defaults) {
        this.id = id;
        this.databaseType = type;
        this.driverClass = driverClass;
        this.priority = priority;
        this.versionRange = range;
        this.urlTemplate = urlTemplate;
        this.classpath = Collections.unmodifiableList(cp);
        this.prefixes = Collections.unmodifiableList(prefixes);
        this.packages = Collections.unmodifiableList(packages);
        this.defaults = defaults;
    }

    public String getId() {
        return id;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public int getPriority() {
        return priority;
    }

    public String getVersionRange() {
        return versionRange;
    }

    public String getUrlTemplate() {
        return urlTemplate;
    }

    public List<Path> getClasspath() {
        return classpath;
    }

    public List<String> getUrlPrefixes() {
        return prefixes;
    }

    public List<String> getDriverPackages() {
        return packages;
    }

    public Properties getDefaultProperties() {
        Properties p = new Properties();
        p.putAll(defaults);
        return p;
    }
}
