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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries the identity of whoever triggered the tool call down to the business method.
 *
 * <p>This is the seam that makes governed agent access possible: a method declared as
 *
 * <pre>{@code
 * @AgentTool(description = "...")
 * public Order cancel(String id, ToolInvocationContext context) { ... }
 * }</pre>
 *
 * receives the caller identity without it ever appearing in the JSON Schema, so the
 * model can neither see nor forge it.
 */
public final class ToolInvocationContext {

    /**
     * Attribute holding a bearer token presented by the caller.
     *
     * <p>The transport puts it here; the identity layer reads it. It is deliberately an
     * attribute rather than a field: whether a token is present at all depends on the
     * transport, and the business method must never see it.
     */
    public static final String ATTR_AUTHORIZATION = "authorization";

    /**
     * Attribute holding authorities the caller merely <em>claims</em> to hold.
     *
     * <p>Only consulted in the explicitly insecure {@code ASSERTED} mode. If a
     * deployment trusts this outside a lab, it has no access control at all.
     */
    public static final String ATTR_ASSERTED_AUTHORITIES = "assertedAuthorities";

    /**
     * Attribute recording how the caller's identity was established: {@code none},
     * {@code asserted} or {@code verified}.
     *
     * <p>Audit consumers must read it. A record naming a caller without saying whether that
     * name was verified is not an audit trail, it is a rumour with a timestamp.
     */
    public static final String ATTR_IDENTITY_VERIFICATION = "identityVerification";

    private final String callerId;
    private final String requestId;
    private final Map<String, String> attributes;

    public ToolInvocationContext(String callerId, String requestId, Map<String, String> attributes) {
        this.callerId = callerId == null || callerId.isBlank() ? "anonymous" : callerId;
        this.requestId = requestId;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static ToolInvocationContext anonymous() {
        return new ToolInvocationContext("anonymous", null, null);
    }

    public String callerId() {
        return callerId;
    }

    public String requestId() {
        return requestId;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    /** Bearer token presented by the caller, or {@code null} when the transport carried none. */
    public String authorization() {
        return attributes.get(ATTR_AUTHORIZATION);
    }

    /**
     * Returns a copy carrying an extra attribute. Used by the transport to attach what it
     * knows about the request without widening this class for every transport.
     */
    public ToolInvocationContext withAttribute(String key, String value) {
        if (key == null || value == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new ToolInvocationContext(callerId, requestId, merged);
    }

    /** Returns a copy naming a different caller, used once an identity has been established. */
    public ToolInvocationContext withCallerId(String newCallerId) {
        return new ToolInvocationContext(newCallerId, requestId, attributes);
    }

    /** True when a credential was verified rather than merely claimed. */
    public boolean callerVerified() {
        return "verified".equals(attributes.get(ATTR_IDENTITY_VERIFICATION));
    }
}
