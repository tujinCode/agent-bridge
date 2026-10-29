package io.github.agentbridge.core;

import io.github.agentbridge.annotation.AgentParam;

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
