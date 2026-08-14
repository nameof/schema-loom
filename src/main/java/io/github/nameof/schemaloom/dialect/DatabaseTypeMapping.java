package io.github.nameof.schemaloom.dialect;

public final class DatabaseTypeMapping {
    private final boolean supported;
    private final String ddlType;

    private DatabaseTypeMapping(boolean supported, String ddlType) {
        this.supported = supported;
        this.ddlType = ddlType;
    }

    public static DatabaseTypeMapping supported(String ddlType) {
        if (ddlType == null || ddlType.trim().isEmpty()) throw new IllegalArgumentException("ddlType is blank");
        return new DatabaseTypeMapping(true, ddlType);
    }

    /**
     * 大多数情况下，尽量不要做unsupported声明，而是通过代码转换为兼容的类型
     * 例如某个数据库不支持带时区的时间类型，尽量不要抛出unsupported导致拒绝任务，而是通过代码将数据转为无时区的时间类型，来做到supported
     */
    public static DatabaseTypeMapping unsupported() { return new DatabaseTypeMapping(false, null); }
    public boolean isSupported() { return supported; }
    public String getDdlType() { return ddlType; }
}
