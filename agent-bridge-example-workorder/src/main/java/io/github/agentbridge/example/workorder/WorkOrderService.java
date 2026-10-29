package io.github.agentbridge.example.workorder;

import io.github.agentbridge.annotation.AgentParam;
import io.github.agentbridge.annotation.AgentTool;
import io.github.agentbridge.annotation.RiskLevel;
import io.github.agentbridge.core.ToolInvocationContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A normal service. The only agent-facing addition is the annotations — no interface,
 * no adapter class, no separate "agent API".
 *
 * <p>The point of the demo is that the methods keep behaving exactly as they do for
 * human callers: same validation, same business rules, same exceptions.
 */
@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

    private final Map<String, WorkOrder> store = new ConcurrentHashMap<>();

    public WorkOrderService() {
        seed("WO-2001", "C-1001", "Cannot log in after password reset", WorkOrder.Status.OPEN, 3, "li.wei");
        seed("WO-2002", "C-1001", "Monthly invoice shows duplicate line item", WorkOrder.Status.IN_PROGRESS, 2, "sun.qi");
        seed("WO-2003", "C-1002", "Export to CSV times out above 50k rows", WorkOrder.Status.OPEN, 4, "li.wei");
        seed("WO-2004", "C-1002", "API returns 500 on empty filter", WorkOrder.Status.CLOSED, 5, "chen.yu");
    }

    @AgentTool(name = "workorder.list",
            description = "List the work orders of one customer, newest first. "
                    + "Use this before acting on a specific order so you know its id.",
            risk = RiskLevel.READ,
            scopes = "workorder:read")
    public List<WorkOrder> list(
            @AgentParam(description = "Customer id, for example C-1001", example = "C-1001") String customerId,
            @AgentParam(description = "Optional status filter: OPEN, IN_PROGRESS, ESCALATED or CLOSED",
                    required = false) WorkOrder.Status status) {

        return store.values().stream()
                .filter(order -> order.customerId().equals(customerId))
                .filter(order -> status == null || order.status() == status)
                .sorted(Comparator.comparing(WorkOrder::createdAt).reversed())
                .toList();
    }

    @AgentTool(name = "workorder.get",
            description = "Fetch a single work order by id, including its current status and priority.",
            risk = RiskLevel.READ,
            scopes = "workorder:read")
    public WorkOrder get(
            @AgentParam(description = "Work order id, for example WO-2001", example = "WO-2001") String workOrderId) {
        return require(workOrderId);
    }

    @AgentTool(name = "workorder.escalate",
            description = "Raise the priority of an open work order. Use when the customer reports the "
                    + "problem is getting worse or is blocking production. Never escalate a closed order.",
            risk = RiskLevel.WRITE,
            idempotent = true,
            scopes = "workorder:write")
    public WorkOrder escalate(
            @AgentParam(description = "Work order id", example = "WO-2001") String workOrderId,
            @AgentParam(description = "New priority: 1 is highest, 5 is lowest")
            @Min(1) @Max(5) int priority,
            ToolInvocationContext context) {

        WorkOrder current = require(workOrderId);
        if (current.status() == WorkOrder.Status.CLOSED) {
            throw new IllegalStateException("work order " + workOrderId + " is closed; reopen it before escalating");
        }
        WorkOrder updated = current.withPriority(priority).withStatus(WorkOrder.Status.ESCALATED);
        store.put(workOrderId, updated);
        log.info("escalated {} to priority {} on behalf of {}", workOrderId, priority, context.callerId());
        return updated;
    }

    @AgentTool(name = "workorder.close",
            description = "Close a work order with a one-line resolution note. "
                    + "Only call this after the customer has confirmed the issue is resolved.",
            risk = RiskLevel.WRITE,
            scopes = "workorder:write")
    public WorkOrder close(
            @AgentParam(description = "Work order id", example = "WO-2002") String workOrderId,
            @AgentParam(description = "One line describing how it was resolved")
            @NotBlank @Size(max = 200) String resolution) {

        WorkOrder current = require(workOrderId);
        WorkOrder closed = current.withStatus(WorkOrder.Status.CLOSED);
        store.put(workOrderId, closed);
        log.info("closed {} with note: {}", workOrderId, resolution);
        return closed;
    }

    @AgentTool(name = "workorder.purge",
            description = "Permanently delete a work order. Irreversible. Only for data-correction requests "
                    + "confirmed by a human operator.",
            risk = RiskLevel.DESTRUCTIVE,
            idempotent = false,
            scopes = "workorder:admin")
    public String purge(
            @AgentParam(description = "Work order id to delete permanently", example = "WO-2004") String workOrderId) {
        WorkOrder removed = store.remove(workOrderId);
        if (removed == null) {
            throw new IllegalArgumentException("no such work order: " + workOrderId);
        }
        return "deleted " + workOrderId;
    }

    private WorkOrder require(String workOrderId) {
        return Optional.ofNullable(store.get(workOrderId))
                .orElseThrow(() -> new IllegalArgumentException("no such work order: " + workOrderId));
    }

    private void seed(String id, String customerId, String title, WorkOrder.Status status, int priority, String assignee) {
        store.put(id, new WorkOrder(id, customerId, title, status, priority, assignee, Instant.now()));
    }
}
