package io.github.nameof.schemaloom.internal;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;

/** 日志内部约定：统一运行标识，并避免把连接凭据带入异常日志。 */
public final class LoggingSupport {
    private LoggingSupport() {
    }

    public static String runId() {
        return StrUtil.subPre(IdUtil.fastSimpleUUID(), 12);
    }

    public static String message(Throwable error) {
        if (error == null) return "";
        return message(error.getMessage());
    }

    public static String message(String value) {
        if (StrUtil.isBlank(value)) return "";
        String sanitized = ReUtil.replaceAll(value,
                "(?i)(password|passwd|pwd|token|authorization)\\s*[=:]\\s*[^,; ]+",
                "$1=<redacted>");
        return StrUtil.subPre(sanitized, 500);
    }

    /** 复制异常链和堆栈，但将每层异常消息脱敏后再交给日志后端。 */
    public static Throwable safe(Throwable error) {
        if (error == null) return null;
        SafeLogException copy = new SafeLogException(error.getClass().getName() + ": " + message(error),
                safe(error.getCause()));
        copy.setStackTrace(error.getStackTrace());
        return copy;
    }

    private static final class SafeLogException extends RuntimeException {
        private SafeLogException(String message, Throwable cause) {
            super(message, cause, true, true);
        }
    }
}
