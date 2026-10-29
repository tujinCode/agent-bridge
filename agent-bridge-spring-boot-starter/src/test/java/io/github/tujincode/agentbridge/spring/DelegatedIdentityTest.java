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
package io.github.tujincode.agentbridge.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tujincode.agentbridge.core.ToolCallResult;
import io.github.tujincode.agentbridge.core.ToolRegistry;
import io.github.tujincode.agentbridge.core.mcp.McpServer;
import io.github.tujincode.agentbridge.spring.support.SecurityTestApplication;
import io.github.tujincode.agentbridge.spring.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim the README makes, tested end to end.
 *
 * <p>Every case goes through the real MCP entry point — a JSON-RPC {@code tools/call} — rather
 * than calling the registry directly, because the interesting failure mode is in the seam the
 * transport sits on. In each successful case the request deliberately carries
 * {@code _meta.callerId = "ignored"}: if the assertion on the business method's output sees a
 * different name, the name can only have come from the token.
 */
@SpringBootTest(
        classes = SecurityTestApplication.class,
        properties = {
                "agent-bridge.transport=none",
                "agent-bridge.max-auto-risk=WRITE",
                "agent-bridge.security.mode=DELEGATED",
                "agent-bridge.security.shared-secret=" + DelegatedIdentityTest.SECRET,
                "agent-bridge.security.issuer=agent-bridge-test"
        })
class DelegatedIdentityTest {

    static final String SECRET = "integration-secret-long-enough-to-look-real";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private McpServer server;

    @Test
    @DisplayName("a verified token becomes the caller that @PreAuthorize evaluates")
    void verifiedTokenBecomesTheAuthenticatedCaller() throws Exception {
        String token = TestTokens.freshToken(SECRET, "zhang.cong", "demo:write");

        JsonNode response = callEscalateWith(token);

        assertThat(response.path("result").path("isError").asBoolean())
                .as("a token carrying demo:write must satisfy @PreAuthorize('hasAuthority(demo:write)')")
                .isFalse();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .as("the request claimed to be 'ignored'; anything else in the output came from the token")
                .contains("escalated X-1 by zhang.cong");
    }

    @Test
    @DisplayName("a caller id in _meta is ignored when a token is present")
    void callerIdFromTheRequestIsNotTrusted() throws Exception {
        String token = TestTokens.freshToken(SECRET, "li.wei", "demo:write");

        JsonNode response = callEscalateWith(token);

        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .contains("by li.wei")
                .doesNotContain("ignored");
    }

    @Test
    @DisplayName("a call with no token is refused and says what to fix")
    void missingTokenIsRefused() throws Exception {
        JsonNode response = callEscalateWith(null);

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .contains("no caller token");
    }

    @Test
    @DisplayName("an expired token is refused")
    void expiredTokenIsRefused() throws Exception {
        String token = TestTokens.token(SECRET, "zhang.cong", "demo:write", Instant.now().minusSeconds(600));

        JsonNode response = callEscalateWith(token);

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .contains("expired");
    }

    @Test
    @DisplayName("a token without the scope the tool declares is refused before the method runs")
    void tokenWithoutDeclaredScopeIsRefused() throws Exception {
        String token = TestTokens.freshToken(SECRET, "intern", "demo:read");

        JsonNode response = callEscalateWith(token);

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .as("@AgentTool(scopes = \"demo:write\") must mean something, not just decorate the method")
                .contains("requires scope")
                .contains("demo:write");
    }

    @Test
    @DisplayName("the identity follows the call to another thread, not the thread that started it")
    void identityFollowsTheCallAcrossThreads() throws Exception {
        String token = TestTokens.freshToken(SECRET, "zhang.cong", "demo:write");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ToolCallResult> future = pool.submit(() -> registry.call("demo.escalate",
                    java.util.Map.of("id", "X-9"),
                    new io.github.tujincode.agentbridge.core.ToolInvocationContext(
                            "ignored", "req-thread",
                            java.util.Map.of("authorization", "Bearer " + token))));

            ToolCallResult result = future.get();

            assertThat(SecurityContextHolder.getContext().getAuthentication())
                    .as("the pool thread must not keep the caller after the call")
                    .isNull();
            assertThat(result.error()).isFalse();
            assertThat(result.text()).contains("by zhang.cong");
        } finally {
            pool.shutdown();
        }
    }

    private JsonNode callEscalateWith(String token) throws Exception {
        String meta = token == null
                ? ",\"_meta\":{\"callerId\":\"ignored\",\"requestId\":\"req-1\"}"
                : ",\"_meta\":{\"callerId\":\"ignored\",\"requestId\":\"req-1\","
                + "\"authorization\":\"Bearer " + token + "\"}";
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"demo.escalate\",\"arguments\":{\"id\":\"X-1\"}" + meta + "}}";
        return JSON.readTree(server.handle(request));
    }
}
