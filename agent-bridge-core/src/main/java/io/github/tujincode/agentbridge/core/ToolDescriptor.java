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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.tujincode.agentbridge.annotation.RiskLevel;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Everything the gateway knows about one tool: the contract shown to the model and
 * the reflection handle used to invoke it.
 */
public final class ToolDescriptor {

    private final String name;
    private final String description;
    private final RiskLevel risk;
    private final boolean idempotent;
    private final List<String> scopes;
    private final ObjectNode inputSchema;
    private final Method method;
    private final Object target;

    public ToolDescriptor(String name,
                          String description,
                          RiskLevel risk,
                          boolean idempotent,
                          List<String> scopes,
                          ObjectNode inputSchema,
                          Method method,
                          Object target) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null || description.isBlank()
                ? "No description provided. Prefer not to call this tool unless asked explicitly."
                : description;
        this.risk = risk == null ? RiskLevel.READ : risk;
        this.idempotent = idempotent;
        this.scopes = scopes == null ? List.of() : List.copyOf(scopes);
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        this.method = Objects.requireNonNull(method, "method");
        this.target = Objects.requireNonNull(target, "target");
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public RiskLevel risk() {
        return risk;
    }

    public boolean idempotent() {
        return idempotent;
    }

    public List<String> scopes() {
        return scopes;
    }

    public ObjectNode inputSchema() {
        return inputSchema;
    }

    public Method method() {
        return method;
    }

    public Object target() {
        return target;
    }

    @Override
    public String toString() {
        return "ToolDescriptor[" + name + " " + risk + " " + method.toGenericString() + "]";
    }
}
