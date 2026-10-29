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
package io.github.tujincode.agentbridge.spring.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tujincode.agentbridge.annotation.RiskLevel;
import io.github.tujincode.agentbridge.core.ToolCallExchange;
import io.github.tujincode.agentbridge.core.ToolDescriptor;
import io.github.tujincode.agentbridge.core.ToolInvocationContext;
import io.github.tujincode.agentbridge.core.ToolInvocationException;
import io.github.tujincode.agentbridge.spring.AgentBridgeProperties;
import io.github.tujincode.agentbridge.spring.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level checks on the interceptor, without a Spring context: the behaviour worth
 * pinning here is the state management around the call and the identity it publishes,
 * not the wiring.
 */
class SecurityContextInterceptorTest {

    private static final String SECRET = "unit-secret-long-enough-to-pass-the-eye-test";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the caller is visible during the call and gone afterwards")
    void establishesThenRestores() {
        authenticateAmbiently("human.supervisor", "demo:write");
        SecurityContextInterceptor interceptor = interceptor(delegated(SECRET, null));
        ToolCallExchange exchange = exchange(descriptor("demo.escalate", "demo:write"),
                contextWith(TestTokens.freshToken(SECRET, "zhang.cong", "demo:write")));

        AtomicReference<String> seenInside = new AtomicReference<>();
        interceptor.invoke(exchange, () -> {
            seenInside.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return null;
        });

        assertThat(seenInside.get()).isEqualTo("zhang.cong");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .as("an agent call must not leave its identity behind on the thread")
                .isEqualTo("human.supervisor");
    }

    @Test
    @DisplayName("the verified caller replaces the name the client claimed")
    void verifiedCallerReplacesTheClaimedOne() {
        SecurityContextInterceptor interceptor = interceptor(delegated(SECRET, null));
        ToolCallExchange exchange = exchange(descriptor("demo.escalate"),
                contextWith(TestTokens.freshToken(SECRET, "zhang.cong", "demo:write")));

        interceptor.invoke(exchange, () -> null);

        ToolInvocationContext effective = exchange.effectiveContext();
        assertThat(effective.callerId())
                .as("the request claimed to be 'ignored'; the token said zhang.cong; the token wins")
                .isEqualTo("zhang.cong");
        assertThat(effective.callerVerified())
                .as("an audit record has to say whether the name was checked")
                .isTrue();
    }

    @Test
    @DisplayName("a process-level token is used when the request carries none")
    void fallsBackToTheConfiguredToken() {
        String token = TestTokens.freshToken(SECRET, "svc.batch", "demo:write");
        SecurityContextInterceptor interceptor = interceptor(delegated(SECRET, token));
        ToolCallExchange exchange = exchange(descriptor("demo.escalate", "demo:write"),
                ToolInvocationContext.anonymous());

        interceptor.invoke(exchange, () -> null);

        assertThat(exchange.effectiveContext().callerId()).isEqualTo("svc.batch");
    }

    @Test
    @DisplayName("NONE mode changes nothing about the current thread")
    void noneModeIsTransparent() {
        AgentBridgeProperties.Security config = new AgentBridgeProperties.Security();
        config.setMode(AgentBridgeProperties.Mode.NONE);
        ToolCallExchange exchange = exchange(descriptor("demo.escalate"), contextWith("anything"));

        AtomicReference<Object> seenInside = new AtomicReference<>();
        interceptor(config).invoke(exchange, () -> {
            seenInside.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        });

        assertThat(seenInside.get()).isNull();
        assertThat(exchange.effectiveContext().callerVerified()).isFalse();
    }

