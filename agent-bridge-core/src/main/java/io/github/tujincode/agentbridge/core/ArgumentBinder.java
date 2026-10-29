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

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the {@code arguments} object received over the wire into a Java argument array.
 *
 * <p>Two rules matter here. Missing required arguments fail loudly with a message the
 * model can act on. And {@link ToolInvocationContext} is injected, never bound from
 * the payload — the model cannot name itself.
 */
public final class ArgumentBinder {

    private final ObjectMapper mapper;

    public ArgumentBinder() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public ArgumentBinder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Object[] bind(Method method, Map<String, Object> arguments, ToolInvocationContext context) {
        Parameter[] parameters = method.getParameters();
        Object[] bound = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];

            if (parameter.getType() == ToolInvocationContext.class) {
                bound[i] = context;
                continue;
            }

            String name = ParameterIntrospector.name(parameter, i);
            Object raw = arguments == null ? null : arguments.get(name);

            if (raw == null) {
                if (ParameterIntrospector.required(parameter)) {
                    throw new ToolInvocationException("missing required argument '" + name + "'");
                }
                bound[i] = defaultValue(parameter);
                continue;
            }
            bound[i] = convert(raw, parameter, name);
        }
        return bound;
    }

    private Object convert(Object raw, Parameter parameter, String name) {
        Class<?> type = parameter.getType();
        Type genericType = parameter.getParameterizedType();

        try {
            if (type == Optional.class) {
                Type innerType = genericType instanceof ParameterizedType parameterized
                        ? parameterized.getActualTypeArguments()[0]
                        : String.class;
                JavaType inner = mapper.getTypeFactory().constructType(innerType);
                return Optional.ofNullable(mapper.convertValue(raw, inner));
            }
            if (type == String.class && raw instanceof String text) {
                return text;
            }
            return mapper.convertValue(raw, mapper.getTypeFactory().constructType(genericType));
        } catch (IllegalArgumentException e) {
            throw new ToolInvocationException(
                    "argument '" + name + "' is not a valid " + type.getSimpleName() + ": " + rootMessage(e), e);
        }
    }

    private static Object defaultValue(Parameter parameter) {
        Class<?> type = parameter.getType();
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
