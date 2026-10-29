package io.github.agentbridge.core;

/**
 * Receives one event per tool call. Register a bean of this type to persist the
 * audit trail, publish metrics, or forward to OpenTelemetry.
 *
 * <p>Implementations must not throw: a broken listener must never break a tool call.
 */
@FunctionalInterface
public interface ToolCallListener {

    void onCall(ToolCallRecord record);

    /** Listener that discards everything. */
    static ToolCallListener noop() {
        return record -> {
        };
    }
}
