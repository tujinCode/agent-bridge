package io.github.agentbridge.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Invokes the business method behind a tool. Kept separate from the registry so a
 * future transport (local call, gRPC, worker pool) can reuse it unchanged.
 */
public final class ToolInvoker {

    private final ArgumentBinder binder;

    public ToolInvoker() {
        this(new ArgumentBinder());
    }

    public ToolInvoker(ArgumentBinder binder) {
        this.binder = binder;
    }

    public Object invoke(ToolDescriptor descriptor, Map<String, Object> arguments, ToolInvocationContext context) {
        Method method = descriptor.method();
        Object[] bound = binder.bind(method, arguments, context);
        try {
            return method.invoke(descriptor.target(), bound);
        } catch (IllegalAccessException e) {
            throw new ToolInvocationException("tool '" + descriptor.name() + "' is not accessible", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ToolInvocationException(describe(cause), cause);
        }
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getName() : message;
    }
}
