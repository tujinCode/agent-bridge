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
 * Documents a single tool parameter for the model and, when needed, overrides
 * how it appears in the generated JSON Schema.
 *
 * <p>Bean Validation annotations ({@code @NotNull}, {@code @Size}, {@code @Min},
 * {@code @Pattern}, ...) are read as well, so constraints are usually declared
 * only once.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AgentParam {

    /** Schema property name. Defaults to the compiled parameter name. */
    String name() default "";

    /** Human/model readable meaning of the parameter. Keep it short and concrete. */
    String description() default "";

    /**
     * Whether the Agent must supply this parameter.
     * Ignored when the parameter type is {@code Optional}.
     */
    boolean required() default true;

    /** Example value shown to the model. Purely advisory. */
    String example() default "";
}
