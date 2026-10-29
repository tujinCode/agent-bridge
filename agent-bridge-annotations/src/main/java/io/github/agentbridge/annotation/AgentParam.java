package io.github.agentbridge.annotation;

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
