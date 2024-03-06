/*
 *
 *  Copyright 2017: Robert Winkler
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
package io.github.resilience4j.circuitbreaker;

import io.github.resilience4j.circuitbreaker.event.*;
import io.github.resilience4j.circuitbreaker.internal.CircuitBreakerStateMachine;
import io.github.resilience4j.core.EventConsumer;
import io.github.resilience4j.core.functions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A CircuitBreaker instance is thread-safe can be used to decorate multiple requests.
 * <p>
 * A {@link CircuitBreaker} manages the state of a backend system. The CircuitBreaker is implemented
 * via a finite state machine with five states: CLOSED, OPEN, HALF_OPEN, DISABLED AND FORCED_OPEN.
 * The CircuitBreaker does not know anything about the backend's state by itself, but uses the
 * information provided by the decorators via {@link CircuitBreaker#onSuccess} and {@link
 * CircuitBreaker#onError} events. Before communicating with the backend, the permission to do so
 * must be obtained via the method {@link CircuitBreaker#tryAcquirePermission()}.
 * <p>
 * The state of the CircuitBreaker changes from CLOSED to OPEN when the failure rate is greater than or
 * equal to a (configurable) threshold. Then, all access to the backend is rejected for a (configurable) time
 * duration. No further calls are permitted.
 * <p>
 * After the time duration has elapsed, the CircuitBreaker state changes from OPEN to HALF_OPEN and
 * allows a number of calls to see if the backend is still unavailable or has become available
 * again. If the failure rate is greater than or equal to the configured threshold, the state changes back to OPEN.
 * If the failure rate is below or equal to the threshold, the state changes back to CLOSED.
 */
public interface CircuitBreaker {

    /**
     * Returns a supplier which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param supplier       the original supplier
     * @param <T>            the type of results supplied by this supplier
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    static <T> CheckedSupplier<T> decorateCheckedSupplier(CircuitBreaker circuitBreaker, CheckedSupplier<T> supplier) {
        return () -> {
            // Acquire permission before the call
            circuitBreaker.acquirePermission();

            // Record the call
            final long start = circuitBreaker.getCurrentTimestamp();
            try {
                // Execute the decorated supplier
                T result = supplier.get();

                // Record a successful call
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // Record the result
                circuitBreaker.onResult(duration, circuitBreaker.getTimestampUnit(), result);

                return result;
            } catch (Exception exception) {
                // Do not handle java.lang.Error
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // Record a failed call
                circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                throw exception;
            }
        };
    }

    /**
     * Returns a supplier which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param supplier       the original supplier
     * @param <T>            the type of the returned CompletionStage's result
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    static <T> Supplier<CompletionStage<T>> decorateCompletionStage(
        CircuitBreaker circuitBreaker,
        Supplier<CompletionStage<T>> supplier
    ) {
        return () -> {
            final CompletableFuture<T> promise = new CompletableFuture<>();

            // Acquire permission before the call
            if (!circuitBreaker.tryAcquirePermission()) {
                // 没有权限的调用
                promise.completeExceptionally(
                    CallNotPermittedException.createCallNotPermittedException(circuitBreaker));
            } else {
                final long start = circuitBreaker.getCurrentTimestamp();
                try {
                    supplier.get().whenComplete((result, throwable) -> {
                        long duration = circuitBreaker.getCurrentTimestamp() - start;
                        if (throwable != null) {
                            if (throwable instanceof Exception) {
                                // 调用抛出异常 让断路器感知
                                circuitBreaker
                                    .onError(duration, circuitBreaker.getTimestampUnit(), throwable);
                            }
                            // 失败的调用
                            promise.completeExceptionally(throwable);
                        } else {
                            // 成功的调用 让断路器感知
                            circuitBreaker.onResult(duration, circuitBreaker.getTimestampUnit(), result);
                            // 返回结果
                            promise.complete(result);
                        }
                    });
                } catch (Exception exception) {
                    long duration = circuitBreaker.getCurrentTimestamp() - start;
                    // 失败的调用 让断路器感知
                    circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                    // 失败的调用
                    promise.completeExceptionally(exception);
                }
            }

            // 返回结果
            return promise;
        };
    }

    /**
     * Returns a runnable which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param runnable       the original runnable
     * @return a runnable which is decorated by a CircuitBreaker.
     */
    static CheckedRunnable decorateCheckedRunnable(CircuitBreaker circuitBreaker, CheckedRunnable runnable) {
        return () -> {
            // 获取权限
            circuitBreaker.acquirePermission();
            final long start = circuitBreaker.getCurrentTimestamp();
            try {
                runnable.run();
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用成功 让断路器感知
                circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
            } catch (Exception exception) {
                // Do not handle java.lang.Error
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用失败 让断路器感知
                circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                throw exception;
            }
        };
    }

