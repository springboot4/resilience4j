/*
 *
 *  Copyright 2016 Robert Winkler
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

/**
 * ｛@link CallNotPermittedException｝ 表示CircuitBreaker为HALF_OPEN或OPEN，并且
 * 不允许执行调用。
 */
public class CallNotPermittedException extends RuntimeException {

    private final transient String causingCircuitBreakerName;

    private CallNotPermittedException(CircuitBreaker circuitBreaker, String message, boolean writableStackTrace) {
        super(message, null, false, writableStackTrace);
        this.causingCircuitBreakerName = circuitBreaker.getName();
    }

    /**
     * Static method to construct a {@link CallNotPermittedException} with a CircuitBreaker.
     *
     * @param circuitBreaker the CircuitBreaker.
     */
    public static CallNotPermittedException createCallNotPermittedException(
        CircuitBreaker circuitBreaker) {
        boolean writableStackTraceEnabled = circuitBreaker.getCircuitBreakerConfig()
            .isWritableStackTraceEnabled();

        String message = String
            .format("CircuitBreaker '%s' is %s and does not permit further calls",
                circuitBreaker.getName(), circuitBreaker.getState());

        return new CallNotPermittedException(circuitBreaker, message, writableStackTraceEnabled);
    }

    /**
     * Returns the name of {@link CircuitBreaker} that caused this exception.
     *
     * @return the name of  {@link CircuitBreaker} that caused this exception.
     */
    public String getCausingCircuitBreakerName() {
        return this.causingCircuitBreakerName;
    }
}
