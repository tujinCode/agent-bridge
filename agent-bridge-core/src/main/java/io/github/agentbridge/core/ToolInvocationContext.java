package io.github.agentbridge.core;

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
}
