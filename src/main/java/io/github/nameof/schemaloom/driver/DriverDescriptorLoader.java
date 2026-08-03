package io.github.nameof.schemaloom.driver;

import io.github.nameof.schemaloom.api.SchemaLoomException;
import cn.hutool.core.util.StrUtil;
import cn.hutool.setting.dialect.Props;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;

public final class DriverDescriptorLoader {
    /** 从 classpath 下的 drivers 资源目录加载驱动描述；非文件系统资源不支持动态读取。 */
    public List<DriverDescriptor> load() {
        URLResource resource = URLResource.from(Thread.currentThread().getContextClassLoader().getResource("drivers"));
        if (resource == null) return Collections.emptyList();
        return load(resource.path);
    }

    /** 读取目录中的 properties 描述文件，并将相对 classpath 解析为受控的本地文件路径。 */
    public List<DriverDescriptor> load(Path root) {
        try {
            if (root == null) return load();
            if (!Files.isDirectory(root)) return Collections.emptyList();
            List<DriverDescriptor> out = new ArrayList<DriverDescriptor>();
            Set<String> ids = new HashSet<String>();
            DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.properties");
            try {
                for (Path p : files) {
                    Props x = new Props(p.toFile(), java.nio.charset.StandardCharsets.UTF_8);
                    String id = req(x, "id", p), dc = req(x, "driverClass", p);
                    DatabaseType type = DatabaseType.valueOf(req(x, "databaseType", p).toUpperCase(Locale.ENGLISH));
                    List<Path> cp = new ArrayList<Path>();
                    for (String s : split(x.getStr("classpath"))) {
                        Path q = root.resolve(s).normalize();
                        // 规范化后仍必须位于 root 下，避免描述文件通过 .. 引用目录外的任意文件。
                        if (!q.startsWith(root.normalize()) || !Files.isRegularFile(q))
                            throw new SchemaLoomException("invalid driver classpath: " + s);
                        cp.add(q);
                    }
                    if (cp.isEmpty()) throw new SchemaLoomException("empty classpath: " + id);
                    if (!ids.add(id)) throw new SchemaLoomException("duplicate driver id: " + id);
                    Properties defaults = new Properties();
                    String d = x.getStr("defaultProperties", "");
                    for (String pair : StrUtil.splitTrim(d, ';')) {
                        int i = pair.indexOf('=');
                        // 只按第一个等号拆分，使属性值本身可以继续包含等号。
                        if (i <= 0) throw new SchemaLoomException("invalid defaultProperties: " + id);
                        defaults.setProperty(StrUtil.trim(pair.substring(0, i)), StrUtil.trim(pair.substring(i + 1)));
                    }
                    out.add(new DriverDescriptor(id, type, dc, x.getInt("priority", 0), x.getStr("serverVersionRange", ""), x.getStr("urlTemplate", ""), cp, split(x.getStr("urlPrefixes")), split(x.getStr("driverPackages")), defaults));
                }
            } finally {
                files.close();
            }
            return out;
        } catch (IOException | IllegalArgumentException e) {
            throw new SchemaLoomException("cannot load driver descriptors", e);
        }
    }

    /** 读取必填配置项，并统一去除首尾空白。 */
    private static String req(Props p, String k, Path path) {
        String v = p.getStr(k);
        if (StrUtil.isBlank(v)) throw new SchemaLoomException("missing " + k + " in " + path);
        return StrUtil.trim(v);
    }

    /** 将逗号分隔配置转换为去空白后的列表；空配置表示没有候选项。 */
    private static List<String> split(String s) {
        return StrUtil.isBlank(s) ? Collections.<String>emptyList() : StrUtil.splitTrim(s, ',');
    }

    private static final class URLResource {
        private final Path path;
        private URLResource(Path path) { this.path = path; }

        /** URL 只有在 exploded 目录场景下才能通过 java.nio.file 直接遍历。 */
        private static URLResource from(java.net.URL url) {
            if (url == null) return null;
            if (!"file".equalsIgnoreCase(url.getProtocol()))
                throw new SchemaLoomException("drivers resource must be an exploded filesystem directory: " + url);
            try { return new URLResource(Paths.get(new URI(url.toString()))); }
            catch (URISyntaxException e) { throw new SchemaLoomException("invalid drivers resource URL", e); }
        }
    }
}
