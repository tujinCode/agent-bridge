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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answers one question: does an agent call actually reach the {@code @PreAuthorize}
 * rules the application already wrote for human callers?
 *
 * <p>The risk ceiling is raised to {@code WRITE} in these tests on purpose. A tool
 * call that never gets past the risk gate would look "denied" for the wrong reason,
 * and a test that passes for the wrong reason is worse than no test.
 *
 * <p>What these tests established when they were first run:
 *
 * <ul>
 *   <li>The bean in the registry <em>is</em> the proxy, so method security fires.
 *       The claim in the README is mechanically true once a caller exists.</li>
 *   <li>It did not fire before, because the scanner skipped every secured bean — see
 *       {@code ToolScanner.hasAgentTool} and the fix that followed.</li>
 *   <li>No code path turns an MCP request into an {@code Authentication}, so a
 *       {@code _meta.callerId} is a hint rather than a credential. The two disabled
 *       tests below are the specification for the missing piece.</li>
 * </ul>
 */
@SpringBootTest(
        classes = SecurityTestApplication.class,
        properties = {
                "agent-bridge.transport=none",
                "agent-bridge.max-auto-risk=WRITE"
        })
class AgentCallIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private McpServer server;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a READ tool runs without any authenticated caller")
    void readToolDoesNotNeedACaller() {
        ToolCallResult result = registry.call("demo.read", Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("read-ok");
    }

    @Test
    @DisplayName("a WRITE tool runs when the caller holds the required authority")
    void writeToolRunsWithAuthority() {
        authenticate("zhang.cong", "demo:write");

        ToolCallResult result = registry.call("demo.escalate", Map.of("id", "X-1"));

        assertThat(result.error())
                .as("the bean scanned into the registry must be the proxied one, "
                        + "otherwise @PreAuthorize is bypassed entirely")
                .isFalse();
        assertThat(result.text()).contains("escalated X-1");
    }

    @Test
    @DisplayName("a WRITE tool is refused when the caller lacks the authority")
    void writeToolIsRefusedWithoutAuthority() {
        authenticate("intern", "demo:read");

        ToolCallResult result = registry.call("demo.escalate", Map.of("id", "X-1"));

        assertThat(result.error()).isTrue();
        assertThat(result.text().toLowerCase()).contains("denied");
    }

    @Test
    @DisplayName("a WRITE tool is refused when nobody is authenticated at all")
    void writeToolIsRefusedWithoutAnyCaller() {
        ToolCallResult result = registry.call("demo.escalate", Map.of("id", "X-1"));

        assertThat(result.error())
                .as("today this is the state of every tool call once method security is "
                        + "enabled: no code path turns the MCP request into an Authentication")
                .isTrue();
    }

    @Test
    @Disabled("v0.5.0 — nothing yet turns an MCP request into an Authentication. "
            + "The caller id from _meta is passed to the business method as a hint and "
            + "must not be trusted: any client can put any name there. Closing this gap "
            + "means validating a token (or an asserted identity, in an explicitly "
            + "insecure mode) and populating the SecurityContext before invocation.")
    @DisplayName("an MCP request that names its caller is authorised as that caller")
    void mcpCallerIdBecomesTheAuthenticatedCaller() throws Exception {
        String request = """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
                  "name":"demo.escalate",
                  "arguments":{"id":"X-1"},
                  "_meta":{"callerId":"zhang.cong","requestId":"req-1"}}}
                """;

        JsonNode response = JSON.readTree(server.handle(request));

        assertThat(response.path("result").path("isError").asBoolean())
                .as("the README promises identity pass-through: a request carrying "
                        + "callerId should be evaluated as that caller, not as nobody")
                .isFalse();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .contains("by zhang.cong");
    }

    @Test
    @Disabled("v0.5.0 — SecurityContextHolder defaults to ThreadLocal, so a call handed "
            + "to another thread arrives with no caller and is denied. This matters before "
            + "the HTTP transport lands, not after: the fix is to carry the context "
            + "explicitly through the invocation (a wrapper on the MCP loop, or a "
            + "DelegatingSecurityContextExecutor) rather than to switch the holder to "
            + "INHERITABLE_THREAD_LOCAL, which leaks the caller into threads that should "
            + "not see it.")
    @DisplayName("the security context survives a hand-off to another thread")
    void securityContextSurvivesThreadHandoff() throws Exception {
        authenticate("zhang.cong", "demo:write");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ToolCallResult> future = pool.submit(
                    () -> registry.call("demo.escalate", Map.of("id", "X-1")));

            assertThat(future.get().error())
                    .as("any async or pooled transport inherits this problem: "
                            + "ThreadLocal context does not follow the call")
                    .isFalse();
        } finally {
            pool.shutdown();
        }
    }

    private static void authenticate(String callerId, String... authorities) {
        List<SimpleGrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(callerId, "n/a", granted);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
