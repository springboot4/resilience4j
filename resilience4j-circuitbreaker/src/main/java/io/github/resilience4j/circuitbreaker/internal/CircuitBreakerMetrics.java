/*
 *
 *  Copyright 2016 Robert Winkler and Bohdan Storozhuk
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
package io.github.resilience4j.circuitbreaker.internal;


import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.metrics.FixedSizeSlidingWindowMetrics;
import io.github.resilience4j.core.metrics.Metrics;
import io.github.resilience4j.core.metrics.SlidingTimeWindowMetrics;
import io.github.resilience4j.core.metrics.Snapshot;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static io.github.resilience4j.core.metrics.Metrics.Outcome;

/**
 * 断路器指标
 */
class CircuitBreakerMetrics implements CircuitBreaker.Metrics {

    private final Metrics metrics;
    // 故障率阀值
    private final float failureRateThreshold;
    // 慢调用阀值
    private final float slowCallRateThreshold;
    // 慢调用持续时间阀值（以纳秒为单位）
    private final long slowCallDurationThresholdInNanos;
    // 记录不允许的调用次数
    private final LongAdder numberOfNotPermittedCalls;
    // 最小调用次数
    private int minimumNumberOfCalls;

    private CircuitBreakerMetrics(int slidingWindowSize,
        CircuitBreakerConfig.SlidingWindowType slidingWindowType,
        CircuitBreakerConfig circuitBreakerConfig,
        Clock clock) {
        if (slidingWindowType == CircuitBreakerConfig.SlidingWindowType.COUNT_BASED) {
            this.metrics = new FixedSizeSlidingWindowMetrics(slidingWindowSize);
            this.minimumNumberOfCalls = Math
                .min(circuitBreakerConfig.getMinimumNumberOfCalls(), slidingWindowSize);
        } else {
            this.metrics = new SlidingTimeWindowMetrics(slidingWindowSize, clock);
            this.minimumNumberOfCalls = circuitBreakerConfig.getMinimumNumberOfCalls();
        }
        this.failureRateThreshold = circuitBreakerConfig.getFailureRateThreshold();
        this.slowCallRateThreshold = circuitBreakerConfig.getSlowCallRateThreshold();
        this.slowCallDurationThresholdInNanos = circuitBreakerConfig.getSlowCallDurationThreshold()
            .toNanos();
        this.numberOfNotPermittedCalls = new LongAdder();
    }