    /**
     * Returns a callable which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param callable       the original Callable
     * @param <T>            the result type of callable
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    static <T> Callable<T> decorateCallable(CircuitBreaker circuitBreaker, Callable<T> callable) {
        return () -> {
            // 获取权限
            circuitBreaker.acquirePermission();
            final long start = circuitBreaker.getCurrentTimestamp();
            try {
                T result = callable.call();
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用成功 让断路器感知
                circuitBreaker.onResult(duration, circuitBreaker.getTimestampUnit(), result);
                return result;
            } catch (Exception exception) {
                // Do not handle java.lang.Error
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                throw exception;
            }
        };
    }

    /**
     * Returns a supplier which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param supplier       the original supplier
     * @param <T>            the type of results supplied by this supplier
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    static <T> Supplier<T> decorateSupplier(CircuitBreaker circuitBreaker, Supplier<T> supplier) {
        return () -> {
            // 获取权限
            circuitBreaker.acquirePermission();
            final long start = circuitBreaker.getCurrentTimestamp();
            try {
                T result = supplier.get();
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用成功 让断路器感知
                circuitBreaker.onResult(duration, circuitBreaker.getTimestampUnit(), result);
                return result;
            } catch (Exception exception) {
                // Do not handle java.lang.Error
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用失败 让断路器感知
                circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                throw exception;
            }
        };
    }

    /**
     * Returns a consumer which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param consumer       the original consumer
     * @param <T>            the type of the input to the consumer
     * @return a consumer which is decorated by a CircuitBreaker.
     */
    static <T> Consumer<T> decorateConsumer(CircuitBreaker circuitBreaker, Consumer<T> consumer) {
        return (t) -> {
            // 获取权限
            circuitBreaker.acquirePermission();
            final long start = circuitBreaker.getCurrentTimestamp();
            try {
                consumer.accept(t);
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用成功 让断路器感知
                circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
            } catch (Exception exception) {
                // Do not handle java.lang.Error
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用失败 让断路器感知
                circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                throw exception;
            }
        };
    }

    /**
     * Returns a consumer which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param consumer       the original consumer
     * @param <T>            the type of the input to the consumer
     * @return a consumer which is decorated by a CircuitBreaker.
     */
    static <T> CheckedConsumer<T> decorateCheckedConsumer(CircuitBreaker circuitBreaker, CheckedConsumer<T> consumer) {
        return (t) -> {
            // 获取权限
            circuitBreaker.acquirePermission();
            final long start = circuitBreaker.getCurrentTimestamp();
            try {
                consumer.accept(t);
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用成功 让断路器感知
                circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
            } catch (Exception exception) {
                // Do not handle java.lang.Error
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用失败 让断路器感知
                circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                throw exception;
            }
        };
    }

    /**
     * Returns a runnable which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param runnable       the original runnable
     * @return a runnable which is decorated by a CircuitBreaker.
     */
    static Runnable decorateRunnable(CircuitBreaker circuitBreaker, Runnable runnable) {
        return () -> {
            // 获取权限
            circuitBreaker.acquirePermission();
            final long start = circuitBreaker.getCurrentTimestamp();
            try {
                runnable.run();
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用成功 让断路器感知
                circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
            } catch (Exception exception) {
                // Do not handle java.lang.Error
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用失败 让断路器感知
                circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                throw exception;
            }
        };
    }

    /**
     * Returns a function which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param function       the original function
     * @param <T>            the type of the input to the function
     * @param <R>            the type of the result of the function
     * @return a function which is decorated by a CircuitBreaker.
     */
    static <T, R> Function<T, R> decorateFunction(CircuitBreaker circuitBreaker,
        Function<T, R> function) {
        return (T t) -> {
            // 获取权限
            circuitBreaker.acquirePermission();
            final long start = circuitBreaker.getCurrentTimestamp();
            try {
                R returnValue = function.apply(t);
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用成功 让断路器感知
                circuitBreaker.onResult(duration, circuitBreaker.getTimestampUnit(), returnValue);
                return returnValue;
            } catch (Exception exception) {
                // Do not handle java.lang.Error
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用失败 让断路器感知
                circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                throw exception;
            }
        };
    }

