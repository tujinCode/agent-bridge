package io.github.agentbridge.example.workorder;

import java.time.Instant;

/** A work order as it already exists in the legacy system. Nothing agent-specific here. */
public record WorkOrder(String id,
                        String customerId,
                        String title,
                        Status status,
                        int priority,
                        String assignee,
                        Instant createdAt) {

    public enum Status {
        OPEN,
        IN_PROGRESS,
        ESCALATED,
        CLOSED
    }

    public WorkOrder withStatus(Status newStatus) {
        return new WorkOrder(id, customerId, title, newStatus, priority, assignee, createdAt);
    }

    public WorkOrder withPriority(int newPriority) {
        return new WorkOrder(id, customerId, title, status, newPriority, assignee, createdAt);
    }
}
