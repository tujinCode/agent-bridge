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

import java.util.Map;
import java.util.Objects;

/**
 * One tool call in flight.
 *
 * <p>Exists because a read-only view of the invocation is not enough. The transport can
 * describe what the client <em>claimed</em>, but only the identity layer can say who the
 * caller actually is, and the business method must see the second one. Handing interceptors
 * an immutable context would leave the claim in place — the name in the audit record and
 * the name in the business method would both be the unverified one, which is worse than
 * having no identity at all, because it looks correct.
 *
 * <p>So the exchange is a small mutable carrier scoped to exactly one call: the incoming
 * context is immutable, and an interceptor may replace the caller on the way through.
 */
public final class ToolCallExchange {

    /** How strongly the caller's identity is supported. */
    public enum Verification {
        /** Nobody claimed to be anyone. */
        NONE,
        /** The client said who it was; nothing checked it. */
        ASSERTED,
        /** A credential was verified and its subject became the caller. */
        VERIFIED
    }

    private final ToolDescriptor descriptor;
    private final Map<String, Object> arguments;
    private final ToolInvocationContext incoming;

    private String callerId;
    private Verification verification = Verification.NONE;

    public ToolCallExchange(ToolDescriptor descriptor,
                            Map<String, Object> arguments,
                            ToolInvocationContext context) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.arguments = arguments == null ? Map.of() : arguments;
        this.incoming = context == null ? ToolInvocationContext.anonymous() : context;
    }

    public ToolDescriptor descriptor() {
        return descriptor;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    /** The context exactly as the transport described it. Prefer {@link #effectiveContext()}. */
    public ToolInvocationContext incoming() {
        return incoming;
    }

    /** Replaces the caller with an identity that came from a verified credential. */
    public void verifiedAs(String callerId) {
        this.callerId = callerId;
        this.verification = Verification.VERIFIED;
    }

    /** Replaces the caller with an identity that was merely claimed. */
    public void assertedAs(String callerId) {
        this.callerId = callerId;
        this.verification = Verification.ASSERTED;
    }

    /**
     * What the business method and the audit record should see: the verified caller when
     * one was established, otherwise whatever the transport reported.
     */
    public ToolInvocationContext effectiveContext() {
        ToolInvocationContext base = callerId == null || callerId.isBlank()
                ? incoming
                : incoming.withCallerId(callerId);
        return base.withAttribute(ToolInvocationContext.ATTR_IDENTITY_VERIFICATION,
                verification.name().toLowerCase(java.util.Locale.ROOT));
    }
}