    /**
     * Returns a function which is decorated by a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param function       the original function
     * @param <T>            the type of the input to the function
     * @param <R>            the type of the result of the function
     * @return a function which is decorated by a CircuitBreaker.
     */
    static <T, R> CheckedFunction<T, R> decorateCheckedFunction(CircuitBreaker circuitBreaker, CheckedFunction<T, R> function) {
        return (T t) -> {
            // 获取权限
            circuitBreaker.acquirePermission();
            final long start = circuitBreaker.getCurrentTimestamp();
            try {
                R result = function.apply(t);
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用成功 让断路器感知
                circuitBreaker.onResult(duration, circuitBreaker.getTimestampUnit(), result);
                return result;
            } catch (Exception exception) {
                // Do not handle java.lang.Error
                long duration = circuitBreaker.getCurrentTimestamp() - start;
                // 调用失败 让断路器感知
                circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
                throw exception;
            }
        };
    }

    /**
     * Creates a CircuitBreaker with a default CircuitBreaker configuration.
     *
     * @param name the name of the CircuitBreaker
     * @return a CircuitBreaker with a default CircuitBreaker configuration.
     */
    static CircuitBreaker ofDefaults(String name) {
        return new CircuitBreakerStateMachine(name);
    }

    /**
     * Creates a CircuitBreaker with a custom CircuitBreaker configuration.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig a custom CircuitBreaker configuration
     * @return a CircuitBreaker with a custom CircuitBreaker configuration.
     */
    static CircuitBreaker of(String name, CircuitBreakerConfig circuitBreakerConfig) {
        return new CircuitBreakerStateMachine(name, circuitBreakerConfig);
    }

    /**
     * Creates a CircuitBreaker with a custom CircuitBreaker configuration.
     * <p>
     * The {@code tags} passed will be appended to the tags already configured for the registry.
     * When tags (keys) of the two collide the tags passed with this method will override the tags
     * of the registry.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig a custom CircuitBreaker configuration
     * @param tags                 tags added to the CircuitBreaker
     * @return a CircuitBreaker with a custom CircuitBreaker configuration.
     */
    static CircuitBreaker of(String name, CircuitBreakerConfig circuitBreakerConfig, Map<String, String> tags) {
        return new CircuitBreakerStateMachine(name, circuitBreakerConfig, tags);
    }

    /**
     * Creates a CircuitBreaker with a custom CircuitBreaker configuration.
     *
     * @param name                         the name of the CircuitBreaker
     * @param circuitBreakerConfigSupplier a supplier of a custom CircuitBreaker configuration
     * @return a CircuitBreaker with a custom CircuitBreaker configuration.
     */
    static CircuitBreaker of(String name,
        Supplier<CircuitBreakerConfig> circuitBreakerConfigSupplier) {
        return new CircuitBreakerStateMachine(name, circuitBreakerConfigSupplier);
    }

    /**
     * Creates a CircuitBreaker with a custom CircuitBreaker configuration.
     * <p>
     * The {@code tags} passed will be appended to the tags already configured for the registry.
     * When tags (keys) of the two collide the tags passed with this method will override the tags
     * of the registry.
     *
     * @param name                         the name of the CircuitBreaker
     * @param circuitBreakerConfigSupplier a supplier of a custom CircuitBreaker configuration
     * @param tags                         tags added to the CircuitBreaker
     * @return a CircuitBreaker with a custom CircuitBreaker configuration.
     */
    static CircuitBreaker of(String name,
        Supplier<CircuitBreakerConfig> circuitBreakerConfigSupplier, Map<String, String> tags) {
        return new CircuitBreakerStateMachine(name, circuitBreakerConfigSupplier, tags);
    }

    /**
     * Returns a supplier of type Future which is decorated by a CircuitBreaker. The elapsed time
     * includes {@link Future#get()} evaluation time even if the underlying call took less time to
     * return. Any delays in evaluating Future by caller will add towards total time.
     *
     * @param circuitBreaker the CircuitBreaker
     * @param supplier       the original supplier
     * @param <T>            the type of the returned Future's result
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    static <T> Supplier<Future<T>> decorateFuture(CircuitBreaker circuitBreaker,
        Supplier<Future<T>> supplier) {
        return () -> {
            // 获取权限
            if (!circuitBreaker.tryAcquirePermission()) {
                CompletableFuture<T> promise = new CompletableFuture<>();
                promise.completeExceptionally(
                    CallNotPermittedException.createCallNotPermittedException(circuitBreaker));
                return promise;
            } else {
                final long start = circuitBreaker.getCurrentTimestamp();
                try {
                    return new CircuitBreakerFuture<>(circuitBreaker, supplier.get(), start);
                } catch (Exception e) {
                    long duration = circuitBreaker.getCurrentTimestamp() - start;
                    // 调用失败 让断路器感知
                    circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), e);
                    throw e;
                }
            }
        };
    }

    /**
     * 仅当调用时有可用的权限时，才获取执行调用的权限。
     *如果不允许呼叫，则增加不允许呼叫的数量。
     </p>
     *当状态为OPEN或FORCED_OPEN时，返回false。当状态为CLOSED或
     *禁用。当状态为HALF_OPEN并且允许进一步的测试调用时，返回true。
     *当状态为HALF_OPEN并且已达到测试调用数时，返回false。如果
     *状态为HALF_OPEN，则允许的测试调用数会减少。重要提示：确保
     *以在调用完成后调用onSuccess或onError。如果通话在此之前被取消
     *则必须再次释放该权限。
     *
     * @return {@code true} if a permission was acquired and {@code false} otherwise
     */
    boolean tryAcquirePermission();

