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

import io.github.tujincode.agentbridge.core.ToolCallExchange;
import io.github.tujincode.agentbridge.core.ToolCallInterceptor;
import io.github.tujincode.agentbridge.core.ToolDescriptor;
import io.github.tujincode.agentbridge.core.ToolInvocationContext;
import io.github.tujincode.agentbridge.core.ToolInvocationException;
import io.github.tujincode.agentbridge.spring.AgentBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a caller token into a Spring {@code Authentication} for the duration of one call.
 *
 * <p>This is the piece that makes the project's central claim true rather than aspirational:
 * once a caller exists, {@code @PreAuthorize} on the business method evaluates against that
 * caller, so an agent is subject to exactly the rules a human already is.
 *
 * <p>Three decisions worth stating:
 *
 * <ol>
 *   <li><strong>A fresh context per call, restored afterwards.</strong> The interceptor does
 *       not mutate whatever authentication the thread happened to carry; it installs a new
 *       one and puts the old one back in a {@code finally}. An agent call must never inherit
 *       ambient authority from the runtime that hosts it.</li>
 *   <li><strong>Identity is established on the thread that runs the method.</strong> That is
 *       why this works when a call is handed to a pooled or virtual thread: nothing is
 *       inherited, everything is re-derived from the {@link ToolInvocationContext} that
 *       travels with the request. The alternative — {@code INHERITABLE_THREAD_LOCAL} — is
 *       rejected on purpose, because it hands the caller to threads that were never meant
 *       to see it.</li>
 *   <li><strong>The token is not kept.</strong> Credentials are left null so the secret is
 *       not retained in the authentication object after the call.</li>
 * </ol>
 */
public final class SecurityContextInterceptor implements ToolCallInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SecurityContextInterceptor.class);

    private static final String ANONYMOUS = "anonymous";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AgentBridgeProperties.Security config;
    private final JwtVerifier verifier;

    public SecurityContextInterceptor(AgentBridgeProperties.Security config) {
        this.config = config;
        this.verifier = config.getMode() == AgentBridgeProperties.Mode.DELEGATED
                ? new JwtVerifier(config.getSharedSecret(), config.getIssuer(),
                        Duration.ofSeconds(config.getClockSkewSeconds()), Clock.systemUTC())
                : null;
    }

    @Override
    public Object invoke(ToolCallExchange exchange, ToolInvocation invocation) {
        if (config.getMode() == AgentBridgeProperties.Mode.NONE) {
            return invocation.proceed();
        }

        ToolInvocationContext context = exchange.incoming();
        Authentication authentication = resolve(context, exchange.descriptor());
        if (authentication == null) {
            return invocation.proceed();
        }

        // Tell the rest of the call who this actually is. Without this the business method
        // and the audit record would keep the name the client claimed, which is exactly the
        // confusion this class exists to remove.
        if (config.getMode() == AgentBridgeProperties.Mode.DELEGATED) {
            exchange.verifiedAs(authentication.getName());
        } else {
            exchange.assertedAs(authentication.getName());
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext scoped = SecurityContextHolder.createEmptyContext();
            scoped.setAuthentication(authentication);
            SecurityContextHolder.setContext(scoped);
            if (log.isDebugEnabled()) {
                log.debug("tool '{}' invoked as '{}' with authorities {}",
                        exchange.descriptor().name(), authentication.getName(), authorityNames(authentication));
            }
            return invocation.proceed();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private Authentication resolve(ToolInvocationContext context, ToolDescriptor descriptor) {
        return switch (config.getMode()) {
            case NONE -> null;
            case ASSERTED -> asserted(context);
            case DELEGATED -> delegated(context, descriptor);
        };
    }

    /**
     * Trusts what the client says. Named plainly because the name is the warning.
     */
    private Authentication asserted(ToolInvocationContext context) {
        String callerId = context.callerId();
        if (callerId == null || callerId.isBlank() || ANONYMOUS.equals(callerId)) {
            return null;
        }
        Set<String> claimed = split(context.attribute(ToolInvocationContext.ATTR_ASSERTED_AUTHORITIES));
        return new UsernamePasswordAuthenticationToken(callerId, null, toAuthorities(claimed));
    }

    private Authentication delegated(ToolInvocationContext context, ToolDescriptor descriptor) {
        String token = firstNonBlank(context.authorization(), config.getStaticToken());
        if (token == null) {
            if (!config.isRequireToken()) {
                return null;
            }
            throw new ToolInvocationException(
                    "no caller token. This gateway runs in DELEGATED mode, so every call must present "
                            + "a bearer token in _meta.authorization, or the process must be started with "
                            + "agent-bridge.security.static-token set.");
        }

        JwtVerifier.VerifiedToken verified;
        try {
            verified = verifier.verify(stripBearer(token));
        } catch (JwtVerifier.InvalidTokenException e) {
            // The model reads this string, so it has to say what to fix, not just "denied".
            throw new ToolInvocationException("caller token rejected: " + e.getMessage()
                    + ". Re-authenticate and retry; do not retry with the same token.");
        }

        requireDeclaredScopes(descriptor, verified.scopes());

        return new UsernamePasswordAuthenticationToken(
                verified.subject(), null, toAuthorities(verified.scopes()));
    }

    /**
     * Makes {@code @AgentTool(scopes = ...)} load-bearing instead of decorative.
     *
     * <p>Without this the attribute is a comment: nothing reads it. With it, a tool states
     * the scope it needs and the gateway refuses a token that lacks it — even when the
     * business method carries no {@code @PreAuthorize}, which is the common case when a
     * team exposes a read-only helper.
     */
    private static void requireDeclaredScopes(ToolDescriptor descriptor, Set<String> granted) {
        List<String> required = descriptor.scopes();
        if (required.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String scope : required) {
            if (!granted.contains(scope)) {
                missing.add(scope);
            }
        }
        if (!missing.isEmpty()) {
            throw new ToolInvocationException("tool '" + descriptor.name() + "' requires scope "
                    + missing + " which the presented token does not carry (it has " + granted + ")");
        }
    }

    private static List<GrantedAuthority> toAuthorities(Collection<String> names) {
        List<GrantedAuthority> authorities = new ArrayList<>(names.size());
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(name));
            }
        }
        return authorities;
    }

    private static Set<String> split(String value) {
        Set<String> parts = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return parts;
        }
        for (String part : value.trim().split("[,\\s]+")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String stripBearer(String token) {
        String trimmed = token.trim();
        return trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                ? trimmed.substring(BEARER_PREFIX.length()).trim()
                : trimmed;
    }

    private static List<String> authorityNames(Authentication authentication) {
        List<String> names = new ArrayList<>();
        authentication.getAuthorities().forEach(a -> names.add(a.getAuthority()));
        return names;
    }
}
