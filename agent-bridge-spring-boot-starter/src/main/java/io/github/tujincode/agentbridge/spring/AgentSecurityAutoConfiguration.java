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

import io.github.tujincode.agentbridge.core.ToolRegistry;
import io.github.tujincode.agentbridge.spring.security.SecurityContextInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Wires caller identity when the application already uses Spring Security.
 *
 * <p>The guard is {@code @ConditionalOnClass}, not a dependency: adding this starter to a
 * service that has never heard of Spring Security must not pull an authorisation framework
 * into it. If the class is absent, none of these beans exist and tool calls behave exactly
 * as they did before.
 */
@AutoConfiguration(after = AgentBridgeAutoConfiguration.class)
@ConditionalOnClass(SecurityContextHolder.class)
@ConditionalOnProperty(prefix = "agent-bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentSecurityAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SecurityContextInterceptor agentBridgeSecurityContextInterceptor(AgentBridgeProperties properties) {
        return new SecurityContextInterceptor(properties.getSecurity());
    }

    /**
     * Attaches the interceptor once every singleton exists.
     *
     * <p>Registration is late on purpose: the registry bean is created before this
     * auto-configuration runs, so the interceptor cannot be passed to its constructor
     * without a cycle. Nothing can call a tool during startup, so there is no window in
     * which a call sees a partially built chain — but the ordering is worth knowing about
     * if a transport ever starts accepting requests before context refresh finishes.
     */
    @Bean
    public SmartInitializingSingleton agentBridgeSecurityInterceptorRegistration(
            ToolRegistry registry, ObjectProvider<SecurityContextInterceptor> interceptor) {
        return () -> {
            SecurityContextInterceptor resolved = interceptor.getIfAvailable();
            if (resolved != null) {
                registry.addInterceptor(resolved);
            }
        };
    }

    /** Says out loud which mode is active, because silence here is how people ship NONE. */
    @Bean
    public SmartInitializingSingleton agentBridgeIdentityModeReport(AgentBridgeProperties properties) {
        return () -> {
            AgentBridgeProperties.Security security = properties.getSecurity();
            switch (security.getMode()) {
                case NONE -> log.info("agent-bridge identity mode: NONE — no caller is established. "
                        + "Tools without access rules will run; tools with them will be denied.");
                case ASSERTED -> log.warn("agent-bridge identity mode: ASSERTED — the caller id and "
                        + "authorities in _meta are trusted verbatim. Any client can claim any identity. "
                        + "This is not authentication and must not be reachable from a network.");
                case DELEGATED -> log.info("agent-bridge identity mode: DELEGATED — HS256 tokens are "
                        + "verified and become the authenticated caller{}.",
                        security.getIssuer() == null || security.getIssuer().isBlank()
                                ? " (no issuer pinned)"
                                : " for issuer '" + security.getIssuer() + "'");
            }
        };
    }
}
