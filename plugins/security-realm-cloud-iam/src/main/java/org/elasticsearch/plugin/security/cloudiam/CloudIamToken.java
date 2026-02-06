/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License v 1".
 */
package org.elasticsearch.plugin.security.cloudiam;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.security.authc.AuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class CloudIamToken implements AuthenticationToken {
    private static final String PARAM_ACTION = "Action";
    private static final String PARAM_VERSION = "Version";
    private static final String PARAM_ACCESS_KEY_ID = "AccessKeyId";
    private static final String PARAM_SIGNATURE = "Signature";
    private static final String PARAM_SIGNATURE_METHOD = "SignatureMethod";
    private static final String PARAM_SIGNATURE_VERSION = "SignatureVersion";
    private static final String PARAM_SIGNATURE_NONCE = "SignatureNonce";
    private static final String PARAM_TIMESTAMP = "Timestamp";
    private static final String PARAM_SECURITY_TOKEN = "SecurityToken";

    // STS signature fields
    private final String accessKeyId;
    private final Instant timestamp;
    private final String nonce;
    private String signature;
    private final String sessionToken;
    private final Map<String, String> signedParams;

    // OAuth bearer token field
    private final String oauthToken;

    private final boolean valid;
    private final String validationError;

    private CloudIamToken(
        String accessKeyId,
        Instant timestamp,
        String nonce,
        String signature,
        String sessionToken,
        Map<String, String> signedParams,
        String oauthToken,
        boolean valid,
        String validationError
    ) {
        this.accessKeyId = accessKeyId;
        this.timestamp = timestamp;
        this.nonce = nonce;
        this.signature = signature;
        this.sessionToken = sessionToken;
        this.signedParams = signedParams;
        this.oauthToken = oauthToken;
        this.valid = valid;
        this.validationError = validationError;
    }

    public static CloudIamToken fromHeaders(String signedHeader, int signedHeaderMaxBytes) {
        return fromHeaders(signedHeader, null, signedHeaderMaxBytes);
    }

    /**
     * Creates a CloudIamToken from either STS signature header or OAuth Bearer token.
     *
     * If both headers are present, OAuth Bearer token takes precedence (modern auth method).
     * This is logged as a warning for debugging purposes.
     *
     * @param signedHeader The X-ES-IAM-Signed header value (STS signature)
     * @param authorizationHeader The Authorization header value (OAuth Bearer token)
     * @param signedHeaderMaxBytes Maximum size for signed header
     * @return A CloudIamToken instance
     */
    public static CloudIamToken fromHeaders(String signedHeader, String authorizationHeader, int signedHeaderMaxBytes) {
        // Check if both headers are present (unusual case)
        boolean hasOAuth = Strings.hasText(authorizationHeader);
        boolean hasSTS = Strings.hasText(signedHeader);

        // Prefer OAuth Bearer token (modern auth method)
        if (hasOAuth) {
            String token = authorizationHeader;
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            if (Strings.hasText(token)) {
                // Log warning if both headers present (OAuth takes precedence)
                if (hasSTS) {
                    // Note: In production, this should use proper logger
                    System.err.println("[CloudIamToken] Both Authorization and X-ES-IAM-Signed headers present. Using OAuth token.");
                }
                System.err.println("[CloudIamToken] Creating OAuth token: " + token.substring(0, Math.min(20, token.length())) + "...");
                return new CloudIamToken(null, null, null, null, null, null, token, true, null);
            }
        }

        // Fall back to STS signature
        if (signedHeader == null || signedHeader.isBlank()) {
            System.err.println("[CloudIamToken] Missing authentication: signedHeader=" + (signedHeader == null ? "null" : "empty") + ", authorizationHeader=" + (authorizationHeader == null ? "null" : "empty"));
            return invalid("missing authentication: both signed header and bearer token are absent");
        }
        if (signedHeader.length() > signedHeaderMaxBytes * 2L) {
            return invalid("signed header too large");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(signedHeader);
        } catch (IllegalArgumentException e) {
            return invalid("invalid signed header");
        }
        if (decoded.length > signedHeaderMaxBytes) {
            return invalid("signed header too large");
        }
        Map<String, String> params;
        try {
            params = parseSignedParams(decoded);
        } catch (Exception e) {
            return invalid("invalid signed json");
        }
        String accessKeyId = params.get(PARAM_ACCESS_KEY_ID);
        String timestampRaw = params.get(PARAM_TIMESTAMP);
        String nonce = params.get(PARAM_SIGNATURE_NONCE);
        String signature = params.get(PARAM_SIGNATURE);
        String sessionToken = params.get(PARAM_SECURITY_TOKEN);
        if (accessKeyId == null || timestampRaw == null || nonce == null || signature == null) {
            return invalid("missing required signed fields");
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampRaw);
        } catch (DateTimeParseException e) {
            return invalid("invalid timestamp");
        }
        return new CloudIamToken(accessKeyId, timestamp, nonce, signature, sessionToken, params, null, true, null);
    }

    private static CloudIamToken invalid(String reason) {
        return new CloudIamToken(null, null, null, null, null, null, null, false, reason);
    }

    private static Map<String, String> parseSignedParams(byte[] decodedJson) throws Exception {
        Tuple<XContentType, Map<String, Object>> parsed = XContentHelper.convertToMap(
            new BytesArray(decodedJson),
            false,
            XContentType.JSON
        );
        Map<String, Object> raw = parsed.v2();
        Map<String, String> params = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (entry.getValue() instanceof String value) {
                params.put(entry.getKey(), value);
            } else {
                params.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return params;
    }

    public boolean isValid() {
        return valid;
    }

    public String validationError() {
        return validationError;
    }

    public String accessKeyId() {
        return accessKeyId;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public String nonce() {
        return nonce;
    }

    public String signature() {
        return signature;
    }

    public String sessionToken() {
        return sessionToken;
    }

    public Map<String, String> signedParams() {
        return signedParams;
    }

    /**
     * Returns the OAuth access token if this is an OAuth-based token.
     * @return The OAuth access token or null if this is an STS token
     */
    public String oauthToken() {
        return oauthToken;
    }

    /**
     * Checks if this token is an OAuth bearer token.
     * @return true if this token uses OAuth authentication
     */
    public boolean isOAuthToken() {
        return oauthToken != null;
    }

    @Override
    public String principal() {
        if (isOAuthToken()) {
            return oauthToken;
        }
        return accessKeyId == null ? "" : accessKeyId;
    }

    @Override
    public Object credentials() {
        if (isOAuthToken()) {
            return oauthToken;
        }
        return signature;
    }

    @Override
    public void clearCredentials() {
        if (isOAuthToken()) {
            // For OAuth tokens, we clear by setting the reference to null
            // Note: This is a no-op since oauthToken is final, but the interface requires this method
        } else {
            signature = null;
        }
    }
}
