/*
 *
 *  Copyright 2019 Robert Winkler and Bohdan Storozhuk
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.core.metrics;


import java.util.concurrent.TimeUnit;

/**
 * A {@link Metrics} implementation is backed by a sliding window that aggregates only the last
 * {@code N} calls.
 * <p>
 * The sliding window is implemented with a circular array of {@code N} measurements. If the time
 * window size is 10, the circular array has always 10 measurements.
 * <p>
 * The sliding window incrementally updates a total aggregation. The total aggregation is updated
 * incrementally when a new call outcome is recorded. When the oldest measurement is evicted, the
 * measurement is subtracted from the total aggregation. (Subtract-on-Evict)
 * <p>
 * The time to retrieve a Snapshot is constant 0(1), since the Snapshot is pre-aggregated and is
 * independent of the window size. The space requirement (memory consumption) of this implementation
 * should be O(n).
 */
public class FixedSizeSlidingWindowMetrics implements Metrics {

    private final int windowSize;
    private final TotalAggregation totalAggregation;
    private final Measurement[] measurements;
    int headIndex;

    /**
     * Creates a new {@link FixedSizeSlidingWindowMetrics} with the given window size.
     *
     * @param windowSize the window size
     */
    public FixedSizeSlidingWindowMetrics(int windowSize) {
        this.windowSize = windowSize;
        this.measurements = new Measurement[this.windowSize];
        this.headIndex = 0;
        for (int i = 0; i < this.windowSize; i++) {
            measurements[i] = new Measurement();
        }
        this.totalAggregation = new TotalAggregation();
    }

    /**
     * 记录调用的持续时间和结果。
     *
     * @param duration     the duration of the call
     * @param durationUnit the time unit of the duration
     * @param outcome      the outcome of the call
     */
    @Override
    public synchronized Snapshot record(long duration, TimeUnit durationUnit, Outcome outcome) {
        // 记录到总聚合中
        totalAggregation.record(duration, durationUnit, outcome);
        // 移动窗口并记录到最新的bucket中
        moveWindowByOne().record(duration, durationUnit, outcome);
        // 返回快照
        return new SnapshotImpl(totalAggregation);
    }

    public synchronized Snapshot getSnapshot() {
        return new SnapshotImpl(totalAggregation);
    }

    private Measurement moveWindowByOne() {
        // 移动headIndex到下一个bucket
        moveHeadIndexByOne();
        // 移除最新的bucket
        Measurement latestMeasurement = getLatestMeasurement();
        // 从总聚合中移除最新的bucket
        totalAggregation.removeBucket(latestMeasurement);
        // 重置最新的bucket
        latestMeasurement.reset();
        return latestMeasurement;
    }

    /**
     * 返回循环数组的头部分聚合。
     *
     * @return the head partial aggregation of the circular array
     */
    private Measurement getLatestMeasurement() {
        return measurements[headIndex];
    }

    /**
     * Moves the headIndex to the next bucket.
     */
    void moveHeadIndexByOne() {
        this.headIndex = (headIndex + 1) % windowSize;
    }
}