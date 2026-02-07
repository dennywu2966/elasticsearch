/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License v 1".
 */
package org.elasticsearch.plugin.security.cloudiam;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.security.authc.RealmConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Validates Aliyun OAuth 2.1 access tokens by calling the userinfo endpoint.
 * Extracts user identity and constructs an ARN-based principal for authentication.
 */
public class OAuthTokenValidator implements IamClient {
    private static final String OAUTH_USERINFO_ENDPOINT = "https://oauth.aliyun.com/v1/userinfo";
    private static final String DEFAULT_ACCOUNT_ID = "unknown";

    private final String userinfoEndpoint;
    private final TimeValue readTimeout;
    private final HttpClient httpClient;

    public OAuthTokenValidator(RealmConfig config) {
        String endpointSetting = config.getSetting(CloudIamRealmSettings.IAM_ENDPOINT, () -> "");
        this.userinfoEndpoint = Strings.hasText(endpointSetting) ? normalizeEndpoint(endpointSetting) : OAUTH_USERINFO_ENDPOINT;
        this.readTimeout = config.getSetting(CloudIamRealmSettings.IAM_READ_TIMEOUT);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.getSetting(CloudIamRealmSettings.IAM_CONNECT_TIMEOUT).getMillis()))
            .build();
    }

    @Override
    public void verify(CloudIamToken token, ActionListener<IamPrincipal> listener) {
        String accessToken = token.oauthToken();
        if (Strings.hasText(accessToken) == false) {
            listener.onFailure(new IllegalArgumentException("missing OAuth access token"));
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(userinfoEndpoint))
                .timeout(Duration.ofMillis(readTimeout.getMillis()))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                listener.onFailure(new IllegalStateException("OAuth token validation failed with status " + response.statusCode()));
                return;
            }

            listener.onResponse(parseUserInfo(response.body()));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Parses Aliyun OAuth userinfo response and extracts principal information.
     *
     * Expected response format for users:
     * {
     *   "sub": "1437310945246567:289518327600482862",
     *   "aid": "1437310945246567",
     *   "uid": "289518327600482862",
     *   "upn": "dongdongplanet",
     *   "name": "Dong Dong",
     *   "type": "user"
     * }
     *
     * Expected response format for roles:
     * {
     *   "sub": "1437310945246567:role/myrole",
     *   "aid": "1437310945246567",
     *   "type": "role"
     * }
     */
    private IamPrincipal parseUserInfo(String responseBody) throws IOException {
        Tuple<XContentType, Map<String, Object>> parsed = XContentHelper.convertToMap(
            new BytesArray(responseBody),
            false,
            XContentType.JSON
        );
        Map<String, Object> userInfo = parsed.v2();

        // Extract account ID (aid) or fallback to sub prefix
        String accountId = stringValue(userInfo.get("aid"));
        if (Strings.hasText(accountId) == false) {
            String sub = stringValue(userInfo.get("sub"));
            if (Strings.hasText(sub) && sub.contains(":")) {
                accountId = sub.split(":", 2)[0];
            } else {
                accountId = DEFAULT_ACCOUNT_ID;
            }
        }

        // Extract identity type: "user", "role", or "account"
        String type = stringValue(userInfo.get("type"));
        IamPrincipal.PrincipalType principalType;

        if ("role".equalsIgnoreCase(type)) {
            principalType = IamPrincipal.PrincipalType.ROLE;
        } else if ("user".equalsIgnoreCase(type)) {
            principalType = IamPrincipal.PrincipalType.USER;
        } else if ("account".equalsIgnoreCase(type)) {
            // Root account - treat as user
            principalType = IamPrincipal.PrincipalType.USER;
        } else {
            // Default to user if type is not specified
            principalType = IamPrincipal.PrincipalType.USER;
        }

        // Build ARN based on principal type
        String arn;
        String userId = stringValue(userInfo.get("uid"));

        if (principalType == IamPrincipal.PrincipalType.ROLE) {
            // For roles: extract role name from sub or construct with type
            String sub = stringValue(userInfo.get("sub"));
            String roleName = null;

            if (Strings.hasText(sub) && sub.contains("role/")) {
                // Format: "1437310945246567:role/myrole"
                roleName = sub.substring(sub.indexOf("role/") + 5);
            } else if (Strings.hasText(sub) && sub.contains(":")) {
                roleName = sub.split(":", 2)[1];
            }

            if (Strings.hasText(roleName) == false) {
                throw new IllegalStateException("missing role identifier in OAuth userinfo response");
            }

            // Build role ARN: acs:ram::{accountId}:role/{roleName}
            arn = "acs:ram::" + accountId + ":role/" + roleName;
        } else {
            // For users/accounts: extract user principal name (upn) or fallback to sub
            String userName = stringValue(userInfo.get("upn"));
            if (Strings.hasText(userName) == false) {
                String sub = stringValue(userInfo.get("sub"));
                if (Strings.hasText(sub) && sub.contains(":")) {
                    userName = sub.split(":", 2)[1];
                } else {
                    userName = stringValue(userInfo.get("sub"));
                }
            }

            if (Strings.hasText(userName) == false) {
                throw new IllegalStateException("missing user identifier in OAuth userinfo response");
            }

            // Build user ARN: acs:ram::{accountId}:user/{userName}
            arn = "acs:ram::" + accountId + ":user/" + userName;
        }

        return new IamPrincipal(arn, accountId, userId, principalType);
    }

    private static String normalizeEndpoint(String endpoint) {
        String trimmed = endpoint.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String normalized = trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : "https://" + trimmed;
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