    /**
     * 释放权限。
     * </p>
     * 应仅在获得许可但未使用时使用。否则使用｛@link
     * CircuitBreaker#onSuccess（long，TimeUnit）｝或
     * ｛@link CircuitBreaker#onError（long，TimeUnit，Throwable）｝以发出呼叫完成或失败的信号。
     * </p>
     * 如果状态为HALF_OPEN，则允许的测试调用数将增加一。
     */
    void releasePermission();

    /**
     *尝试获取执行调用的权限。如果不允许呼叫，则不允许的号码
     *允许的呼叫增加。
     </p>
     *当状态为OPEN或FORCED_OPEN时引发CallNotPermittedException。当
     *状态为CLOSED（关闭）或DISABLED（禁用）。当状态为HALF_OPEN并且进一步的测试调用为时返回
     *允许。当状态为HALF_OPEN且
     *已到达测试调用。如果状态为HALF_OPEN，则允许的测试调用数为
     *减少。重要提示：请确保在调用完成后调用onSuccess或onError。如果
     *调用之前调用被取消，您必须再次释放权限。
     *
     * @throws CallNotPermittedException when CircuitBreaker is OPEN or HALF_OPEN and no further
     *                                   test calls are permitted.
     */
    void acquirePermission();

    /**
     * 记录一个失败的呼叫。当调用失败时，必须调用此方法。
     *
     * @param duration 调用经过的持续时间
     * @param durationUnit 持续时间单位
     * @param throwable 必须记录的throwable
     */
    void onError(long duration, TimeUnit durationUnit, Throwable throwable);

    /**
     * 记录一个成功的呼叫。当调用
     * 成功的
     *
     * @param duration 调用经过的持续时间
     * @param durationUnit 持续时间单位
     */
    void onSuccess(long duration, TimeUnit durationUnit);

    /**
     * 当调用返回结果时，必须调用此方法
     * 结果谓词应该决定调用是否成功。
     *
     * @param duration 调用经过的持续时间
     * @param durationUnit 持续时间单位
     * @param result 受保护函数的结果
     */
    void onResult(long duration, TimeUnit durationUnit, Object result);

    /**
     *将断路器恢复到其原始闭合状态，丢失统计信息。
     </p>
     *仅当您希望完全重置断路器时才应使用
     *创建一个新的。
     */
    void reset();

    /**
     * 将状态机转换为关闭状态。这个调用是幂等的，不会有
     * 如果状态机已经处于关闭状态，则任何效果。
     </p>
     *仅当您希望强制进行状态转换时才应使用。状态转换正常
     *内部完成。
     */
    void transitionToClosedState();

    /**
     * Transitions the state machine to OPEN state. This call is idempotent and will not have
     * any effect if the state machine is already in OPEN state.
     * <p>
     * Should only be used, when you want to force a state transition. State transition are normally
     * done internally.
     */
    void transitionToOpenState();

    /**
     * Same as {@link #transitionToOpenState()} but waits in open state for the given amount of time
     * instead of relaying on configurations to determine it.
     *
     * @param waitDuration how long should we wait in open state
     */
    void transitionToOpenStateFor(Duration waitDuration);

    /**
     * Same as {@link #transitionToOpenState()} but waits in open state until the given in time
     * instead of relaying on configurations to determine it.
     *
     * @param waitUntil how long should we wait in open state
     */
    void transitionToOpenStateUntil(Instant waitUntil);

    /**
     * Transitions the state machine to HALF_OPEN state. This call is idempotent and will not have
     * any effect if the state machine is already in HALF_OPEN state.
     * <p>
     * Should only be used, when you want to force a state transition. State transition are normally
     * done internally.
     */
    void transitionToHalfOpenState();

    /**
     * Transitions the state machine to a DISABLED state, stopping state transition, metrics and
     * event publishing. This call is idempotent and will not have any effect if the
     * state machine is already in DISABLED state.
     * <p>
     * Should only be used, when you want to disable the circuit breaker allowing all calls to pass.
     * To recover from this state you must force a new state transition
     */
    void transitionToDisabledState();

