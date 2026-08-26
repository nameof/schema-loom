package io.github.nameof.schemaloom.api;

/** 提供写入阶段统计的可选能力。 */
public interface WriteStatisticsProvider {
    WriteStatistics getWriteStatistics();
}
