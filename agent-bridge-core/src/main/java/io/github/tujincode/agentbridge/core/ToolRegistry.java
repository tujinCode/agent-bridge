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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.tujincode.agentbridge.annotation.RiskLevel;
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
import java.util.concurrent.CopyOnWriteArrayList;

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
    private final List<ToolCallInterceptor> interceptors = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper;
    private final ToolInvoker invoker;
    private final ToolCallListener listener;

    private volatile RiskLevel maxAutoRisk;

    public ToolRegistry() {
        this(RiskLevel.READ, ToolCallListener.noop());
    }

    public ToolRegistry(RiskLevel maxAutoRisk, ToolCallListener listener) {
        this(maxAutoRisk, listener, List.of());
    }

    public ToolRegistry(RiskLevel maxAutoRisk,
                        ToolCallListener listener,
                        Collection<ToolCallInterceptor> interceptors) {
        this.maxAutoRisk = maxAutoRisk == null ? RiskLevel.READ : maxAutoRisk;
        this.listener = listener == null ? ToolCallListener.noop() : listener;
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                // Models read "2026-10-29T08:57:18Z" far better than "1793264394.7039056".
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.invoker = new ToolInvoker();
        if (interceptors != null) {
            this.interceptors.addAll(interceptors);
        }
    }

    /**
     * Appends an interceptor to the chain. Registration happens while the application
     * context starts, before any client can call a tool; there is no reordering and no
     * de-duplication, because a chain whose order depends on who registered first is a
     * chain nobody can reason about.
     */
    public void addInterceptor(ToolCallInterceptor interceptor) {
        if (interceptor == null) {
            return;
        }
        interceptors.add(interceptor);
    }

    public List<ToolCallInterceptor> interceptors() {
        return List.copyOf(interceptors);
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
        ToolCallExchange exchange = new ToolCallExchange(descriptor, arguments, ctx);

        if (!descriptor.risk().allowedBy(maxAutoRisk)) {
            String message = "approval required: '" + name + "' is classified " + descriptor.risk()
                    + " but this gateway runs at most " + maxAutoRisk + " automatically. "
                    + "Explain the intended effect to the user and ask for confirmation.";
            notify(new ToolCallRecord(name, ctx.callerId(), ctx.requestId(), 0L, false, message));
            return ToolCallResult.failure(message);
        }

        // Snapshot the chain once per call: an interceptor registered mid-flight must not
        // shift the index out from under an invocation that already started.
        ToolCallInterceptor[] snapshot = interceptors.toArray(new ToolCallInterceptor[0]);

        long start = System.nanoTime();
        try {
            Object payload = invokeThroughChain(exchange, snapshot, 0);
            // Read the context after the chain ran: an identity interceptor may have replaced
            // the caller the client claimed with the one a credential proved.
            ToolInvocationContext effective = exchange.effectiveContext();
            notify(new ToolCallRecord(name, effective.callerId(), effective.requestId(),
                    elapsedMillis(start), true, null));
            return ToolCallResult.ok(render(payload), payload);
        } catch (ToolInvocationException e) {
            ToolInvocationContext effective = exchange.effectiveContext();
            notify(new ToolCallRecord(name, effective.callerId(), effective.requestId(),
                    elapsedMillis(start), false, e.getMessage()));
            return ToolCallResult.failure("tool failed: " + e.getMessage());
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            log.warn("Unexpected failure in tool '{}'", name, e);
            ToolInvocationContext effective = exchange.effectiveContext();
            notify(new ToolCallRecord(name, effective.callerId(), effective.requestId(),
                    elapsedMillis(start), false, message));
            return ToolCallResult.failure("tool failed: " + message);
        }
    }

    private Object invokeThroughChain(ToolCallExchange exchange, ToolCallInterceptor[] chain, int index) {
        if (index >= chain.length) {
            // The terminal step reads the effective context, so any identity an interceptor
            // established above is what the business method is bound with.
            return invoker.invoke(exchange.descriptor(), exchange.arguments(), exchange.effectiveContext());
        }
        return chain[index].invoke(exchange, () -> invokeThroughChain(exchange, chain, index + 1));
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
