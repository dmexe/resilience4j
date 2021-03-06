/*
 * Copyright 2017 Dan Maas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.ratpack;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerOpenException;
import io.github.resilience4j.core.StopWatch;
import ratpack.exec.Downstream;
import ratpack.exec.Upstream;
import ratpack.func.Function;

public class CircuitBreakerTransformer<T> implements Function<Upstream<? extends T>, Upstream<T>> {

    private final CircuitBreaker circuitBreaker;
    private Function<Throwable, ? extends T> recoverer;

    private CircuitBreakerTransformer(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Create a new transformer that can be applied to the {@link ratpack.exec.Promise#transform(Function)} method.
     * The Promised value will pass through the circuitbreaker, potentially causing it to open if the thresholds
     * for the circuit breaker are exceeded.
     *
     * @param circuitBreaker the circuit breaker to use
     * @param <T>            the type of object
     * @return the transformer
     */
    public static <T> CircuitBreakerTransformer<T> of(CircuitBreaker circuitBreaker) {
        return new CircuitBreakerTransformer<>(circuitBreaker);
    }

    /**
     * Set a recovery function that will execute when the circuit breaker is open.
     *
     * @param recoverer the recovery function
     * @return the transformer
     */
    public CircuitBreakerTransformer<T> recover(Function<Throwable, ? extends T> recoverer) {
        this.recoverer = recoverer;
        return this;
    }

    @Override
    public Upstream<T> apply(Upstream<? extends T> upstream) throws Exception {
        return down -> {
            StopWatch stopWatch;
            if (circuitBreaker.isCallPermitted()) {
                stopWatch = StopWatch.start(circuitBreaker.getName());
                upstream.connect(new Downstream<T>() {

                    @Override
                    public void success(T value) {
                        circuitBreaker.onSuccess(stopWatch.stop().getProcessingDuration());
                        down.success(value);
                    }

                    @Override
                    public void error(Throwable throwable) {
                        circuitBreaker.onError(stopWatch.stop().getProcessingDuration(), throwable);
                        try {
                            if (recoverer != null) {
                                down.success(recoverer.apply(throwable));
                            } else {
                                down.error(throwable);
                            }
                        } catch (Throwable t) {
                            down.error(t);
                        }
                    }

                    @Override
                    public void complete() {
                        down.complete();
                    }
                });
            } else {
                Throwable t = new CircuitBreakerOpenException("CircuitBreaker ${circuitBreaker.name} is open");
                if (recoverer != null) {
                    try {
                        down.success(recoverer.apply(t));
                    } catch (Throwable t2) {
                        down.error(t2);
                    }
                } else {
                    down.error(t);
                }
            }
        };
    }

}