    /**
     * Transitions the state machine to METRICS_ONLY state, stopping all state transitions but
     * continues to capture metrics and publish events. This call is idempotent and will not have
     * any effect if the state machine is already in METRICS_ONLY state.
     * <p>
     * Should only be used when you want to collect metrics while keeping the circuit breaker
     * disabled, allowing all calls to pass.
     * To recover from this state you must force a new state transition.
     */
    void transitionToMetricsOnlyState();

    /**
     * Transitions the state machine to a FORCED_OPEN state,  stopping state transition, metrics and
     * event publishing. This call is idempotent and will not have any effect if the state machine
     * is already in FORCED_OPEN state.
     * <p>
     * Should only be used, when you want to disable the circuit breaker allowing no call to pass.
     * To recover from this state you must force a new state transition
     */
    void transitionToForcedOpenState();

    /**
     * Returns the name of this CircuitBreaker.
     *
     * @return the name of this CircuitBreaker
     */
    String getName();

    /**
     * Returns the state of this CircuitBreaker.
     *
     * @return the state of this CircuitBreaker
     */
    State getState();

    /**
     * Returns the CircuitBreakerConfig of this CircuitBreaker.
     *
     * @return the CircuitBreakerConfig of this CircuitBreaker
     */
    CircuitBreakerConfig getCircuitBreakerConfig();

    /**
     * 返回此CircuitBreaker的度量。
     *
     * @return the Metrics of this CircuitBreaker
     */
    Metrics getMetrics();

    /**
     * 返回一个不可修改的映射，其中标记已分配给该CircuitBreaker。
     *
     * @return the tags assigned to this CircuitBreaker in an unmodifiable map
     */
    Map<String, String> getTags();

    /**
     * 返回一个EventPublisher，该EventPublisher可用于注册事件使用者。
     *
     * @return an EventPublisher
     */
    EventPublisher getEventPublisher();

    /**
     * 返回与CircuitBreaker currentTimeFunction相关的当前时间。
     * 默认情况下返回System.nanoTime（）。
     *
     * @return current timestamp
     */
    long getCurrentTimestamp();

    /**
     * 返回当前时间戳的时间单位。
     * 默认值为TimeUnit。纳秒。
     *
     * @return the timeUnit of current timestamp
     */
    TimeUnit getTimestampUnit();

    /**
     * 装饰并执行装饰供应商。
     *
     * @param supplier the original Supplier
     * @param <T>      the type of results supplied by this supplier
     * @return the result of the decorated Supplier.
     */
    default <T> T executeSupplier(Supplier<T> supplier) {
        return decorateSupplier(this, supplier).get();
    }

    /**
     * 返回由CircuitBreaker装饰的供应商。
     *
     * @param supplier the original supplier
     * @param <T>      the type of results supplied by this supplier
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    default <T> Supplier<T> decorateSupplier(Supplier<T> supplier) {
        return decorateSupplier(this, supplier);
    }

    /**
     * 装饰并执行已装饰的Callable。
     *
     * @param callable the original Callable
     * @param <T>      the result type of callable
     * @return the result of the decorated Callable.
     * @throws Exception if unable to compute a result
     */
    default <T> T executeCallable(Callable<T> callable) throws Exception {
        return decorateCallable(this, callable).call();
    }

    /**
     * 返回一个由CircuitBreaker装饰的可调用对象。
     *
     * @param callable the original Callable
     * @param <T>      the result type of callable
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    default <T> Callable<T> decorateCallable(Callable<T> callable) {
        return decorateCallable(this, callable);
    }

    /**
     * 装饰并执行已装饰的Runnable。
     *
     * @param runnable the original Runnable
     */
    default void executeRunnable(Runnable runnable) {
        decorateRunnable(this, runnable).run();
    }

    /**
     * 返回一个由CircuitBreaker装饰的可运行文件。
     *
     * @param runnable the original runnable
     * @return a runnable which is decorated by a CircuitBreaker.
     */
    default Runnable decorateRunnable(Runnable runnable) {
        return decorateRunnable(this, runnable);
    }

    /**
     * Decorates and executes the decorated CompletionStage.
     *
     * @param supplier the original CompletionStage
     * @param <T>      the type of results supplied by this supplier
     * @return the decorated CompletionStage.
     */
    default <T> CompletionStage<T> executeCompletionStage(Supplier<CompletionStage<T>> supplier) {
        return decorateCompletionStage(this, supplier).get();
    }

    /**
     * Returns a supplier which is decorated by a CircuitBreaker.
     *
     * @param supplier the original supplier
     * @param <T>      the type of the returned CompletionStage's result
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    default <T> Supplier<CompletionStage<T>> decorateCompletionStage(
        Supplier<CompletionStage<T>> supplier) {
        return decorateCompletionStage(this, supplier);
    }

    /**
     * Decorates and executes the decorated Supplier.
     *
     * @param checkedSupplier the original Supplier
     * @param <T>             the type of results supplied by this supplier
     * @return the result of the decorated Supplier.
     * @throws Throwable if something goes wrong applying this function to the given arguments
     */
    default <T> T executeCheckedSupplier(CheckedSupplier<T> checkedSupplier) throws Throwable {
        return decorateCheckedSupplier(this, checkedSupplier).get();
    }

