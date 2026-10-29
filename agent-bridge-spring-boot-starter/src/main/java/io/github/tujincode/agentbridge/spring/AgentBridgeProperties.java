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

import io.github.tujincode.agentbridge.annotation.RiskLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the starter, bound from the {@code agent-bridge.*} namespace.
 */
@ConfigurationProperties(prefix = "agent-bridge")
public class AgentBridgeProperties {

    /** Master switch. Disable in tests to keep the stdio server out of the way. */
    private boolean enabled = true;

    /** Name reported to the MCP client during the handshake. */
    private String serverName = "agent-bridge";

    /** Version reported to the MCP client during the handshake. */
    private String serverVersion = "0.1.0";

    /** How tools are exposed. {@code NONE} keeps the registry but starts no server. */
    private Transport transport = Transport.STDIO;

    /**
     * Highest risk level executed without human confirmation.
     *
     * <p>Defaults to {@link RiskLevel#READ}: out of the box the agent can look but not
     * touch. Raise it only after the audit trail is wired up.
     */
    private RiskLevel maxAutoRisk = RiskLevel.READ;

    /** Optional package prefixes to scan. Empty means "every bean". */
    private List<String> includePackages = new ArrayList<>();

    public enum Transport {
        /** Newline-delimited JSON-RPC over stdin/stdout. */
        STDIO,
        /** No transport started; useful for tests and for embedding the registry. */
        NONE
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public Transport getTransport() {
        return transport;
    }

    public void setTransport(Transport transport) {
        this.transport = transport;
    }

    public RiskLevel getMaxAutoRisk() {
        return maxAutoRisk;
    }

    public void setMaxAutoRisk(RiskLevel maxAutoRisk) {
        this.maxAutoRisk = maxAutoRisk;
    }

    public List<String> getIncludePackages() {
        return includePackages;
    }

    public void setIncludePackages(List<String> includePackages) {
        this.includePackages = includePackages;
    }
}
