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
package io.github.resilience4j.ratelimiter.internal;

import static java.lang.Long.min;
import static java.lang.System.nanoTime;
import static java.lang.Thread.currentThread;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.locks.LockSupport.parkNanos;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnDrainedEvent;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnFailureEvent;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnSuccessEvent;

/**
 * {@link AtomicRateLimiter} splits all nanoseconds from the start of epoch into cycles.
 * <p>Each cycle has duration of {@link RateLimiterConfig#getLimitRefreshPeriod} in nanoseconds.
 * <p>By contract on start of each cycle {@link AtomicRateLimiter} should
 * set {@link State#activePermissions} to {@link RateLimiterConfig#getLimitForPeriod}. For the {@link
 * AtomicRateLimiter} callers it is really looks so, but under the hood there is some optimisations
 * that will skip this refresh if {@link AtomicRateLimiter} is not used actively.
 * <p>All {@link AtomicRateLimiter} updates are atomic and state is encapsulated in {@link
 * AtomicReference} to {@link AtomicRateLimiter.State}
 */
public class AtomicRateLimiter implements RateLimiter {

    private final long nanoTimeStart;
    private final String name;
    private final AtomicInteger waitingThreads;
    private final AtomicReference<State> state;
    private final Map<String, String> tags;
    private final RateLimiterEventProcessor eventProcessor;

    public AtomicRateLimiter(String name, RateLimiterConfig rateLimiterConfig) {
        this(name, rateLimiterConfig, emptyMap());
    }

