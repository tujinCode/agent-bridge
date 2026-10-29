package io.github.agentbridge.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentbridge.annotation.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The single place every agent call goes through. Holds the tool catalogue, enforces
 * the risk ceiling, and emits one audit record per call.
 *
 * <p>Deliberately boring and synchronous: no magic, no proxy chains. Anything clever
 * belongs in a listener or the transport.
 */
public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ConcurrentMap<String, ToolDescriptor> tools = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final ToolInvoker invoker;
    private final ToolCallListener listener;

    private volatile RiskLevel maxAutoRisk;

    public ToolRegistry() {
        this(RiskLevel.READ, ToolCallListener.noop());
    }

    public ToolRegistry(RiskLevel maxAutoRisk, ToolCallListener listener) {
        this.maxAutoRisk = maxAutoRisk == null ? RiskLevel.READ : maxAutoRisk;
        this.listener = listener == null ? ToolCallListener.noop() : listener;
        this.mapper = new ObjectMapper().findAndRegisterModules();
        this.invoker = new ToolInvoker();
    }

    public void register(ToolDescriptor descriptor) {
        ToolDescriptor previous = tools.putIfAbsent(descriptor.name(), descriptor);
        if (previous != null) {
            throw new IllegalStateException("duplicate tool name '" + descriptor.name() + "': "
                    + previous + " vs " + descriptor);
        }
    }

    public void registerAll(Collection<ToolDescriptor> descriptors) {
        descriptors.forEach(this::register);
    }

    /** Catalogue in stable name order, so tool listings are diffable. */
    public List<ToolDescriptor> tools() {
        List<ToolDescriptor> snapshot = new ArrayList<>(tools.values());
        snapshot.sort(Comparator.comparing(ToolDescriptor::name));
        return snapshot;
    }

    public Optional<ToolDescriptor> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public RiskLevel maxAutoRisk() {
        return maxAutoRisk;
    }

    public void maxAutoRisk(RiskLevel level) {
        this.maxAutoRisk = level == null ? RiskLevel.READ : level;
    }

    public int size() {
        return tools.size();
    }

    public ToolCallResult call(String name, Map<String, Object> arguments) {
        return call(name, arguments, ToolInvocationContext.anonymous());
    }

    /**
     * Runs one tool call.
     *
     * <p>Never throws for tool-level failures: the returned {@link ToolCallResult}
     * carries the error so the model can read it and self-correct.
     */
    public ToolCallResult call(String name, Map<String, Object> arguments, ToolInvocationContext context) {
        ToolDescriptor descriptor = tools.get(name);
        if (descriptor == null) {
            return ToolCallResult.failure("unknown tool '" + name + "'. Call tools/list to see what is available.");
        }
        ToolInvocationContext ctx = context == null ? ToolInvocationContext.anonymous() : context;

        if (!descriptor.risk().allowedBy(maxAutoRisk)) {
            String message = "approval required: '" + name + "' is classified " + descriptor.risk()
                    + " but this gateway runs at most " + maxAutoRisk + " automatically. "
                    + "Explain the intended effect to the user and ask for confirmation.";
            notify(new ToolCallRecord(name, ctx.callerId(), ctx.requestId(), 0L, false, message));
            return ToolCallResult.failure(message);
        }

        long start = System.nanoTime();
        try {
            Object payload = invoker.invoke(descriptor, arguments, ctx);
            notify(new ToolCallRecord(name, ctx.callerId(), ctx.requestId(), elapsedMillis(start), true, null));
            return ToolCallResult.ok(render(payload), payload);
        } catch (ToolInvocationException e) {
            notify(new ToolCallRecord(name, ctx.callerId(), ctx.requestId(), elapsedMillis(start), false, e.getMessage()));
            return ToolCallResult.failure("tool failed: " + e.getMessage());
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            log.warn("Unexpected failure in tool '{}'", name, e);
            notify(new ToolCallRecord(name, ctx.callerId(), ctx.requestId(), elapsedMillis(start), false, message));
            return ToolCallResult.failure("tool failed: " + message);
        }
    }

    private String render(Object payload) {
        if (payload == null) {
            return "null";
        }
        if (payload instanceof CharSequence text) {
            return text.toString();
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    private void notify(ToolCallRecord record) {
        try {
            listener.onCall(record);
        } catch (RuntimeException e) {
            log.warn("ToolCallListener failed for '{}'", record.toolName(), e);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
