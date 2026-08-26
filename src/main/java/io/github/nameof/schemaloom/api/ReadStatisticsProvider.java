package io.github.nameof.schemaloom.api;

/** 提供读取阶段统计的可选能力。 */
public interface ReadStatisticsProvider {
    ReadStatistics getReadStatistics();
}
