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

class AbstractAggregation {

    long totalDurationInMillis = 0;
    int numberOfSlowCalls = 0;
    int numberOfSlowFailedCalls = 0;
    int numberOfFailedCalls = 0;
    int numberOfCalls = 0;

    void record(long duration, TimeUnit durationUnit, Metrics.Outcome outcome) {
        // 调用次数递增
        this.numberOfCalls++;
        // 总调用时长
        this.totalDurationInMillis += durationUnit.toMillis(duration);
        switch (outcome) {
            case SLOW_SUCCESS:
                // 慢调用次数加一
                numberOfSlowCalls++;
                break;

            case SLOW_ERROR:
                // 慢调用次数加一
                numberOfSlowCalls++;
                // 调用失败次数加一
                numberOfFailedCalls++;
                // 慢调用失败次数加一
                numberOfSlowFailedCalls++;
                break;

            case ERROR:
                // 调用失败次数加一
                numberOfFailedCalls++;
                break;

            default:
                break;
        }
    }
}
