package io.github.agentbridge.core;

/**
 * Raised when a tool cannot be invoked: missing argument, type mismatch, or the
 * business method itself failed.
 *
 * <p>The message is shown to the model verbatim, so it must explain what to fix
 * rather than dump a stack trace.
 */
public class ToolInvocationException extends RuntimeException {

    public ToolInvocationException(String message) {
        super(message);
    }

    public ToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
