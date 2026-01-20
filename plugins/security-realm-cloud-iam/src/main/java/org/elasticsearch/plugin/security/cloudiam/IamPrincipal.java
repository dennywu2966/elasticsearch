/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License v 1".
 */
package org.elasticsearch.plugin.security.cloudiam;

import java.util.Objects;

public final class IamPrincipal {
    public enum PrincipalType {
        USER,
        ROLE,
        ASSUMED_ROLE,
        UNKNOWN
    }

    private final String arn;
    private final String accountId;
    private final String userId;
    private final PrincipalType principalType;

    public IamPrincipal(String arn, String accountId, String userId, PrincipalType principalType) {
        this.arn = Objects.requireNonNull(arn);
        this.accountId = Objects.requireNonNull(accountId);
        this.userId = userId;
        this.principalType = Objects.requireNonNull(principalType);
    }

    public String arn() {
        return arn;
    }

    public String accountId() {
        return accountId;
    }

    public String userId() {
        return userId;
    }

    public PrincipalType principalType() {
        return principalType;
    }

    public static PrincipalType principalTypeFromArn(String arn) {
        if (arn == null) {
            return PrincipalType.UNKNOWN;
        }
        int idx = arn.indexOf(':');
        if (idx < 0) {
            return PrincipalType.UNKNOWN;
        }
        String[] parts = arn.split(":", 6);
        if (parts.length < 6) {
            return PrincipalType.UNKNOWN;
        }
        String resource = parts[5];
        if (resource.startsWith("user/")) {
            return PrincipalType.USER;
        }
        if (resource.startsWith("role/")) {
            return PrincipalType.ROLE;
        }
        if (resource.startsWith("assumed-role/")) {
            return PrincipalType.ASSUMED_ROLE;
        }
        return PrincipalType.UNKNOWN;
    }
}
