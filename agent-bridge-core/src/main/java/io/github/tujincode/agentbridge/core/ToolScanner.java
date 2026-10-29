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

import io.github.tujincode.agentbridge.annotation.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds {@code @AgentTool} methods on a bean and turns them into descriptors.
 *
 * <p>Framework neutral on purpose: give it a plain object and it works. The Spring
 * starter is one caller, not a requirement.
 */
public final class ToolScanner {

    private static final Logger log = LoggerFactory.getLogger(ToolScanner.class);

    private final JsonSchemaBuilder schemaBuilder;

    public ToolScanner() {
        this(new JsonSchemaBuilder());
    }

    public ToolScanner(JsonSchemaBuilder schemaBuilder) {
        this.schemaBuilder = schemaBuilder;
    }

    public List<ToolDescriptor> scan(Object bean) {
        Class<?> type = userClass(bean.getClass());
        List<ToolDescriptor> descriptors = new ArrayList<>();
        Map<String, Method> seen = new HashMap<>();

        for (Method method : type.getMethods()) {
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            if (annotation == null) {
                continue;
            }
            method.trySetAccessible();

            String name = annotation.name().isBlank() ? method.getName() : annotation.name();
            Method clash = seen.put(name, method);
            if (clash != null) {
                throw new IllegalStateException("@AgentTool name '" + name + "' is declared twice in "
                        + type.getName() + ": " + clash + " and " + method
                        + ". Set an explicit name on one of them.");
            }
            descriptors.add(new ToolDescriptor(
                    name,
                    annotation.description(),
                    annotation.risk(),
                    annotation.idempotent(),
                    List.of(annotation.scopes()),
                    schemaBuilder.build(method),
                    method,
                    bean));
        }

        descriptors.sort(Comparator.comparing(ToolDescriptor::name));
        if (!descriptors.isEmpty()) {
            log.info("Scanned {} tool(s) from {}", descriptors.size(), type.getName());
        }
        return descriptors;
    }

    /** Cheap check used by the Spring starter to skip beans without annotations. */
    public static boolean hasAgentTool(Class<?> type) {
        if (type == null) {
            return false;
        }
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(AgentTool.class)) {
                return true;
            }
        }
        return false;
    }

    /** Unwraps CGLIB and JDK proxies so annotations on the target class are visible. */
    private static Class<?> userClass(Class<?> type) {
        Class<?> current = type;
        while (current != null && (current.getName().contains("$$") || Proxy.isProxyClass(current))) {
            current = current.getSuperclass();
        }
        return current == null ? type : current;
    }
}
