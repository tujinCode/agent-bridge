package io.github.agentbridge.core;

/**
 * Outcome of one tool call.
 *
 * @param error   {@code true} when the call was rejected or threw; the model is
 *                expected to read {@code text} and try a different approach
 * @param text    rendering shown to the model
 * @param payload the raw return value, {@code null} on error
 */
public record ToolCallResult(boolean error, String text, Object payload) {

    public static ToolCallResult ok(String text, Object payload) {
        return new ToolCallResult(false, text, payload);
    }

    public static ToolCallResult failure(String text) {
        return new ToolCallResult(true, text, null);
    }
}