    public AtomicRateLimiter(String name, RateLimiterConfig rateLimiterConfig,
                             Map<String, String> tags) {
        this.name = name;
        this.tags = tags;
        this.nanoTimeStart = nanoTime();

        waitingThreads = new AtomicInteger(0);
        state = new AtomicReference<>(new State(
            rateLimiterConfig, 0, rateLimiterConfig.getLimitForPeriod(), 0
        ));
        eventProcessor = new RateLimiterEventProcessor();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void changeTimeoutDuration(final Duration timeoutDuration) {
        RateLimiterConfig newConfig = RateLimiterConfig.from(state.get().config)
            .timeoutDuration(timeoutDuration)
            .build();
        state.updateAndGet(currentState -> new State(
            newConfig, currentState.activeCycle, currentState.activePermissions,
            currentState.nanosToWait
        ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void changeLimitForPeriod(final int limitForPeriod) {
        RateLimiterConfig newConfig = RateLimiterConfig.from(state.get().config)
            .limitForPeriod(limitForPeriod)
            .build();
        state.updateAndGet(currentState -> new State(
            newConfig, currentState.activeCycle, currentState.activePermissions,
            currentState.nanosToWait
        ));
    }

    /**
     * Calculates time elapsed from the class loading.
     */
    private long currentNanoTime() {
        return nanoTime() - nanoTimeStart;
    }

    long getNanoTimeStart() {
        return this.nanoTimeStart;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean acquirePermission(final int permits) {
        // 超时持续时间，表示请求等待执行的最长时间，单位为毫秒，0 表示无限期等待
        long timeoutInNanos = state.get().config.getTimeoutDuration().toNanos();

        // 更新状态 这是最核心的方法 计算周期内剩余的权限以及需要等待的时间
        State modifiedState = updateStateWithBackOff(permits, timeoutInNanos);

        // 等待指定时间来获取权限
        boolean result = waitForPermissionIfNecessary(timeoutInNanos, modifiedState.nanosToWait);

        publishRateLimiterAcquisitionEvent(result, permits);

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long reservePermission(final int permits) {
        long timeoutInNanos = state.get().config.getTimeoutDuration().toNanos();
        State modifiedState = updateStateWithBackOff(permits, timeoutInNanos);

        boolean canAcquireImmediately = modifiedState.nanosToWait <= 0;
        if (canAcquireImmediately) {
            publishRateLimiterAcquisitionEvent(true, permits);
            return 0;
        }

        boolean canAcquireInTime = timeoutInNanos >= modifiedState.nanosToWait;
        if (canAcquireInTime) {
            publishRateLimiterAcquisitionEvent(true, permits);
            return modifiedState.nanosToWait;
        }

        publishRateLimiterAcquisitionEvent(false, permits);
        return -1;
    }

    @Override
    public void drainPermissions() {
        AtomicRateLimiter.State prev;
        AtomicRateLimiter.State next;
        do {
            prev = state.get();
            next = calculateNextState(prev.activePermissions, 0, prev);
        } while (!compareAndSet(prev, next));
        if (eventProcessor.hasConsumers()) {
            eventProcessor.consumeEvent(new RateLimiterOnDrainedEvent(name, Math.min(prev.activePermissions, 0)));
        }
    }

    /**
     * 该方法是用于原子更新当前状态（State）的，更新的结果是通过应用AtomicRateLimiter类中的calculateNextState方法得到的。
     * 与普通的AtomicReference类的updateAndGet方法不同的是，该方法具有常数退避（constant back off）的特性。
     * 这意味着，在尝试一次compareAndSet操作后，该方法会在再次尝试之前等待一段时间。
     *
     * Atomically updates the current {@link State} with the results of applying the {@link
     * AtomicRateLimiter#calculateNextState}, returning the updated {@link State}. It differs from
     * {@link AtomicReference#updateAndGet(UnaryOperator)} by constant back off. It means that after
     * one try to {@link AtomicReference#compareAndSet(Object, Object)} this method will wait for a
     * while before try one more time. This technique was originally described in this
     * <a href="https://arxiv.org/abs/1305.5800"> paper</a>
     * and showed great results with {@link AtomicRateLimiter} in benchmark tests.
     *
     * @param timeoutInNanos a side-effect-free function
     * @return the updated value
     */
    private State updateStateWithBackOff(final int permits, final long timeoutInNanos) {
        AtomicRateLimiter.State prev;
        AtomicRateLimiter.State next;

        // cas更新当前状态
        do {
            // 当前状态
            prev = state.get();

            // 计算下一个状态
            next = calculateNextState(permits, timeoutInNanos, prev);
        } while (!compareAndSet(prev, next));

        return next;
    }

    /**
     * Atomically sets the value to the given updated value if the current value {@code ==} the
     * expected value. It differs from {@link AtomicReference#updateAndGet(UnaryOperator)} by
     * constant back off. It means that after one try to {@link AtomicReference#compareAndSet(Object,
     * Object)} this method will wait for a while before try one more time. This technique was
     * originally described in this
     * <a href="https://arxiv.org/abs/1305.5800"> paper</a>
     * and showed great results with {@link AtomicRateLimiter} in benchmark tests.
     *
     * @param current the expected value
     * @param next    the new value
     * @return {@code true} if successful. False return indicates that the actual value was not
     * equal to the expected value.
     */
    private boolean compareAndSet(final State current, final State next) {
        if (state.compareAndSet(current, next)) {
            return true;
        }
        parkNanos(1); // back-off
        return false;
    }

    /**
     * 一个无副作用的函数，可以从当前计算下一个｛@link State｝。它决定
     * 您应该等待给定数量的许可证并为您保留的时间，
     * 如果你能等足够长的时间。
     *
     * @param permits        许可证数量
     * @param timeoutInNanos 调用方可以等待权限的最长时间（以纳秒为单位）
     * @param activeState    ｛@link AtomicRateLimiter｝ 的当前状态
     * @return next {@link State}
     */
    private State calculateNextState(final int permits, final long timeoutInNanos,
                                     final State activeState) {
        // 配置的限流周期
        long cyclePeriodInNanos = activeState.config.getLimitRefreshPeriod().toNanos();
        // 每个时间周期的限流阈值，表示每个时间周期内允许的最大请求数
        int permissionsPerCycle = activeState.config.getLimitForPeriod();

        // 当前时间
        long currentNanos = currentNanoTime();
        // 当前所处周期
        long currentCycle = currentNanos / cyclePeriodInNanos;

        // 激活的周期
        long nextCycle = activeState.activeCycle;
        // 激活的周期所剩余的权限数
        int nextPermissions = activeState.activePermissions;


        // 如果当前所处的周期和上次激活的限流周期不处在一个周期中 重新计算 注意上个状态中权限数可能是负的 欠的债在这里要还
        if (nextCycle != currentCycle) {
            // 当前与激活周期的差额
            long elapsedCycles = currentCycle - nextCycle;

            // 区间内累计权限
            long accumulatedPermissions = elapsedCycles * permissionsPerCycle;

            // 赋值下一个周期
            nextCycle = currentCycle;

            // 下一个周期的权限数  注意上个状态中权限数可能是负的 欠的债在这里要还 也就是min(x,y)的作用
            nextPermissions = (int) min(nextPermissions + accumulatedPermissions,
                permissionsPerCycle);
        }

        // 计算等待所需权限许可证累积的时间
        long nextNanosToWait = nanosToWaitForPermission(
            permits, cyclePeriodInNanos, permissionsPerCycle, nextPermissions, currentNanos,
            currentCycle
        );

        State nextState = reservePermissions(activeState.config, permits, timeoutInNanos, nextCycle,
            nextPermissions, nextNanosToWait);

        return nextState;
    }

    /**
     * 计算等待所需权限许可证累积的时间
     *
     * @param permits              所需权限的许可证
     * @param cyclePeriodInNanos   限流周期
     * @param permissionsPerCycle  每个周期的最大阀值
     * @param availablePermissions 当前可用权限，如果有已保留权限
     * @param currentNanos         当前时间（纳秒）
     * @param currentCycle         当前｛@link AtomicRateLimiter｝周期    @return nanoseconds to
     *                             wait for the next permission
     */
    private long nanosToWaitForPermission(final int permits, final long cyclePeriodInNanos,
                                          final int permissionsPerCycle,
                                          final int availablePermissions, final long currentNanos, final long currentCycle) {
        // 当前周期可用权限大于需要的权限 返回0 表示不需要等待
        if (availablePermissions >= permits) {
            return 0L;
        }

        // 走到下面说明本周期内的权限肯定是不够了 需要累计等待几个周期获取足够的权限

        // 下一个循环时间（纳米）
        long nextCycleTimeInNanos = (currentCycle + 1) * cyclePeriodInNanos;
        // 下一个周期和当前周期时间差
        long nanosToNextCycle = nextCycleTimeInNanos - currentNanos;
        // 当前周期到下一个周期可用的权限
        int permissionsAtTheStartOfNextCycle = availablePermissions + permissionsPerCycle;

        // （  permits - availablePermissions  + 1 ） / permissionsPerCycle 即 获取需要的权限需要几个周期
        int fullCyclesToWait = divCeil(-(permissionsAtTheStartOfNextCycle - permits), permissionsPerCycle);

        // 等待的时间
        return (fullCyclesToWait * cyclePeriodInNanos) + nanosToNextCycle;
    }

    /**
     * Divide two integers and round result to the bigger near mathematical integer.
     *
     * @param x - should be > 0
     * @param y - should be > 0
     */
    private static int divCeil(int x, int y) {
        return (x + y - 1) / y;
    }

    /**
     * 确定调用方是否可以在超时前获取权限，然后创建
     * 对应的{@link State}。只有当调用方可以成功等待时才保留权限准许
     *
     * @param config
     * @param permits        要获取的许可证
     * @param timeoutInNanos 调用方可以等待权限的最长时间（以纳秒为单位）
     * @param cycle          新｛@link State｝ 的周期
     * @param permissions    新｛@link State｝ 的权限
     * @param nanosToWait    等待下一个权限的时间
     * @return new {@link State} with possibly reserved permissions and time to wait
     */
    private State reservePermissions(final RateLimiterConfig config, final int permits,
                                     final long timeoutInNanos,
                                     final long cycle, final int permissions, final long nanosToWait) {
        // 调用方可以等待怎么长时间
        boolean canAcquireInTime = timeoutInNanos >= nanosToWait;
        // 当前周期还剩下的权限
        int permissionsWithReservation = permissions;
        if (canAcquireInTime) {
            // 下一个状态还剩下的权限
            permissionsWithReservation -= permits;
        }

        // 创建新的状态
        return new State(config, cycle, permissionsWithReservation, nanosToWait);
    }

    /**
     * 如果nanosToWait大于0，它会尝试为nanosToWait停放{@link Thread}，但不会比timeoutInNanos的时间更长。
     *
     * @param timeoutInNanos 调用发可以等待的最长时间
     * @param nanosToWait    调用方需要等待纳秒
     * @return true if caller was able to wait for nanosToWait without {@link Thread#interrupt} and
     * not exceed timeout
     */
    private boolean waitForPermissionIfNecessary(final long timeoutInNanos,
                                                 final long nanosToWait) {
        // 可以立即获取
        boolean canAcquireImmediately = nanosToWait <= 0;
        // 可以获取
        boolean canAcquireInTime = timeoutInNanos >= nanosToWait;

        if (canAcquireImmediately) {
            return true;
        }

        if (canAcquireInTime) {
            return waitForPermission(nanosToWait);
        }
        waitForPermission(timeoutInNanos);
        return false;
    }

    /**
     * Parks {@link Thread} for nanosToWait.
     * <p>If the current thread is {@linkplain Thread#interrupted}
     * while waiting for a permit then it won't throw {@linkplain InterruptedException}, but its
     * interrupt status will be set.
     *
     * @param nanosToWait nanoseconds caller need to wait
     * @return true if caller was not {@link Thread#interrupted} while waiting
     */
    private boolean waitForPermission(final long nanosToWait) {
        // 等待线程数加1
        waitingThreads.incrementAndGet();
        // 要等待到的时间
        long deadline = currentNanoTime() + nanosToWait;
        boolean wasInterrupted = false;
        while (currentNanoTime() < deadline && !wasInterrupted) {
            long sleepBlockDuration = deadline - currentNanoTime();
            parkNanos(sleepBlockDuration);
            wasInterrupted = Thread.interrupted();
        }

        // 等待线程数减1
        waitingThreads.decrementAndGet();
        if (wasInterrupted) {
            currentThread().interrupt();
        }

        return !wasInterrupted;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RateLimiterConfig getRateLimiterConfig() {
        return state.get().config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> getTags() {
        return tags;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Metrics getMetrics() {
        return new AtomicRateLimiterMetrics();
    }

    @Override
    public EventPublisher getEventPublisher() {
        return eventProcessor;
    }

    @Override
    public String toString() {
        return "AtomicRateLimiter{" +
            "name='" + name + '\'' +
            ", rateLimiterConfig=" + state.get().config +
            '}';
    }

    /**
     * Get the enhanced Metrics with some implementation specific details.
     *
     * @return the detailed metrics
     */
    public AtomicRateLimiterMetrics getDetailedMetrics() {
        return new AtomicRateLimiterMetrics();
    }

    private void publishRateLimiterAcquisitionEvent(boolean permissionAcquired, int permits) {
        if (!eventProcessor.hasConsumers()) {
            return;
        }
        if (permissionAcquired) {
            eventProcessor.consumeEvent(new RateLimiterOnSuccessEvent(name, permits));
            return;
        }
        eventProcessor.consumeEvent(new RateLimiterOnFailureEvent(name, permits));
    }

    /**
     * <p>{@link AtomicRateLimiter.State} represents immutable state of {@link AtomicRateLimiter}
     * where:
     * <ul>
     * <li>activeCycle - {@link AtomicRateLimiter} cycle number that was used
     * by the last {@link AtomicRateLimiter#acquirePermission()} call.</li>
     * <p>
     * <li>activePermissions - count of available permissions after
     * the last {@link AtomicRateLimiter#acquirePermission()} call.
     * Can be negative if some permissions where reserved.</li>
     * <p>
     * <li>nanosToWait - count of nanoseconds to wait for permission for
     * the last {@link AtomicRateLimiter#acquirePermission()} call.</li>
     * </ul>
     */
    private static class State {

        private final RateLimiterConfig config;

        // 当前周期
        private final long activeCycle;
        // 当前周期剩余的权限 这tm可能是负的
        private final int activePermissions;
        // 等待的时间
        private final long nanosToWait;

        private State(RateLimiterConfig config,
                      final long activeCycle, final int activePermissions, final long nanosToWait) {
            this.config = config;
            this.activeCycle = activeCycle;
            this.activePermissions = activePermissions;
            this.nanosToWait = nanosToWait;
        }

    }

    /**
     * Enhanced {@link Metrics} with some implementation specific details
     */
    public class AtomicRateLimiterMetrics implements Metrics {

        private AtomicRateLimiterMetrics() {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getNumberOfWaitingThreads() {
            return waitingThreads.get();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getAvailablePermissions() {
            State currentState = state.get();
            State estimatedState = calculateNextState(1, -1, currentState);
            return estimatedState.activePermissions;
        }

        /**
         * @return estimated time duration in nanos to wait for the next permission
         */
        public long getNanosToWait() {
            State currentState = state.get();
            State estimatedState = calculateNextState(1, -1, currentState);
            return estimatedState.nanosToWait;
        }

        /**
         * @return estimated current cycle
         */
        public long getCycle() {
            State currentState = state.get();
            State estimatedState = calculateNextState(1, -1, currentState);
            return estimatedState.activeCycle;
        }

    }
}
