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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Verifies HS256 JSON Web Tokens. Deliberately narrow.
 *
 * <p>This is not a general-purpose JWT library and should not become one. It accepts
 * exactly the shape this project issues: HS256, an {@code exp} claim, a {@code sub} claim,
 * and scopes in either {@code scope} (space-delimited string) or {@code scp} (array). Every
 * other algorithm is rejected by name, including {@code none} — the ability to switch
 * algorithms via a request-controlled header is the oldest JWT failure there is, and the
 * only reliable defence is to never look at the header's opinion beyond confirming it says
 * what this class already decided to accept.
 *
 * <p>Why not pull in a library: the project ships a starter that should add no transitive
 * dependencies an adopter has to audit. Sixty lines of HMAC that pass tests are easier to
 * justify than an unfamiliar dependency graph in a library whose selling point is that it
 * sits next to your authorisation code.
 */
public final class JwtVerifier {

    /** What a verified token says about its bearer. */
    public record VerifiedToken(String subject, Set<String> scopes, Map<String, String> claims) {
    }

    /** Raised when a token is malformed, unsigned, wrongly signed, or expired. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    private static final String ALGORITHM = "HmacSHA256";
    private static final int SEGMENTS = 3;

    private final byte[] secret;
    private final String expectedIssuer;
    private final Duration clockSkew;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtVerifier(String sharedSecret, String expectedIssuer, Duration clockSkew, Clock clock) {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "agent-bridge.security.shared-secret is required in DELEGATED mode. "
                            + "Refusing to start with no way to verify a token.");
        }
        this.secret = sharedSecret.getBytes(StandardCharsets.UTF_8);
        this.expectedIssuer = expectedIssuer;
        this.clockSkew = clockSkew == null ? Duration.ZERO : clockSkew;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public VerifiedToken verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("no token presented");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != SEGMENTS) {
            throw new InvalidTokenException(
                    "expected 3 dot-separated segments, found " + parts.length + "; is this a JWT?");
        }

        JsonNode header = decodeSegment(parts[0], "header");
        String alg = header.path("alg").asText("");
        if (!"HS256".equals(alg)) {
            throw new InvalidTokenException(
                    "algorithm '" + alg + "' is not accepted; this deployment verifies HS256 only");
        }
        verifySignature(parts[0] + "." + parts[1], parts[2]);

        JsonNode payload = decodeSegment(parts[1], "payload");
        verifyValidityWindow(payload);
        verifyIssuer(payload);

        String subject = payload.path("sub").asText("");
        if (subject.isBlank()) {
            throw new InvalidTokenException("token has no subject; an unidentified caller cannot be audited");
        }
        return new VerifiedToken(subject, readScopes(payload), readScalarClaims(payload));
    }

    private void verifySignature(String signingInput, String encodedSignature) {
        byte[] presented = base64UrlDecode(encodedSignature, "signature");
        byte[] expected = hmac(signingInput);
        // Constant-time: a length-equal comparison that exits early leaks the prefix.
        if (!MessageDigest.isEqual(expected, presented)) {
            throw new InvalidTokenException("signature does not match");
        }
    }

    private void verifyValidityWindow(JsonNode payload) {
        Instant now = clock.instant();

        long expiresAt = payload.path("exp").asLong(0L);
        if (expiresAt <= 0L) {
            throw new InvalidTokenException(
                    "token has no exp claim; a credential that never expires is not accepted");
        }
        if (now.minus(clockSkew).isAfter(Instant.ofEpochSecond(expiresAt))) {
            throw new InvalidTokenException("token expired at " + Instant.ofEpochSecond(expiresAt));
        }

        long notBefore = payload.path("nbf").asLong(0L);
        if (notBefore > 0L && now.plus(clockSkew).isBefore(Instant.ofEpochSecond(notBefore))) {
            throw new InvalidTokenException("token is not valid before " + Instant.ofEpochSecond(notBefore));
        }
    }

    private void verifyIssuer(JsonNode payload) {
        if (expectedIssuer == null || expectedIssuer.isBlank()) {
            return;
        }
        String actual = payload.path("iss").asText("");
        if (!expectedIssuer.equals(actual)) {
            throw new InvalidTokenException(
                    "issuer '" + actual + "' is not the expected '" + expectedIssuer + "'");
        }
    }

    private static Set<String> readScopes(JsonNode payload) {
        Set<String> scopes = new LinkedHashSet<>();
        String spaceDelimited = payload.path("scope").asText("");
        if (!spaceDelimited.isBlank()) {
            Collections.addAll(scopes, spaceDelimited.trim().split("\\s+"));
        }
        JsonNode array = payload.path("scp");
        if (array.isArray()) {
            array.forEach(node -> scopes.add(node.asText()));
        }
        return scopes;
    }

    private static Map<String, String> readScalarClaims(JsonNode payload) {
        Map<String, String> claims = new LinkedHashMap<>();
        payload.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isValueNode() && !value.isNull()) {
                claims.put(entry.getKey(), value.asText());
            }
        });
        return claims;
    }

    private byte[] hmac(String signingInput) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM; this is a bug", e);
        }
    }

    private JsonNode decodeSegment(String segment, String what) {
        byte[] decoded = base64UrlDecode(segment, what);
        try {
            JsonNode node = mapper.readTree(decoded);
            if (node == null || !node.isObject()) {
                throw new InvalidTokenException("token " + what + " is not a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw new InvalidTokenException("token " + what + " is not valid JSON");
        }
    }

    private static byte[] base64UrlDecode(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new InvalidTokenException("token " + what + " is empty");
        }
        int padding = (4 - value.length() % 4) % 4;
        try {
            return Base64.getUrlDecoder().decode(value + "=".repeat(padding));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("token " + what + " is not valid base64url");
        }
    }
}
