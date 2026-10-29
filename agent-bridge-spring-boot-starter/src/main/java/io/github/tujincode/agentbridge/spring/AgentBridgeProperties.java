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

    /** How a tool call acquires an authenticated caller. */
    private Security security = new Security();

    /**
     * What the gateway does about the identity of whoever triggered a call.
     *
     * <p>The default is {@link Mode#NONE}: the library adds no security behaviour unless
     * asked, and a tool call runs with whatever the current thread already carries. That
     * is the safe default for an upgrade, not a safe default for production — hence the
     * startup warning described on {@link Mode#NONE}.
     */
    public enum Mode {
        /**
         * No {@code Authentication} is created.
         *
         * <p>If the application enables method security while the gateway is in this mode,
         * every tool that carries an access rule will be denied, and the denial will look
         * like an authorisation bug in the business code. The starter logs a warning naming
         * those tools at startup so the cause is obvious.
         */
        NONE,

        /**
         * The caller id and authorities sent by the client are trusted as-is.
         *
         * <p>This is not authentication, it is a placeholder with a nicer name. Any client
         * can send any name. Use it for a laptop demo, never for anything reachable.
         */
        ASSERTED,

        /**
         * A signed JWT is verified, and its subject becomes the authenticated caller.
         *
         * <p>This is the only mode that can answer "who did this" to an auditor.
         */
        DELEGATED
    }

    /** Identity configuration. */
    public static class Security {

        private Mode mode = Mode.NONE;

        /**
         * HMAC secret used to verify delegated tokens. Required when mode is
         * {@code DELEGATED}; startup fails without it rather than silently downgrading.
         */
        private String sharedSecret;

        /** Expected {@code iss} claim. Optional, but recommended. */
        private String issuer;

        /**
         * Token used for every call when the request does not carry one, typically bound
         * to an environment variable: {@code ${AGENT_BRIDGE_TOKEN:}}.
         *
         * <p>Suited to stdio, where one process serves one client for its lifetime. It is
         * the wrong mechanism the moment two callers share a process.
         */
        private String staticToken;

        /** Whether {@code DELEGATED} refuses calls that present no token. Default true. */
        private boolean requireToken = true;

        /** Tolerance applied to {@code exp} and {@code nbf}, in seconds. */
        private long clockSkewSeconds = 60;

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode == null ? Mode.NONE : mode;
        }

        public String getSharedSecret() {
            return sharedSecret;
        }

        public void setSharedSecret(String sharedSecret) {
            this.sharedSecret = sharedSecret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getStaticToken() {
            return staticToken;
        }

        public void setStaticToken(String staticToken) {
            this.staticToken = staticToken;
        }

        public boolean isRequireToken() {
            return requireToken;
        }

        public void setRequireToken(boolean requireToken) {
            this.requireToken = requireToken;
        }

        public long getClockSkewSeconds() {
            return clockSkewSeconds;
        }

        public void setClockSkewSeconds(long clockSkewSeconds) {
            this.clockSkewSeconds = clockSkewSeconds;
        }
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security == null ? new Security() : security;
    }

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
