package io.github.agentbridge.spring;

import io.github.agentbridge.core.ToolCallListener;
import io.github.agentbridge.core.ToolDescriptor;
import io.github.agentbridge.core.ToolRegistry;
import io.github.agentbridge.core.ToolScanner;
import io.github.agentbridge.core.mcp.McpServer;
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
        };
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
