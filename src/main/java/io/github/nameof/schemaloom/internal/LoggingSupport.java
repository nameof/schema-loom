package io.github.nameof.schemaloom.internal;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;

/** 日志内部约定：统一运行标识，并避免把连接凭据带入异常日志。 */
public final class LoggingSupport {
    private static final ThreadLocal<String> CURRENT_RUN_ID = new ThreadLocal<String>();
    private LoggingSupport() {
    }

    public static String runId() {
        return StrUtil.subPre(IdUtil.fastSimpleUUID(), 12);
    }

    public static String bindRunId(String runId) {
        String previous = CURRENT_RUN_ID.get();
        CURRENT_RUN_ID.set(runId);
        return previous;
    }

    public static String currentRunId() {
        return CURRENT_RUN_ID.get();
    }

    public static void restoreRunId(String previous) {
        if (previous == null) CURRENT_RUN_ID.remove();
        else CURRENT_RUN_ID.set(previous);
    }

    public static String message(Throwable error) {
        if (error == null) return "";
        return message(error.getMessage());
    }

    public static String message(String value) {
        if (StrUtil.isBlank(value)) return "";
        String sanitized = ReUtil.replaceAll(value,
                "(?i)(password|passwd|pwd|token|authorization|username|user|secret|access[_-]?key)\\s*[=:]\\s*[^,;\\s&]+",
                "$1=<redacted>");
        sanitized = ReUtil.replaceAll(sanitized,
                "(?i)(jdbc:[a-z0-9:]+://)[^/@\\s]+@",
                "$1<redacted>@");
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
