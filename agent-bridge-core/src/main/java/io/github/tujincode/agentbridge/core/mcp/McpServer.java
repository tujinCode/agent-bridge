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
package io.github.tujincode.agentbridge.core.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.tujincode.agentbridge.annotation.RiskLevel;
import io.github.tujincode.agentbridge.core.ToolCallResult;
import io.github.tujincode.agentbridge.core.ToolDescriptor;
import io.github.tujincode.agentbridge.core.ToolInvocationContext;
import io.github.tujincode.agentbridge.core.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal MCP server over the stdio transport: newline-delimited JSON-RPC 2.0.
 *
 * <p>Implements exactly four methods — {@code initialize}, {@code ping},
 * {@code tools/list}, {@code tools/call} — because that is the whole surface an MCP
 * client needs to use tools. Everything else is deliberately out of scope for now.
 *
 * <p><strong>stdout carries the protocol.</strong> Anything else written to stdout
 * (banner, banner-style logging) corrupts the stream and the client will disconnect.
 * Send logs to stderr.
 */
public final class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    /** Highest protocol revision this server knows about. */
    public static final String LATEST_PROTOCOL_VERSION = "2025-06-18";

    private static final int PARSE_ERROR = -32700;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INTERNAL_ERROR = -32603;

    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final String serverName;
    private final String serverVersion;

    public McpServer(ToolRegistry registry, String serverName, String serverVersion) {
        this(registry, new ObjectMapper().findAndRegisterModules(), serverName, serverVersion);
    }

    public McpServer(ToolRegistry registry, ObjectMapper mapper, String serverName, String serverVersion) {
        this.registry = registry;
        this.mapper = mapper;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
    }

    /** Reads requests from {@code in} and writes responses to {@code out} until EOF. */
    public void serve(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String response = handle(line);
            if (response == null) {
                continue;
            }
            writer.write(response);
            writer.write('\n');
            writer.flush();
        }
        log.info("MCP stream closed after {} tool(s) registered", registry.size());
    }

    /**
     * Handles one raw JSON-RPC message.
     *
     * @return the serialised response, or {@code null} when the message was a
     *         notification and must not be answered
     */
    public String handle(String rawMessage) {
        JsonNode request;
        try {
            request = mapper.readTree(rawMessage);
        } catch (JsonProcessingException e) {
            return error(null, PARSE_ERROR, "parse error: " + e.getOriginalMessage());
        }

        JsonNode id = request.get("id");
        String method = request.path("method").asText("");
        JsonNode params = request.path("params");

        try {
            switch (method) {
                case "initialize":
                    return success(id, initializeResult(params));
                case "ping":
                    return success(id, mapper.createObjectNode());
                case "tools/list":
                    return success(id, toolsListResult());
                case "tools/call":
                    return success(id, toolsCallResult(params));
                case "":
                    return id == null ? null : error(id, METHOD_NOT_FOUND, "missing method");
                default:
                    if (method.startsWith("notifications/")) {
                        return null;
                    }
                    return error(id, METHOD_NOT_FOUND, "method not found: " + method);
            }
        } catch (RuntimeException e) {
            log.error("Failed to handle '{}'", method, e);
            return error(id, INTERNAL_ERROR, e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        }
    }

    private ObjectNode initializeResult(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        String requested = params.path("protocolVersion").asText("");
        result.put("protocolVersion", requested.isBlank() ? LATEST_PROTOCOL_VERSION : requested);

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", serverName);
        serverInfo.put("version", serverVersion);

        result.put("instructions",
                "Tools are generated from @AgentTool methods in the host application. "
                        + "Calls above the configured risk ceiling are rejected until a human confirms.");
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (ToolDescriptor descriptor : registry.tools()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", descriptor.name());
            tool.put("description", descriptor.description());
            tool.set("inputSchema", descriptor.inputSchema());

            ObjectNode annotations = tool.putObject("annotations");
            annotations.put("title", descriptor.name());
            annotations.put("readOnlyHint", descriptor.risk() == RiskLevel.READ);
            annotations.put("destructiveHint", descriptor.risk() == RiskLevel.DESTRUCTIVE);
            annotations.put("idempotentHint", descriptor.idempotent());
            annotations.put("openWorldHint", false);
        }
        return result;
    }

    private ObjectNode toolsCallResult(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode argumentsNode = params.path("arguments");
        Map<String, Object> arguments = argumentsNode.isObject()
                ? mapper.convertValue(argumentsNode, new TypeReference<Map<String, Object>>() {
        })
                : Map.of();

        JsonNode meta = params.path("_meta");
        String callerId = meta.path("callerId").asText("");
        String requestId = meta.path("requestId").asText("");

        // stdio has no headers, so _meta is the only channel available for credentials.
        // Everything here is forwarded verbatim and interpreted by the identity layer;
        // the transport does not decide what counts as proof.
        Map<String, String> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, ToolInvocationContext.ATTR_AUTHORIZATION, meta.path("authorization").asText(""));
        putIfPresent(attributes, ToolInvocationContext.ATTR_ASSERTED_AUTHORITIES, meta.path("authorities").asText(""));
        putIfPresent(attributes, "tenant", meta.path("tenant").asText(""));

        ToolInvocationContext context = new ToolInvocationContext(
                callerId, requestId.isBlank() ? null : requestId, attributes);

        ToolCallResult callResult = registry.call(name, arguments, context);

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", callResult.text());
        result.put("isError", callResult.error());
        return result;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String success(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? mapper.nullNode() : id);
        response.set("result", result);
        return write(response);
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? mapper.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return write(response);
    }

    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise MCP response", e);
        }
    }
}
