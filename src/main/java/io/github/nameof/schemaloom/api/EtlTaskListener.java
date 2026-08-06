package io.github.nameof.schemaloom.api;

/** Lifecycle callbacks for an ETL task. Callbacks run synchronously on the task thread. */
public interface EtlTaskListener {
    default void onStarted(Object context, EtlProgress progress) {
    }

    default void onProgress(Object context, EtlProgress progress) {
    }

    default void onCompleted(Object context, EtlResult result) {
    }
}
