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

import io.github.tujincode.agentbridge.annotation.AgentParam;

import java.lang.reflect.Parameter;
import java.util.Optional;

/**
 * Reads parameter metadata once, so the schema builder and the argument binder can
 * never disagree about a parameter's name or whether it is required.
 */
final class ParameterIntrospector {

    private ParameterIntrospector() {
    }

    /** Schema property name: explicit override first, compiled name second. */
    static String name(Parameter parameter, int index) {
        AgentParam declared = parameter.getAnnotation(AgentParam.class);
        if (declared != null && !declared.name().isBlank()) {
            return declared.name();
        }
        return hasReadableName(parameter) ? parameter.getName() : "arg" + index;
    }

    /**
     * False when the parameter name was erased to {@code arg0}, which happens if the
     * project was compiled without {@code -parameters}.
     */
    static boolean hasReadableName(Parameter parameter) {
        String compiled = parameter.getName();
        return compiled != null && !compiled.isBlank() && !compiled.matches("arg\\d+");
    }

    static boolean required(Parameter parameter) {
        if (parameter.getType() == Optional.class) {
            return false;
        }
        AgentParam declared = parameter.getAnnotation(AgentParam.class);
        return declared == null || declared.required();
    }

    static String description(Parameter parameter) {
        AgentParam declared = parameter.getAnnotation(AgentParam.class);
        if (declared == null || declared.description().isBlank()) {
            return null;
        }
        return declared.description();
    }

    static String example(Parameter parameter) {
        AgentParam declared = parameter.getAnnotation(AgentParam.class);
        if (declared == null || declared.example().isBlank()) {
            return null;
        }
        return declared.example();
    }
}
