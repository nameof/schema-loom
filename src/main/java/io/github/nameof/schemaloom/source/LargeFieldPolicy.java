package io.github.nameof.schemaloom.source;

import java.sql.Types;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * JDBC 大字段读取策略；策略仅影响 Source 输出值，不改变字段 Schema。
 *
 * BLOB默认直接 SKIP，不会读取内容，maxBinaryBytes 不会生效
 * 配置 BLOB = COPY，未设置阈值读取完整 BLOB
 * 配置 BLOB = COPY，设置 maxBinaryBytes先检查长度，超过阈值则输出 null
 *
 * CLOB、LONGVARCHAR 等字符字段默认复制；如需限制其物化大小，应使用 maxTextChars 参数，
 * 如需直接跳过则显式配置对应字段或类型的 SKIP 动作。
 */
public final class LargeFieldPolicy {
    public enum Action { COPY, SKIP }

    private final Map<String, Action> fieldActions;
    private final Map<Integer, Action> jdbcTypeActions;
    private final Map<String, Action> typeNameActions;
    private final Long maxBinaryBytes;
    private final Long maxTextChars;

    private LargeFieldPolicy(Builder builder) {
        fieldActions = Collections.unmodifiableMap(new LinkedHashMap<String, Action>(builder.fieldActions));
        jdbcTypeActions = Collections.unmodifiableMap(new LinkedHashMap<Integer, Action>(builder.jdbcTypeActions));
        typeNameActions = Collections.unmodifiableMap(new LinkedHashMap<String, Action>(builder.typeNameActions));
        maxBinaryBytes = builder.maxBinaryBytes;
        maxTextChars = builder.maxTextChars;
    }

    public static Builder builder() { return new Builder(); }

    /**
     * 默认行为：BLOB：跳过读取
     * CLOB、TEXT、LONGTEXT、LONGVARCHAR等：照常读取
     */
    public static LargeFieldPolicy defaults() {
        return builder().jdbcType(Types.BLOB, Action.SKIP)
                .typeName("BLOB", Action.SKIP).build();
    }

    Action action(String fieldName, int jdbcType, String typeName) {
        Action action = fieldActions.get(normalize(fieldName));
        if (action != null) return action;
        action = typeNameActions.get(normalize(typeName));
        return action == null ? actionForJdbcType(jdbcType) : action;
    }

    Long maxBinaryBytes() { return maxBinaryBytes; }
    Long maxTextChars() { return maxTextChars; }

    boolean canReturnNull(String fieldName, int jdbcType, String typeName) {
        if (action(fieldName, jdbcType, typeName) == Action.SKIP) return true;
        return isBinary(jdbcType) ? maxBinaryBytes != null : isText(jdbcType) && maxTextChars != null;
    }

    private Action actionForJdbcType(int jdbcType) {
        Action action = jdbcTypeActions.get(jdbcType);
        return action == null ? Action.COPY : action;
    }

    private static boolean isBinary(int jdbcType) {
        return jdbcType == Types.BINARY || jdbcType == Types.VARBINARY || jdbcType == Types.LONGVARBINARY || jdbcType == Types.BLOB;
    }

    private static boolean isText(int jdbcType) {
        return jdbcType == Types.CHAR || jdbcType == Types.VARCHAR || jdbcType == Types.LONGVARCHAR
                || jdbcType == Types.NCHAR || jdbcType == Types.NVARCHAR || jdbcType == Types.LONGNVARCHAR || jdbcType == Types.CLOB;
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ENGLISH); }

    public static final class Builder {
        private final Map<String, Action> fieldActions = new LinkedHashMap<String, Action>();
        private final Map<Integer, Action> jdbcTypeActions = new LinkedHashMap<Integer, Action>();
        private final Map<String, Action> typeNameActions = new LinkedHashMap<String, Action>();
        private Long maxBinaryBytes;
        private Long maxTextChars;

        public Builder field(String name, Action action) { fieldActions.put(requiredName(name), requiredAction(action)); return this; }
        public Builder jdbcType(int type, Action action) { jdbcTypeActions.put(type, requiredAction(action)); return this; }
        public Builder typeName(String name, Action action) { typeNameActions.put(requiredName(name), requiredAction(action)); return this; }
        public Builder maxBinaryBytes(long value) { maxBinaryBytes = positive(value, "二进制阈值必须大于零"); return this; }
        public Builder maxTextChars(long value) { maxTextChars = positive(value, "文本阈值必须大于零"); return this; }
        public LargeFieldPolicy build() { return new LargeFieldPolicy(this); }

        private static String requiredName(String value) {
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("字段或类型名称不能为空");
            return normalize(value);
        }
        private static Action requiredAction(Action value) {
            if (value == null) throw new IllegalArgumentException("大字段动作不能为空");
            return value;
        }
        private static long positive(long value, String message) {
            if (value <= 0) throw new IllegalArgumentException(message);
            return value;
        }
    }
}
