package io.github.agentbridge.core;

/**
 * One line of the audit trail. Emitted for every call, successful or not.
 *
 * @param toolName       tool that was called
 * @param callerId       identity the call was made on behalf of
 * @param requestId      idempotency key supplied by the caller, may be {@code null}
 * @param durationMillis wall clock time spent in the business method
 * @param success        whether the business method returned normally
 * @param errorMessage   failure detail, {@code null} on success
 */
public record ToolCallRecord(String toolName,
                             String callerId,
                             String requestId,
                             long durationMillis,
                             boolean success,
                             String errorMessage) {
}
