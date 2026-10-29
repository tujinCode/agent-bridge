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
