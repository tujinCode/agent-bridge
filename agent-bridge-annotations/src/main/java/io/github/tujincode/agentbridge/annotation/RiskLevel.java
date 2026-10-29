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

/**
 * How much damage a tool call can do. The gateway uses this to decide whether a
 * call may run autonomously, needs human confirmation, or is blocked outright.
 *
 * <p>The levels are ordered: {@code READ < WRITE < DESTRUCTIVE}.
 */
public enum RiskLevel {

    /** Reads data, never mutates state. Safe to call without confirmation. */
    READ(0),

    /** Mutates data, but the effect can normally be undone by a human. */
    WRITE(1),

    /** Irreversible: deletes, transfers, sends, or charges money. */
    DESTRUCTIVE(2);

    private final int level;

    RiskLevel(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    /**
     * @param ceiling the highest risk the gateway lets through automatically
     * @return {@code true} if this risk is acceptable under {@code ceiling}
     */
    public boolean allowedBy(RiskLevel ceiling) {
        return this.level <= ceiling.level;
    }
}
