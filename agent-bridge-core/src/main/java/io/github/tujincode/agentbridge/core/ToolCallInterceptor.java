/*
 * Copyright 2026 Zhang Cong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.tujincode.agentbridge.core;

/**
 * Wraps a tool invocation after the risk gate has passed and before the business
 * method runs.
 *
 * <p>This is the seam the framework-specific layer plugs into. The core knows nothing
 * about Spring Security; it only knows that something may want to prepare state around
 * a call, tear it down afterwards, and say who the caller turned out to be. That keeps
 * {@code agent-bridge-core} free of framework imports while still allowing an
 * implementation to establish the caller's identity for the duration of exactly one
 * invocation.
 *
 * <p><strong>Identity belongs to the call, not to the thread.</strong> An interceptor
 * that establishes state in {@link #invoke} and clears it in a {@code finally} block is
 * correct even when the call lands on a pooled worker, because the state is created on
 * the thread that actually runs the method — not inherited from whoever submitted it.
 * Thread-local inheritance is the wrong tool here: it hands the caller to threads that
 * were never meant to see it.
 *
 * <p>Interceptors run in registration order, outermost first.
 */
@FunctionalInterface
public interface ToolCallInterceptor {

    /**
     * @param exchange   the call in flight; call {@link ToolCallExchange#verifiedAs} when a
     *                   credential has identified the caller, so the business method and the
     *                   audit record see the verified name rather than the claimed one
     * @param invocation the rest of the chain; call {@link ToolInvocation#proceed()} exactly once
     * @return whatever the business method returned
     */
    Object invoke(ToolCallExchange exchange, ToolInvocation invocation);

    /** The remainder of the chain, so an interceptor can decide whether to continue. */
    @FunctionalInterface
    interface ToolInvocation {
        Object proceed();
    }

    /** Pass-through interceptor, used when no framework layer is present. */
    static ToolCallInterceptor noop() {
        return (exchange, invocation) -> invocation.proceed();
    }
}
