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

import io.github.tujincode.agentbridge.spring.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtVerifierTest {

    private static final String SECRET = "a-test-secret-long-enough-to-be-uninteresting";
    private static final String ISSUER = "agent-bridge-test";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final JwtVerifier verifier = new JwtVerifier(
            SECRET, ISSUER, Duration.ofSeconds(30), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("a well-formed token yields its subject and scopes")
    void acceptsValidToken() {
        String token = TestTokens.sign(SECRET,
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                TestTokens.payload(ISSUER, "zhang.cong", "workorder:read workorder:write",
                        NOW.plusSeconds(600).getEpochSecond()));

        JwtVerifier.VerifiedToken verified = verifier.verify(token);

        assertThat(verified.subject()).isEqualTo("zhang.cong");
        assertThat(verified.scopes()).containsExactly("workorder:read", "workorder:write");
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpiredToken() {
        String token = TestTokens.sign(SECRET, "{\"alg\":\"HS256\"}",
                TestTokens.payload(ISSUER, "zhang.cong", "demo:write",
                        NOW.minusSeconds(120).getEpochSecond()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("a token inside the clock skew is still accepted")
    void toleratesClockSkew() {
        // Expired 10 seconds ago, skew allows 30.
        String token = TestTokens.sign(SECRET, "{\"alg\":\"HS256\"}",
                TestTokens.payload(ISSUER, "zhang.cong", "demo:write",
                        NOW.minusSeconds(10).getEpochSecond()));

        assertThat(verifier.verify(token).subject()).isEqualTo("zhang.cong");
    }

    @Test
    @DisplayName("a token that never expires is rejected")
    void rejectsTokenWithoutExpiry() {
        String token = TestTokens.sign(SECRET, "{\"alg\":\"HS256\"}",
                TestTokens.payload(ISSUER, "zhang.cong", "demo:write", null));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class)
                .hasMessageContaining("exp");
    }

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void rejectsWrongSecret() {
        String token = TestTokens.sign("the-attackers-secret-wrapped-in-a-long-string",
                "{\"alg\":\"HS256\"}",
                TestTokens.payload(ISSUER, "zhang.cong", "demo:write",
                        NOW.plusSeconds(600).getEpochSecond()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("a payload edited after signing is rejected")
    void rejectsTamperedPayload() {
        String valid = TestTokens.sign(SECRET, "{\"alg\":\"HS256\"}",
                TestTokens.payload(ISSUER, "intern", "demo:read",
                        NOW.plusSeconds(600).getEpochSecond()));

        String[] parts = valid.split("\\.");
        String escalated = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                TestTokens.payload(ISSUER, "intern", "demo:read demo:write",
                        NOW.plusSeconds(600).getEpochSecond()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = parts[0] + "." + escalated + "." + parts[2];

        assertThatThrownBy(() -> verifier.verify(forged))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("an unexpected algorithm is rejected without consulting the signature")
    void rejectsUnexpectedAlgorithm() {
        String token = TestTokens.sign(SECRET, "{\"alg\":\"RS256\"}",
                TestTokens.payload(ISSUER, "zhang.cong", "demo:write",
                        NOW.plusSeconds(600).getEpochSecond()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class)
                .hasMessageContaining("HS256");
    }

    @Test
    @DisplayName("alg none is rejected")
    void rejectsAlgNone() {
        String token = TestTokens.sign(SECRET, "{\"alg\":\"none\"}",
                TestTokens.payload(ISSUER, "zhang.cong", "demo:write",
                        NOW.plusSeconds(600).getEpochSecond()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class)
                .hasMessageContaining("HS256");
    }

    @Test
    @DisplayName("a token from another issuer is rejected")
    void rejectsForeignIssuer() {
        String token = TestTokens.sign(SECRET, "{\"alg\":\"HS256\"}",
                TestTokens.payload("some-other-idp", "zhang.cong", "demo:write",
                        NOW.plusSeconds(600).getEpochSecond()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    @DisplayName("a token without a subject is rejected")
    void rejectsMissingSubject() {
        String token = TestTokens.sign(SECRET, "{\"alg\":\"HS256\"}",
                TestTokens.payload(ISSUER, null, "demo:write",
                        NOW.plusSeconds(600).getEpochSecond()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class)
                .hasMessageContaining("subject");
    }

    @Test
    @DisplayName("something that is not a token at all is rejected")
    void rejectsGarbage() {
        assertThatThrownBy(() -> verifier.verify("not-a-token"))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class);
        assertThatThrownBy(() -> verifier.verify(""))
                .isInstanceOf(JwtVerifier.InvalidTokenException.class);
    }
}