    /**
     * Returns a supplier which is decorated by a CircuitBreaker.
     *
     * @param checkedSupplier the original supplier
     * @param <T>             the type of results supplied by this supplier
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    default <T> CheckedSupplier<T> decorateCheckedSupplier(CheckedSupplier<T> checkedSupplier) {
        return decorateCheckedSupplier(this, checkedSupplier);
    }

    /**
     * Returns a runnable which is decorated by a CircuitBreaker.
     *
     * @param runnable the original runnable
     * @return a runnable which is decorated by a CircuitBreaker.
     */
    default CheckedRunnable decorateCheckedRunnable(CheckedRunnable runnable) {
        return decorateCheckedRunnable(this, runnable);
    }

    /**
     * Decorates and executes the decorated Runnable.
     *
     * @param runnable the original runnable
     */
    default void executeCheckedRunnable(CheckedRunnable runnable) throws Throwable {
        decorateCheckedRunnable(this, runnable).run();
    }

    /**
     * Returns a consumer which is decorated by a CircuitBreaker.
     *
     * @param consumer the original consumer
     * @param <T>      the type of the input to the consumer
     * @return a consumer which is decorated by a CircuitBreaker.
     */
    default <T> Consumer<T> decorateConsumer(Consumer<T> consumer) {
        return decorateConsumer(this, consumer);
    }

    /**
     * Returns a consumer which is decorated by a CircuitBreaker.
     *
     * @param consumer the original consumer
     * @param <T>      the type of the input to the consumer
     * @return a consumer which is decorated by a CircuitBreaker.
     */
    default <T> CheckedConsumer<T> decorateCheckedConsumer(CheckedConsumer<T> consumer) {
        return decorateCheckedConsumer(this, consumer);
    }

    /**
     * Returns a supplier of type Future which is decorated by a CircuitBreaker. The elapsed time
     * includes {@link Future#get()} evaluation time even if the underlying call took less time to
     * return. Any delays in evaluating Future by caller will add towards total time.
     *
     * @param supplier the original supplier
     * @param <T>      the type of the returned CompletionStage's result
     * @return a supplier which is decorated by a CircuitBreaker.
     */
    default <T> Supplier<Future<T>> decorateFuture(Supplier<Future<T>> supplier) {
        return decorateFuture(this, supplier);
    }

    /**
     * 断路器状态机的状态。
     */
    enum State {
        /**
         *DISABLED 断路器未运行（无状态转换，无事件），并且允许所有
         *请求通过。
         */
        DISABLED(3, false),
        /**
         * METRICS_ONLY 断路器正在收集度量、发布事件并允许所有请求
         * 通过但未过渡到其他状态。
         */
        METRICS_ONLY(5, true),
        /**
         * CLOSED 断路器运行正常，允许请求通过。
         */
        CLOSED(0, true),
        /**
         * OPEN断路器跳闸，不允许请求通过。
         */
        OPEN(1, true),
        /**
         * FORCED_OPEN断路器未运行（无状态转换，无事件）且不允许
         * 任何请求通过。
         */
        FORCED_OPEN(4, false),
        /**
         * HALF_OPEN断路器已完成其等待间隔，将允许请求
         */
        HALF_OPEN(2, true);

        public final boolean allowPublish;
        private final int order;

        /**
         * Order is a FIXED integer, it should be preserved regardless of the ordinal number of the
         * enumeration. While a State.ordinal() does mostly the same, it is prone to changing the
         * order based on how the programmer  sets the enum. If more states are added the "order"
         * should be preserved. For example, if there is a state inserted between CLOSED and
         * HALF_OPEN (say FIXED_OPEN) then the order of HALF_OPEN remains at 2 and the new state
         * takes 3 regardless of its order in the enum.
         *
         * @param order
         * @param allowPublish
         */
        State(int order, boolean allowPublish) {
            this.order = order;
            this.allowPublish = allowPublish;
        }

        public int getOrder() {
            return order;
        }
    }