    private CircuitBreakerMetrics(int slidingWindowSize,
        CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        this(slidingWindowSize, circuitBreakerConfig.getSlidingWindowType(), circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forClosed(CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return new CircuitBreakerMetrics(circuitBreakerConfig.getSlidingWindowSize(),
            circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forHalfOpen(int permittedNumberOfCallsInHalfOpenState,
        CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return new CircuitBreakerMetrics(permittedNumberOfCallsInHalfOpenState,
            CircuitBreakerConfig.SlidingWindowType.COUNT_BASED, circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forForcedOpen(CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return new CircuitBreakerMetrics(0, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED,
            circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forDisabled(CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return new CircuitBreakerMetrics(0, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED,
            circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forMetricsOnly(CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return forClosed(circuitBreakerConfig, clock);
    }

    /**
     * Records a call which was not permitted, because the CircuitBreaker state is OPEN.
     */
    void onCallNotPermitted() {
        numberOfNotPermittedCalls.increment();
    }

    /**
     * Records a successful call and checks if the thresholds are exceeded.
     *
     * @return the result of the check
     */
    public Result onSuccess(long duration, TimeUnit durationUnit) {
        Snapshot snapshot;
        if (durationUnit.toNanos(duration) > slowCallDurationThresholdInNanos) {
            // 记录慢速成功调用
            snapshot = metrics.record(duration, durationUnit, Outcome.SLOW_SUCCESS);
        } else {
            // 记录成功调用
            snapshot = metrics.record(duration, durationUnit, Outcome.SUCCESS);
        }

        // 检查故障率是否高于阈值或慢速呼叫百分比是否高于阈值
        return checkIfThresholdsExceeded(snapshot);
    }

    /**
     * 记录一个失败的调用，并检查是否超过了阈值。
     *
     * @return the result of the check
     */
    public Result onError(long duration, TimeUnit durationUnit) {
        Snapshot snapshot;
        if (durationUnit.toNanos(duration) > slowCallDurationThresholdInNanos) {
            // 记录慢速调用
            snapshot = metrics.record(duration, durationUnit, Outcome.SLOW_ERROR);
        } else {
            // 记录失败调用
            snapshot = metrics.record(duration, durationUnit, Outcome.ERROR);
        }

        // 检查故障率是否高于阈值或慢速呼叫百分比是否高于阈值
        return checkIfThresholdsExceeded(snapshot);
    }

    /**
     * 检查故障率是否高于阈值或慢速呼叫百分比是否高于阈值
     *
     * @param snapshot a metrics snapshot
     * @return false, if the thresholds haven't been exceeded.
     */
    private Result checkIfThresholdsExceeded(Snapshot snapshot) {
        float failureRateInPercentage = getFailureRate(snapshot);
        float slowCallsInPercentage = getSlowCallRate(snapshot);

        // 低于最低调用次数阈值
        if (failureRateInPercentage == -1 || slowCallsInPercentage == -1) {
            return Result.BELOW_MINIMUM_CALLS_THRESHOLD;
        }

        // 故障率和慢速调用率都超过阈值
        if (failureRateInPercentage >= failureRateThreshold
            && slowCallsInPercentage >= slowCallRateThreshold) {
            return Result.ABOVE_THRESHOLDS;
        }

        // 故障率超过阈值
        if (failureRateInPercentage >= failureRateThreshold) {
            return Result.FAILURE_RATE_ABOVE_THRESHOLDS;
        }

        // 慢速调用率超过阈值
        if (slowCallsInPercentage >= slowCallRateThreshold) {
            return Result.SLOW_CALL_RATE_ABOVE_THRESHOLDS;
        }

        // 故障率低于阈值
        return Result.BELOW_THRESHOLDS;
    }

    private float getSlowCallRate(Snapshot snapshot) {
        // 调用次数小于最小调用次数阈值 返回-1
        int bufferedCalls = snapshot.getTotalNumberOfCalls();
        if (bufferedCalls == 0 || bufferedCalls < minimumNumberOfCalls) {
            return -1.0f;
        }

        // 返回慢速调用率
        return snapshot.getSlowCallRate();
    }

    private float getFailureRate(Snapshot snapshot) {
        // 调用次数小于最小调用次数阈值 返回-1
        int bufferedCalls = snapshot.getTotalNumberOfCalls();
        if (bufferedCalls == 0 || bufferedCalls < minimumNumberOfCalls) {
            return -1.0f;
        }

        // 返回失败率
        return snapshot.getFailureRate();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float getFailureRate() {
        return getFailureRate(metrics.getSnapshot());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float getSlowCallRate() {
        return getSlowCallRate(metrics.getSnapshot());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfSuccessfulCalls() {
        return this.metrics.getSnapshot().getNumberOfSuccessfulCalls();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfBufferedCalls() {
        return this.metrics.getSnapshot().getTotalNumberOfCalls();
    }

    @Override
    public int getNumberOfFailedCalls() {
        return this.metrics.getSnapshot().getNumberOfFailedCalls();
    }

    @Override
    public int getNumberOfSlowCalls() {
        return this.metrics.getSnapshot().getTotalNumberOfSlowCalls();
    }

    @Override
    public int getNumberOfSlowSuccessfulCalls() {
        return this.metrics.getSnapshot().getNumberOfSlowSuccessfulCalls();
    }

    @Override
    public int getNumberOfSlowFailedCalls() {
        return this.metrics.getSnapshot().getNumberOfSlowFailedCalls();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNumberOfNotPermittedCalls() {
        return this.numberOfNotPermittedCalls.sum();
    }

    enum Result {
        BELOW_THRESHOLDS,
        FAILURE_RATE_ABOVE_THRESHOLDS,
        SLOW_CALL_RATE_ABOVE_THRESHOLDS,
        ABOVE_THRESHOLDS,
        BELOW_MINIMUM_CALLS_THRESHOLD;

        // 是否超过阈值
        public static boolean hasExceededThresholds(Result result) {
            return hasFailureRateExceededThreshold(result) ||
                hasSlowCallRateExceededThreshold(result);
        }

        // 故障率是否超过阈值
        public static boolean hasFailureRateExceededThreshold(Result result) {
            return result == ABOVE_THRESHOLDS || result == FAILURE_RATE_ABOVE_THRESHOLDS;
        }

        // 慢速调用率是否超过阈值
        public static boolean hasSlowCallRateExceededThreshold(Result result) {
            return result == ABOVE_THRESHOLDS || result == SLOW_CALL_RATE_ABOVE_THRESHOLDS;
        }
    }
}
