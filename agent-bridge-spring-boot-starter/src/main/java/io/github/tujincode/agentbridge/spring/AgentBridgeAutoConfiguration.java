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

import io.github.tujincode.agentbridge.core.ToolCallListener;
import io.github.tujincode.agentbridge.core.ToolDescriptor;
import io.github.tujincode.agentbridge.core.ToolRegistry;
import io.github.tujincode.agentbridge.core.ToolScanner;
import io.github.tujincode.agentbridge.core.mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wires the framework-free core into a Spring Boot application.
 *
 * <p>Nothing here is conditional on web: the same jar works inside a servlet app,
 * a batch job, or a plain {@code main()}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "agent-bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentBridgeProperties.class)
public class AgentBridgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentBridgeAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry agentBridgeToolRegistry(AgentBridgeProperties properties,
                                                ObjectProvider<ToolCallListener> listeners) {
        ToolCallListener listener = listeners.getIfUnique(ToolCallListener::noop);
        return new ToolRegistry(properties.getMaxAutoRisk(), listener);
    }

    @Bean
    @ConditionalOnMissingBean
    public McpServer agentBridgeMcpServer(ToolRegistry registry, AgentBridgeProperties properties) {
        return new McpServer(registry, properties.getServerName(), properties.getServerVersion());
    }

    /**
     * Scans after all singletons exist, so lazy proxies and AOP-wrapped beans are
     * already in their final shape.
     */
    @Bean
    public SmartInitializingSingleton agentBridgeToolDiscovery(ApplicationContext context,
                                                               ToolRegistry registry,
                                                               AgentBridgeProperties properties) {
        return () -> {
            ToolScanner scanner = new ToolScanner();
            List<ToolDescriptor> discovered = new ArrayList<>();

            for (String beanName : context.getBeanDefinitionNames()) {
                Class<?> type;
                try {
                    type = context.getType(beanName);
                } catch (RuntimeException e) {
                    continue;
                }
                if (!ToolScanner.hasAgentTool(type) || !isIncluded(type, properties)) {
                    continue;
                }
                try {
                    discovered.addAll(scanner.scan(context.getBean(beanName)));
                } catch (RuntimeException e) {
                    log.warn("Skipping bean '{}' while scanning for @AgentTool: {}", beanName, e.getMessage());
                }
            }

            registry.registerAll(discovered);
            if (discovered.isEmpty()) {
                log.info("agent-bridge found no @AgentTool methods. The MCP server will expose an empty catalogue.");
            } else {
                log.info("agent-bridge exposed {} tool(s): {}", discovered.size(),
                        discovered.stream().map(ToolDescriptor::name).collect(Collectors.joining(", ")));
                log.info("agent-bridge risk ceiling is {}; higher-risk tools require confirmation.",
                        registry.maxAutoRisk());
            }
            warnAboutGuardedToolsWithoutIdentity(discovered, properties.getSecurity().getMode());
        };
    }

    /**
     * The failure this prevents: an application enables method security, this gateway is
     * left in {@code NONE}, and every guarded tool call is denied with nothing in the logs
     * connecting the denial to the gateway. That exact confusion already cost this project
     * one debugging session, so it gets a warning naming the tools.
     *
     * <p>Annotations are matched by name rather than by class so this class needs no
     * compile-time dependency on Spring Security or on Jakarta annotations.
     */
    private static void warnAboutGuardedToolsWithoutIdentity(List<ToolDescriptor> discovered,
                                                             AgentBridgeProperties.Mode mode) {
        if (mode != AgentBridgeProperties.Mode.NONE) {
            return;
        }
        List<String> guarded = new ArrayList<>();
        for (ToolDescriptor descriptor : discovered) {
            String rule = accessRuleOn(descriptor.method());
            if (rule != null) {
                guarded.add(descriptor.name() + " (" + rule + ")");
            }
        }
        if (!guarded.isEmpty()) {
            log.warn("agent-bridge identity mode is NONE, but these tools carry access rules that "
                    + "no caller will satisfy: {}. Every call to them will be denied. Set "
                    + "agent-bridge.security.mode to DELEGATED (or ASSERTED for a local demo) to "
                    + "have the gateway establish a caller first.", String.join(", ", guarded));
        }
    }

    private static final List<String> ACCESS_RULE_ANNOTATIONS = List.of(
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed",
            "jakarta.annotation.security.DenyAll");

    private static String accessRuleOn(java.lang.reflect.Method method) {
        if (method == null) {
            return null;
        }
        for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
            String type = annotation.annotationType().getName();
            if (ACCESS_RULE_ANNOTATIONS.contains(type)) {
                return type.substring(type.lastIndexOf('.') + 1);
            }
        }
        return null;
    }

    /**
     * Starts the stdio transport. The thread is non-daemon on purpose: for a
     * non-web application, this is what keeps the JVM alive while the client talks.
     */
    @Bean
    public ApplicationRunner agentBridgeStdioRunner(McpServer server, AgentBridgeProperties properties) {
        return args -> {
            if (properties.getTransport() != AgentBridgeProperties.Transport.STDIO) {
                log.info("agent-bridge transport is {}; no server started.", properties.getTransport());
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    server.serve(System.in, System.out);
                } catch (IOException e) {
                    log.error("MCP stdio transport stopped", e);
                }
            }, "agent-bridge-mcp-stdio");
            thread.setDaemon(false);
            thread.start();
            log.info("agent-bridge listening on stdio as '{}'", properties.getServerName());
        };
    }

    private static boolean isIncluded(Class<?> type, AgentBridgeProperties properties) {
        List<String> packages = properties.getIncludePackages();
        if (packages == null || packages.isEmpty()) {
            return true;
        }
        String name = type.getName();
        for (String prefix : packages) {
            if (prefix != null && !prefix.isBlank() && name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