    /**
     * State transitions of the CircuitBreaker state machine.
     */
    enum StateTransition {
        CLOSED_TO_CLOSED(State.CLOSED, State.CLOSED),
        CLOSED_TO_OPEN(State.CLOSED, State.OPEN),
        CLOSED_TO_DISABLED(State.CLOSED, State.DISABLED),
        CLOSED_TO_METRICS_ONLY(State.CLOSED, State.METRICS_ONLY),
        CLOSED_TO_FORCED_OPEN(State.CLOSED, State.FORCED_OPEN),
        HALF_OPEN_TO_HALF_OPEN(State.HALF_OPEN, State.HALF_OPEN),
        HALF_OPEN_TO_CLOSED(State.HALF_OPEN, State.CLOSED),
        HALF_OPEN_TO_OPEN(State.HALF_OPEN, State.OPEN),
        HALF_OPEN_TO_DISABLED(State.HALF_OPEN, State.DISABLED),
        HALF_OPEN_TO_METRICS_ONLY(State.HALF_OPEN, State.METRICS_ONLY),
        HALF_OPEN_TO_FORCED_OPEN(State.HALF_OPEN, State.FORCED_OPEN),
        OPEN_TO_OPEN(State.OPEN, State.OPEN),
        OPEN_TO_CLOSED(State.OPEN, State.CLOSED),
        OPEN_TO_HALF_OPEN(State.OPEN, State.HALF_OPEN),
        OPEN_TO_DISABLED(State.OPEN, State.DISABLED),
        OPEN_TO_METRICS_ONLY(State.OPEN, State.METRICS_ONLY),
        OPEN_TO_FORCED_OPEN(State.OPEN, State.FORCED_OPEN),
        FORCED_OPEN_TO_FORCED_OPEN(State.FORCED_OPEN, State.FORCED_OPEN),
        FORCED_OPEN_TO_CLOSED(State.FORCED_OPEN, State.CLOSED),
        FORCED_OPEN_TO_OPEN(State.FORCED_OPEN, State.OPEN),
        FORCED_OPEN_TO_DISABLED(State.FORCED_OPEN, State.DISABLED),
        FORCED_OPEN_TO_METRICS_ONLY(State.FORCED_OPEN, State.METRICS_ONLY),
        FORCED_OPEN_TO_HALF_OPEN(State.FORCED_OPEN, State.HALF_OPEN),
        DISABLED_TO_DISABLED(State.DISABLED, State.DISABLED),
        DISABLED_TO_CLOSED(State.DISABLED, State.CLOSED),
        DISABLED_TO_OPEN(State.DISABLED, State.OPEN),
        DISABLED_TO_FORCED_OPEN(State.DISABLED, State.FORCED_OPEN),
        DISABLED_TO_HALF_OPEN(State.DISABLED, State.HALF_OPEN),
        DISABLED_TO_METRICS_ONLY(State.DISABLED, State.METRICS_ONLY),
        METRICS_ONLY_TO_METRICS_ONLY(State.METRICS_ONLY, State.METRICS_ONLY),
        METRICS_ONLY_TO_CLOSED(State.METRICS_ONLY, State.CLOSED),
        METRICS_ONLY_TO_FORCED_OPEN(State.METRICS_ONLY, State.FORCED_OPEN),
        METRICS_ONLY_TO_DISABLED(State.METRICS_ONLY, State.DISABLED);

        private static final Map<Map.Entry<State, State>, StateTransition> STATE_TRANSITION_MAP = Arrays
            .stream(StateTransition.values())
            .collect(Collectors.toMap(v -> Map.entry(v.fromState, v.toState), Function.identity()));
        private final State fromState;
        private final State toState;

        StateTransition(State fromState, State toState) {
            this.fromState = fromState;
            this.toState = toState;
        }

        public static StateTransition transitionBetween(String name, State fromState,
            State toState) {
            final StateTransition stateTransition = STATE_TRANSITION_MAP
                .get(Map.entry(fromState, toState));
            if (stateTransition == null) {
                throw new IllegalStateTransitionException(name, fromState, toState);
            }
            return stateTransition;
        }

        public State getFromState() {
            return fromState;
        }

        public State getToState() {
            return toState;
        }

        public static boolean isInternalTransition(final StateTransition transition) {
            return transition.getToState() == transition.getFromState();
        }

        @Override
        public String toString() {
            return String.format("State transition from %s to %s", fromState, toState);
        }
    }

    /**
     * An EventPublisher can be used to register event consumers.
     */
    interface EventPublisher extends
        io.github.resilience4j.core.EventPublisher<CircuitBreakerEvent> {

        EventPublisher onSuccess(EventConsumer<CircuitBreakerOnSuccessEvent> eventConsumer);

        EventPublisher onError(EventConsumer<CircuitBreakerOnErrorEvent> eventConsumer);

        EventPublisher onStateTransition(
            EventConsumer<CircuitBreakerOnStateTransitionEvent> eventConsumer);

        EventPublisher onReset(EventConsumer<CircuitBreakerOnResetEvent> eventConsumer);

        EventPublisher onIgnoredError(
            EventConsumer<CircuitBreakerOnIgnoredErrorEvent> eventConsumer);

        EventPublisher onCallNotPermitted(
            EventConsumer<CircuitBreakerOnCallNotPermittedEvent> eventConsumer);

        EventPublisher onFailureRateExceeded(
            EventConsumer<CircuitBreakerOnFailureRateExceededEvent> eventConsumer);

        EventPublisher onSlowCallRateExceeded(
            EventConsumer<CircuitBreakerOnSlowCallRateExceededEvent> eventConsumer);
    }

