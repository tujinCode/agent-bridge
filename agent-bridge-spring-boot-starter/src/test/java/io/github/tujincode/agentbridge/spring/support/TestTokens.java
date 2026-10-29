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
package io.github.tujincode.agentbridge.spring.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints HS256 tokens for tests.
 *
 * <p>Written from the primitives rather than by calling {@code JwtVerifier}, for the obvious
 * reason: a bug shared by the signer and the verifier cancels out and the tests go green
 * over broken code. Here the two implementations agree only where the format does.
 */
public final class TestTokens {

    private TestTokens() {
    }

    /** A token with the claims the gateway needs, plus a space-delimited scope. */
    public static String token(String secret, String subject, String scopes, Instant expiresAt) {
        String payload = "{\"sub\":\"" + escape(subject) + "\""
                + ",\"scope\":\"" + escape(scopes) + "\""
                + ",\"iss\":\"agent-bridge-test\""
                + ",\"exp\":" + expiresAt.getEpochSecond() + "}";
        return sign(secret, "{\"alg\":\"HS256\",\"typ\":\"JWT\"}", payload);
    }

    /** A token that expires 5 minutes from now. */
    public static String freshToken(String secret, String subject, String scopes) {
        return token(secret, subject, scopes, Instant.now().plusSeconds(300));
    }

    /** Signs arbitrary header and payload JSON; used to forge the cases that must fail. */
    public static String sign(String secret, String headerJson, String payloadJson) {
        String signingInput = base64Url(headerJson.getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(hmac(secret, signingInput));
    }

    public static String payload(String issuer, String subject, String scopes, Long expiresAt) {
        StringBuilder json = new StringBuilder("{");
        if (issuer != null) {
            json.append("\"iss\":\"").append(escape(issuer)).append("\",");
        }
        if (subject != null) {
            json.append("\"sub\":\"").append(escape(subject)).append("\",");
        }
        if (scopes != null) {
            json.append("\"scope\":\"").append(escape(scopes)).append("\",");
        }
        if (expiresAt != null) {
            json.append("\"exp\":").append(expiresAt).append(",");
        }
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    private static byte[] hmac(String secret, String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
