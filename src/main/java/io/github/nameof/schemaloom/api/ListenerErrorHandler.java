package io.github.nameof.schemaloom.api;

public interface ListenerErrorHandler {
    void onError(ListenerCallback callback, Object context, Throwable error);
}
