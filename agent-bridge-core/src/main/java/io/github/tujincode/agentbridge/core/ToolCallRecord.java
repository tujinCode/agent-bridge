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
