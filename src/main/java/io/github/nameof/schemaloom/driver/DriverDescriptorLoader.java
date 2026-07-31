package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.SchemaLoomException;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class DriverDescriptorLoader {
    public List<DriverDescriptor> load(Path root) {
        try {
            if (root == null) root = Paths.get(System.getProperty("user.dir"), "drivers");
            if (!Files.isDirectory(root)) return Collections.emptyList();
            List<DriverDescriptor> out = new ArrayList<DriverDescriptor>();
            Set<String> ids = new HashSet<String>();
            DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.properties");
            try {
                for (Path p : files) {
                    Properties x = new Properties();
                    InputStream in = Files.newInputStream(p);
                    try {
                        x.load(in);
                    } finally {
                        in.close();
                    }
                    String id = req(x, "id", p), dc = req(x, "driverClass", p);
                    DatabaseType type = DatabaseType.valueOf(req(x, "databaseType", p).toUpperCase(Locale.ENGLISH));
                    List<Path> cp = new ArrayList<Path>();
                    for (String s : split(x.getProperty("classpath"))) {
                        Path q = root.resolve(s).normalize();
                        if (!q.startsWith(root.normalize()) || !Files.isRegularFile(q))
                            throw new SchemaLoomException("invalid driver classpath: " + s);
                        cp.add(q);
                    }
                    if (cp.isEmpty()) throw new SchemaLoomException("empty classpath: " + id);
                    if (!ids.add(id)) throw new SchemaLoomException("duplicate driver id: " + id);
                    Properties defaults = new Properties();
                    String d = x.getProperty("defaultProperties", "");
                    for (String pair : d.split(";")) {
                        if (pair.trim().isEmpty()) continue;
                        int i = pair.indexOf('=');
                        if (i <= 0) throw new SchemaLoomException("invalid defaultProperties: " + id);
                        defaults.setProperty(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
                    }
                    out.add(new DriverDescriptor(id, type, dc, Integer.parseInt(x.getProperty("priority", "0")), x.getProperty("serverVersionRange", ""), cp, split(x.getProperty("urlPrefixes")), split(x.getProperty("driverPackages")), defaults));
                }
            } finally {
                files.close();
            }
            return out;
        } catch (IOException | IllegalArgumentException e) {
            throw new SchemaLoomException("cannot load driver descriptors", e);
        }
    }

    private static String req(Properties p, String k, Path path) {
        String v = p.getProperty(k);
        if (v == null || v.trim().isEmpty()) throw new SchemaLoomException("missing " + k + " in " + path);
        return v.trim();
    }

    private static List<String> split(String s) {
        List<String> o = new ArrayList<String>();
        if (s != null) for (String v : s.split(",")) if (!v.trim().isEmpty()) o.add(v.trim());
        return o;
    }
}
