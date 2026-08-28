package io.github.nameof.schemaloom.source;

/** 文件字段值无法按 Schema 解码时的处理方式。 */
public enum InvalidValuePolicy {
    FAIL_FAST,
    SKIP_VALUE,
    SKIP_ROW
}
