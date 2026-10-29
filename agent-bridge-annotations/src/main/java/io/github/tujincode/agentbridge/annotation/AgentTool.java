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
package io.github.tujincode.agentbridge.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an existing method as an Agent-callable tool.
 *
 * <p>The JSON Schema the Agent sees is derived from the method signature and its
 * parameter annotations, so the contract can never drift away from the code.
 *
 * <pre>{@code
 * @AgentTool(name = "workorder.escalate",
 *            description = "Raise the priority of an open work order.",
 *            risk = RiskLevel.WRITE,
 *            scopes = "workorder:write")
 * public WorkOrder escalate(@AgentParam(description = "Work order id") String id,
 *                           @AgentParam(description = "Target level, 1-5") int level) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {

    /**
     * Tool name exposed to the Agent. Defaults to the bare method name.
     * Use dot notation to group tools by domain, e.g. {@code workorder.list}.
     */
    String name() default "";

    /**
     * What the tool does, written for a model to read. Required: a tool without a
     * description is a tool the model will misuse.
     */
    String description();

    /** Blast radius of the call. Defaults to read-only. */
    RiskLevel risk() default RiskLevel.READ;

    /**
     * Whether repeating the same call has the same effect as calling it once.
     * Non-idempotent tools are retried at most once, never in parallel.
     */
    boolean idempotent() default true;

    /** Permission scopes the caller must hold, checked by the host application. */
    String[] scopes() default {};
}