    interface Metrics {

        /**
         *以百分比形式返回当前故障率。如果测量到的呼叫数低于
         *测量到的最小调用数，返回-1。
         *
         * @return the failure rate in percentage
         */
        float getFailureRate();

        /**
         *返回低于某个阈值的当前调用百分比。如果
         *测量到的调用数低于测量到的最小调用数，则返回-1.
         *
         * @return the failure rate in percentage
         */
        float getSlowCallRate();

        /**
         * 返回当前低于某个阈值的调用总数。
         *
         * @return the current total number of calls which were slower than a certain threshold
         */
        int getNumberOfSlowCalls();

        /**
         * 返回当前慢于特定阈值的成功调用数。
         *
         * @return 当前速度慢于某个临界值的成功呼叫次数
         */
        int getNumberOfSlowSuccessfulCalls();

        /**
         * 返回当前慢于特定阈值的失败调用数。
         *
         * @return the current number of failed calls which were slower than a certain threshold
         */
        int getNumberOfSlowFailedCalls();

        /**
         * 返回环形缓冲区中当前缓冲呼叫的总数。
         *
         * @return he current total number of buffered calls in the ring buffer
         */
        int getNumberOfBufferedCalls();

        /**
         * 返回环形缓冲区中当前失败的缓冲调用次数。
         *
         * @return the current number of failed buffered calls in the ring buffer
         */
        int getNumberOfFailedCalls();

        /**
         * 当状态为OPEN时，返回当前不允许的调用数。
         * <p>
         * The number of denied calls is always 0, when the CircuitBreaker state is CLOSED or
         * HALF_OPEN. The number of denied calls is only increased when the CircuitBreaker state is
         * OPEN.
         *
         * @return the current number of not permitted calls
         */
        long getNumberOfNotPermittedCalls();

        /**
         * 返回环形缓冲区中当前成功缓冲的调用数。
         *
         * @return the current number of successful buffered calls in the ring buffer
         */
        int getNumberOfSuccessfulCalls();
    }

    /**
     * This class decorates future to add CircuitBreaking functionality around invocation.
     *
     * @param <T> of return type
     */
    final class CircuitBreakerFuture<T> implements Future<T> {

        private final Future<T> future;
        private final OnceConsumer<CircuitBreaker> onceToCircuitbreaker;
        private final long start;

        CircuitBreakerFuture(CircuitBreaker circuitBreaker, Future<T> future) {
            this(circuitBreaker, future, circuitBreaker.getCurrentTimestamp());
        }

        CircuitBreakerFuture(CircuitBreaker circuitBreaker, Future<T> future, long start) {
            Objects.requireNonNull(future, "Non null Future is required to decorate");
            this.onceToCircuitbreaker = OnceConsumer.of(circuitBreaker);
            this.future = future;
            this.start = start;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return future.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }

        @Override
        public boolean isDone() {
            return future.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            try {
                T v = future.get();
                onceToCircuitbreaker
                    .applyOnce(cb ->
                        cb.onResult(cb.getCurrentTimestamp() - start, cb.getTimestampUnit(), v)
                    );
                return v;
            } catch (CancellationException | InterruptedException e) {
                onceToCircuitbreaker.applyOnce(cb -> cb.releasePermission());
                throw e;
            } catch (Exception e) {
                onceToCircuitbreaker.applyOnce(
                    cb -> cb.onError(cb.getCurrentTimestamp() - start, cb.getTimestampUnit(), e));
                throw e;
            }
        }

        @Override
        public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
            try {
                T v = future.get(timeout, unit);
                onceToCircuitbreaker
                    .applyOnce(cb ->
                        cb.onResult(cb.getCurrentTimestamp()  - start, cb.getTimestampUnit(), v)
                    );
                return v;
            } catch (CancellationException | InterruptedException e) {
                onceToCircuitbreaker.applyOnce(CircuitBreaker::releasePermission);
                throw e;
            } catch (Exception e) {
                onceToCircuitbreaker.applyOnce(
                    cb -> cb.onError(cb.getCurrentTimestamp() - start, cb.getTimestampUnit(), e));
                throw e;
            }
        }
    }
}
