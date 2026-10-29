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
package io.github.tujincode.agentbridge.spring.support;

import io.github.tujincode.agentbridge.annotation.AgentParam;
import io.github.tujincode.agentbridge.annotation.AgentTool;
import io.github.tujincode.agentbridge.annotation.RiskLevel;
import io.github.tujincode.agentbridge.core.ToolInvocationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Stands in for "the service you already have": a plain Spring bean with the security
 * annotations a platform team would already have written for human callers.
 *
 * <p>The agent-facing annotations do not replace the security ones — that is the whole
 * claim under test. If both are honoured, exposing a method to an agent costs one
 * annotation and changes nothing about who is allowed to do what.
 */
@Service
public class DemoToolService {

    @AgentTool(name = "demo.read",
            description = "Read a counter. Safe.",
            risk = RiskLevel.READ,
            scopes = "demo:read")
    public String read() {
        return "read-ok";
    }

    @PreAuthorize("hasAuthority('demo:write')")
    @AgentTool(name = "demo.escalate",
            description = "Change something. Requires demo:write.",
            risk = RiskLevel.WRITE,
            scopes = "demo:write")
    public String escalate(
            @AgentParam(description = "Target id", example = "X-1") String id,
            ToolInvocationContext context) {
        return "escalated " + id + " by " + context.callerId();
    }
}