    @Test
    @DisplayName("ASSERTED mode trusts the caller id and authorities it is handed")
    void assertedModeTrustsTheClient() {
        AgentBridgeProperties.Security config = new AgentBridgeProperties.Security();
        config.setMode(AgentBridgeProperties.Mode.ASSERTED);

        ToolInvocationContext context = new ToolInvocationContext("zhang.cong", null,
                Map.of(ToolInvocationContext.ATTR_ASSERTED_AUTHORITIES, "demo:write, demo:read"));
        ToolCallExchange exchange = exchange(descriptor("demo.escalate"), context);

        AtomicReference<List<String>> seenInside = new AtomicReference<>();
        interceptor(config).invoke(exchange, () -> {
            seenInside.set(SecurityContextHolder.getContext().getAuthentication()
                    .getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
            return null;
        });

        // Documented as insecure on purpose: this mode exists to be easy, not to be safe.
        assertThat(seenInside.get()).containsExactlyInAnyOrder("demo:write", "demo:read");
        assertThat(exchange.effectiveContext().callerVerified())
                .as("an asserted caller must never be recorded as verified")
                .isFalse();
    }

    @Test
    @DisplayName("a delegated call with no token fails with a message the model can act on")
    void missingTokenIsAnActionableFailure() {
        SecurityContextInterceptor interceptor = interceptor(delegated(SECRET, null));

        assertThatThrownBy(() -> interceptor.invoke(
                exchange(descriptor("demo.escalate"), ToolInvocationContext.anonymous()),
                () -> null))
                .isInstanceOf(ToolInvocationException.class)
                .hasMessageContaining("no caller token")
                .hasMessageContaining("_meta.authorization");
    }

    @Test
    @DisplayName("a tool that declares scopes refuses a token missing them")
    void declaredScopesAreEnforced() {
        SecurityContextInterceptor interceptor = interceptor(delegated(SECRET, null));
        ToolCallExchange exchange = exchange(descriptor("demo.escalate", "demo:write", "demo:admin"),
                contextWith(TestTokens.freshToken(SECRET, "intern", "demo:read")));

        assertThatThrownBy(() -> interceptor.invoke(exchange, () -> null))
                .isInstanceOf(ToolInvocationException.class)
                .hasMessageContaining("requires scope");
    }

    @Test
    @DisplayName("bearer prefix is optional and case-insensitive")
    void acceptsBearerPrefix() {
        SecurityContextInterceptor interceptor = interceptor(delegated(SECRET, null));
        ToolCallExchange exchange = exchange(descriptor("demo.escalate"),
                contextWithRaw("bearer " + TestTokens.freshToken(SECRET, "zhang.cong", "demo:write")));

        interceptor.invoke(exchange, () -> null);

        assertThat(exchange.effectiveContext().callerId()).isEqualTo("zhang.cong");
    }

    private static SecurityContextInterceptor interceptor(AgentBridgeProperties.Security config) {
        return new SecurityContextInterceptor(config);
    }

    private static AgentBridgeProperties.Security delegated(String secret, String staticToken) {
        AgentBridgeProperties.Security config = new AgentBridgeProperties.Security();
        config.setMode(AgentBridgeProperties.Mode.DELEGATED);
        config.setSharedSecret(secret);
        config.setStaticToken(staticToken);
        return config;
    }

    private static ToolCallExchange exchange(ToolDescriptor descriptor, ToolInvocationContext context) {
        return new ToolCallExchange(descriptor, Map.of(), context);
    }

    private static ToolInvocationContext contextWith(String token) {
        return contextWithRaw("Bearer " + token);
    }

    private static ToolInvocationContext contextWithRaw(String headerValue) {
        return new ToolInvocationContext("ignored", "req-unit",
                Map.of(ToolInvocationContext.ATTR_AUTHORIZATION, headerValue));
    }

    private static ToolDescriptor descriptor(String name, String... scopes) {
        try {
            return new ToolDescriptor(name, "test tool", RiskLevel.WRITE, false, List.of(scopes),
                    new ObjectMapper().createObjectNode(), Object.class.getMethod("toString"), new Object());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void authenticateAmbiently(String name, String... authorities) {
        List<GrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .map(authority -> (GrantedAuthority) new SimpleGrantedAuthority(authority))
                .toList();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(name, "n/a", granted));
        SecurityContextHolder.setContext(context);
    }
}
