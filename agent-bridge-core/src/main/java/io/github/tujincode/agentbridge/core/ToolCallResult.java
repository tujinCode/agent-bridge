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
