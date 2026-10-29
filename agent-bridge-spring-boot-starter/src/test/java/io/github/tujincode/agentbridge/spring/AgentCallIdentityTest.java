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
 * </ul>
 *
 * <p>This class now covers the {@code NONE} mode only: it answers "what happens if the
 * gateway is left alone". {@link DelegatedIdentityTest} covers the case where a token is
 * actually verified, which is what the README promises.
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
    @DisplayName("in NONE mode a callerId in _meta is a hint, never a credential")
    void callerIdInMetaIsNotACredential() throws Exception {
        String request = """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
                  "name":"demo.escalate",
                  "arguments":{"id":"X-1"},
                  "_meta":{"callerId":"zhang.cong","requestId":"req-1"}}}
                """;

        JsonNode response = JSON.readTree(server.handle(request));

        assertThat(response.path("result").path("isError").asBoolean())
                .as("no identity mode is configured here, so a name typed into _meta creates no "
                        + "Authentication and must not satisfy an access rule")
                .isTrue();
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
