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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Derives a JSON Schema from a Java method signature.
 *
 * <p>The point of deriving instead of hand-writing: rename a parameter, change a
 * type, add {@code @Size(min = 5)} — the schema follows automatically. There is no
 * second copy of the contract that can rot.
 */
public final class JsonSchemaBuilder {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaBuilder.class);

    private final ObjectMapper mapper;

    public JsonSchemaBuilder() {
        this(new ObjectMapper());
    }

    public JsonSchemaBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode build(Method method) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = mapper.createArrayNode();

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            if (parameter.getType() == ToolInvocationContext.class) {
                continue;
            }
            if (!ParameterIntrospector.hasReadableName(parameter)
                    && (parameter.getAnnotation(io.github.tujincode.agentbridge.annotation.AgentParam.class) == null
                    || parameter.getAnnotation(io.github.tujincode.agentbridge.annotation.AgentParam.class).name().isBlank())) {
                log.warn("Parameter {} of {} has no usable name. Compile with -parameters, "
                        + "or set @AgentParam(name = \"...\").", i, method.getDeclaringClass().getSimpleName() + "#" + method.getName());
            }

            String name = ParameterIntrospector.name(parameter, i);
            ObjectNode property = properties.putObject(name);
            describeType(property, parameter.getType(), parameter.getParameterizedType());
            applyValidation(property, parameter);

            String description = ParameterIntrospector.description(parameter);
            if (description != null) {
                property.put("description", description);
            }
            String example = ParameterIntrospector.example(parameter);
            if (example != null) {
                ArrayNode examples = mapper.createArrayNode();
                examples.add(example);
                property.set("examples", examples);
            }
            if (ParameterIntrospector.required(parameter)) {
                required.add(name);
            }
        }

        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private void describeType(ObjectNode target, Class<?> type, Type genericType) {
        if (type == Optional.class) {
            Type inner = typeArgument(genericType, 0);
            describeType(target, rawClass(inner, String.class), inner);
            return;
        }
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) {
            target.put("type", "string");
            return;
        }
        if (type == UUID.class) {
            target.put("type", "string");
            target.put("format", "uuid");
            return;
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class
                || type == BigInteger.class) {
            target.put("type", "integer");
            return;
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class
                || type == BigDecimal.class) {
            target.put("type", "number");
            return;
        }
        if (type == boolean.class || type == Boolean.class) {
            target.put("type", "boolean");
            return;
        }
        if (type.isEnum()) {
            target.put("type", "string");
            ArrayNode values = target.putArray("enum");
            for (Object constant : type.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return;
        }
        if (type == LocalDate.class) {
            target.put("type", "string");
            target.put("format", "date");
            return;
        }
        if (type == LocalDateTime.class || type == Instant.class
                || type == OffsetDateTime.class || type == ZonedDateTime.class) {
            target.put("type", "string");
            target.put("format", "date-time");
            return;
        }
        if (type.isArray()) {
            target.put("type", "array");
            describeType(target.putObject("items"), type.getComponentType(), type.getComponentType());
            return;
        }
        if (Collection.class.isAssignableFrom(type)) {
            target.put("type", "array");
            Type element = typeArgument(genericType, 0);
            describeType(target.putObject("items"), rawClass(element, String.class), element);
            return;
        }
        if (Map.class.isAssignableFrom(type)) {
            target.put("type", "object");
            target.putObject("additionalProperties");
            return;
        }
        target.put("type", "object");
        target.put("x-java-type", type.getName());
    }

    private void applyValidation(ObjectNode target, Parameter parameter) {
        String jsonType = target.path("type").asText();

        Size size = parameter.getAnnotation(Size.class);
        if (size != null) {
            if ("string".equals(jsonType)) {
                if (size.min() > 0) {
                    target.put("minLength", size.min());
                }
                if (size.max() < Integer.MAX_VALUE) {
                    target.put("maxLength", size.max());
                }
            } else if ("array".equals(jsonType)) {
                if (size.min() > 0) {
                    target.put("minItems", size.min());
                }
                if (size.max() < Integer.MAX_VALUE) {
                    target.put("maxItems", size.max());
                }
            }
        }

        Min min = parameter.getAnnotation(Min.class);
        if (min != null) {
            target.put("minimum", min.value());
        }
        Max max = parameter.getAnnotation(Max.class);
        if (max != null) {
            target.put("maximum", max.value());
        }
        if (parameter.isAnnotationPresent(Positive.class)) {
            target.put("exclusiveMinimum", 0);
        }
        if (parameter.isAnnotationPresent(NotBlank.class) && "string".equals(jsonType)) {
            target.put("minLength", 1);
        }
        if (parameter.isAnnotationPresent(NotEmpty.class) && "array".equals(jsonType)) {
            target.put("minItems", 1);
        }
        Pattern pattern = parameter.getAnnotation(Pattern.class);
        if (pattern != null) {
            target.put("pattern", pattern.regexp());
        }
        if (parameter.isAnnotationPresent(Email.class)) {
            target.put("format", "email");
        }
    }

    private static Type typeArgument(Type genericType, int index) {
        if (genericType instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (index < arguments.length) {
                Type argument = arguments[index];
                if (argument instanceof WildcardType wildcard && wildcard.getUpperBounds().length == 1) {
                    return wildcard.getUpperBounds()[0];
                }
                return argument;
            }
        }
        return null;
    }

    private static Class<?> rawClass(Type type, Class<?> fallback) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return fallback;
    }

    /** Human readable type hint, used in logs and tests. */
    static String describe(ObjectNode schema) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        schema.path("properties").fieldNames().forEachRemaining(name -> {
            String type = schema.path("properties").path(name).path("type").asText("?");
            joiner.add(name + ":" + type);
        });
        return joiner.toString();
    }
}
