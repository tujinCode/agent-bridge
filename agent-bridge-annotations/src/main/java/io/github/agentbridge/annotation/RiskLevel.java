package io.github.agentbridge.annotation;

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
