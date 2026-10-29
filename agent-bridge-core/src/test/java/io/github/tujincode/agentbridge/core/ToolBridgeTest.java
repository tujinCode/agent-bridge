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

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tujincode.agentbridge.annotation.AgentParam;
import io.github.tujincode.agentbridge.annotation.AgentTool;
import io.github.tujincode.agentbridge.annotation.RiskLevel;
import io.github.tujincode.agentbridge.core.mcp.McpServer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolBridgeTest {

    /** Stand-in for an existing service: plain annotations, no agent-specific code. */
    static class Fixture {

        @AgentTool(name = "order.find", description = "Find one order.", scopes = "order:read")
        public String find(@AgentParam(name = "id", description = "Order id") String id) {
            return "order:" + id;
        }

        @AgentTool(name = "order.cancel", description = "Cancel an order.",
                risk = RiskLevel.WRITE, idempotent = false)
        public String cancel(@AgentParam(name = "id") String id, ToolInvocationContext context) {
            return "cancelled " + id + " by " + context.callerId();
        }

        @AgentTool(name = "order.purge", description = "Delete an order permanently.",
                risk = RiskLevel.DESTRUCTIVE)
        public String purge(@AgentParam(name = "id") String id) {
            return "purged " + id;
        }

        @AgentTool(name = "report.range", description = "Count rows in a date range.")
        public int count(@AgentParam(name = "from") LocalDate from,
                         @AgentParam(name = "size") @Min(1) @Max(50) int size) {
            return size;
        }
    }

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(RiskLevel.WRITE, ToolCallListener.noop());
        registry.registerAll(new ToolScanner().scan(new Fixture()));
    }

    @Test
    @DisplayName("schema is derived from the method signature, not hand written")
    void schemaDerivedFromSignature() {
        JsonNode schema = registry.find("order.find").orElseThrow().inputSchema();
        JsonNode id = schema.path("properties").path("id");

        assertEquals("string", id.path("type").asText());
        assertEquals("Order id", id.path("description").asText());
        assertTrue(schema.path("required").toString().contains("id"));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }

    @Test
    @DisplayName("bean validation annotations become schema constraints")
    void validationBecomesSchema() {
        JsonNode size = registry.find("report.range").orElseThrow().inputSchema().path("properties").path("size");

        assertEquals("integer", size.path("type").asText());
        assertEquals(1, size.path("minimum").asInt());
        assertEquals(50, size.path("maximum").asInt());
    }

    @Test
    @DisplayName("the invocation context is injected and never appears in the schema")
    void contextIsInjectedNotExposed() {
        JsonNode properties = registry.find("order.cancel").orElseThrow().inputSchema().path("properties");
        assertFalse(properties.has("context"));

        ToolCallResult result = registry.call("order.cancel", Map.of("id", "A1"),
                new ToolInvocationContext("alice", "req-1", null));

        assertFalse(result.error());
        assertEquals("cancelled A1 by alice", result.text());
    }

    @Test
    @DisplayName("a tool above the risk ceiling is rejected with an actionable message")
    void destructiveToolIsBlocked() {
        ToolCallResult result = registry.call("order.purge", Map.of("id", "A1"));

        assertTrue(result.error());
        assertTrue(result.text().contains("approval required"), result.text());
    }

    @Test
    @DisplayName("a missing argument tells the model exactly what to fix")
    void missingArgumentIsReported() {
        ToolCallResult result = registry.call("order.find", Map.of());

        assertTrue(result.error());
        assertTrue(result.text().contains("missing required argument 'id'"), result.text());
    }

    @Test
    @DisplayName("arguments are coerced into the declared java types")
    void argumentsAreCoerced() {
        ToolCallResult result = registry.call("report.range", Map.of("from", "2026-01-01", "size", 7));

        assertFalse(result.error(), result.text());
        assertEquals(7, result.payload());
    }

    @Test
    @DisplayName("MCP tools/list and tools/call speak the protocol")
    void mcpRoundTrip() {
        McpServer server = new McpServer(registry, "test-server", "0.0.1");

        String listed = server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertTrue(listed.contains("order.find"), listed);
        assertTrue(listed.contains("\"readOnlyHint\":true"), listed);
        assertTrue(listed.contains("\"destructiveHint\":true"), listed);

        String called = server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"order.find\",\"arguments\":{\"id\":\"A9\"}}}");
        assertTrue(called.contains("order:A9"), called);
        assertTrue(called.contains("\"isError\":false"), called);
    }

    @Test
    @DisplayName("notifications are never answered")
    void notificationsAreSilent() {
        McpServer server = new McpServer(registry, "test-server", "0.0.1");

        assertEquals(null, server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }
}
