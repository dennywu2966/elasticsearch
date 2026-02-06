# Cloud IAM Realm Plugin - Authorization Limitations

## Overview

This document describes the current authorization capabilities and limitations of the Cloud IAM realm plugin, which supports two authentication paths: STS Signature and OAuth Token.

## Supported Principal Types

The plugin defines four principal types in `IamPrincipal.java`:

| Principal Type | Description |
|----------------|-------------|
| `USER` | RAM user |
| `ROLE` | RAM role |
| `ASSUMED_ROLE` | Assumed role (via STS AssumeRole API) |
| `UNKNOWN` | Unknown/unsupported type |

## What is an Assumed Role?

An **assumed role** is a temporary security credential mechanism that allows a principal (user, service, or application) to temporarily "assume" a different identity with specific permissions.

### How It Works

```
┌─────────────┐                ┌──────────────┐                ┌─────────────┐
│   User/App  │ ─AssumeRole──> │ RAM Service  │ ─Returns────> │ Temp Creds  │
│             │                │              │              │             │
│ Principal A │                │              │              │  Role B     │
│ (limited)   │                │              │              │ (elevated)  │
└─────────────┘                └──────────────┘                └─────────────┘
```

1. Caller requests to assume a role using `AssumeRole` API
2. RAM service validates the request and returns **temporary credentials**
3. Caller uses these temp credentials (with a `SecurityToken`) to access resources
4. Temp credentials expire after a configured duration (typically 15min to 12 hours)

### Real-World Use Cases

#### Use Case 1: Cross-Account Access

```
┌──────────────────┐                    ┌──────────────────┐
│  Account A       │                    │  Account B       │
│  (Dev Team)      │                    │  (Production)    │
│                  │                    │                  │
│  ┌──────────┐    │   AssumeRole       │  ┌──────────┐    │
│  │ Dev User │───┼──────────────────>│  │ ProdRole │    │
│  └──────────┘    │                    │  └──────────┘    │
│                  │                    │                  │
└──────────────────┘                    └──────────────────┘
```

**Scenario:** A developer in Account A needs read-only access to Elasticsearch in Account B's production environment.

**Why use Assumed Role:**
- No need to create permanent IAM users in Account B
- Temporary access with automatic expiration
- Centralized access control and auditing

#### Use Case 2: Privilege Elevation for Admin Tasks

```
┌─────────────────────────────────────────────────────────┐
│                     Daily Operations                    │
│  ┌──────────┐    ────────────>    ┌──────────────────┐  │
│  │ Regular  │                      │ Read-only access │  │
│  │ User     │                      │ to ES indices    │  │
│  └──────────┘                      └──────────────────┘  │
└─────────────────────────────────────────────────────────┘
                          │
                          │ AssumeRole (when needed)
                          ▼
┌─────────────────────────────────────────────────────────┐
│                   Emergency Admin Task                  │
│  ┌──────────┐    ────────────>    ┌──────────────────┐  │
│  │ Regular  │                      │ Full admin access│  │
│  │ User     │                      │ (temp, 15 min)   │  │
│  └──────────┘                      └──────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**Scenario:** A data analyst normally has read-only access to Elasticsearch. Once a month, they need to reindex data (requires write permissions).

**Why use Assumed Role:**
- User doesn't need permanent write permissions
- Temporary elevation for the specific task
- Auto-revocation after timeout
- Audit trail shows exactly when elevated access was used

#### Use Case 3: Service-to-Service Authentication

```
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│   Web App    │ ──────> │   API Svc    │ ──────> │ Elasticsearch│
│              │         │              │         │              │
│  Role: App   │         │ AssumeRole   │         │  Role:       │
│  (low priv)  │         │ ───────────> │         │  ESWriter    │
└──────────────┘         └──────────────┘         └──────────────┘
```

**Scenario:** A web application calls a backend service, which then needs to write documents to Elasticsearch.

**Why use Assumed Role:**
- Each service has its own role with minimal permissions
- Services can assume roles with specific write access to ES
- Compromised web app credentials don't directly expose ES
- Credential rotation handled automatically by RAM

### ARN Format Differences

When a role is assumed, the ARN reflects this:

| Scenario | ARN Format | Principal Type |
|----------|------------|----------------|
| Direct role | `acs:ram::123456:role/DataWriter` | `ROLE` |
| Assumed role | `acs:ram::123456:assumed-role/DataWriter/session-name` | `ASSUMED_ROLE` |

The `assumed-role/` prefix indicates this is a **temporary session** created via `AssumeRole`, not the role itself.

---

## Authentication Path Comparison

### Path A: STS Signature (`AliyunStsClient`)

**Entry Point:** `X-ES-IAM-Signed` header (base64-encoded JSON)

**Verification Method:** Calls Aliyun STS `GetCallerIdentity` API

**Principal Detection:** ARN-based parsing via `IamPrincipal.principalTypeFromArn()`
- ARN format: `acs:ram::{accountId}:{resource}`
- Resource prefix determines type:
  - `user/` → `USER`
  - `role/` → `ROLE`
  - `assumed-role/` → `ASSUMED_ROLE`

**Supported Authorization Types:**
- ✅ RAM Users
- ✅ RAM Roles
- ✅ Assumed Roles (gated by `auth.allow_assumed_role` config, default: `false`)

**Configuration:** `auth.allow_assumed_role` (default: `false`)

---

### Path B: OAuth Token (`OAuthTokenValidator`)

**Entry Point:** `Authorization: Bearer <token>` header

**Verification Method:** Calls Aliyun OAuth `https://oauth.aliyun.com/v1/userinfo`

**Principal Detection:** Response field-based via `parseUserInfo()`
- Checks `type` field in userinfo response:
  - `type: "user"` → `USER`
  - `type: "role"` → `ROLE`
  - `type: "account"` → `USER` (root account treated as user)
  - Missing type → defaults to `USER`

**Supported Authorization Types:**
- ✅ RAM Users
- ✅ RAM Roles
- ❌ Assumed Roles (**NOT SUPPORTED**)

**Configuration:** No configuration to enable assumed roles (not implemented)

---

## Critical Limitation: Assumed Role Support

| Feature | STS Signature | OAuth Token |
|---------|---------------|-------------|
| RAM User | ✅ Full support | ✅ Full support |
| RAM Role | ✅ Full support | ✅ Full support |
| Assumed Role | ✅ Supported (config-gated) | ❌ **Not supported** |

### Root Cause

The `OAuthTokenValidator.parseUserInfo()` method (`OAuthTokenValidator.java:125-139`) only handles three types:

```java
if ("role".equalsIgnoreCase(type)) {
    principalType = IamPrincipal.PrincipalType.ROLE;
} else if ("user".equalsIgnoreCase(type)) {
    principalType = IamPrincipal.PrincipalType.USER;
} else if ("account".equalsIgnoreCase(type)) {
    principalType = IamPrincipal.PrincipalType.USER;
} else {
    principalType = IamPrincipal.PrincipalType.USER;  // Default
}
```

There is no handling for `type: "assumed-role"` or ARN-based fallback logic like the STS path uses.

### Impact

1. **Inconsistency:** Users authenticating via assumed role with OAuth tokens will be misidentified as regular `USER` principals
2. **Security Risk:** Assumed role sessions may gain incorrect permissions if role mappings differ between users and assumed roles
3. **Feature Parity:** OAuth path cannot match STS path's full authorization capabilities

---

## Role Mapping Consistency

### ✅ GOOD NEWS: Role Mapping Language IS Consistent

Both authentication paths use **identical role mapping logic** in `CloudIamRealm.resolveRoles()`:

```java
UserRoleMapper.UserData userData = new UserRoleMapper.UserData(
    principal.arn(),  // ARN is the key identifier
    null,
    List.of(),
    metadata,
    config
);
roleMapper.resolveRoles(userData, ...);
```

**Key Points:**
- Both paths produce an `IamPrincipal` with an ARN
- The ARN is the sole identifier passed to `UserRoleMapper`
- Both paths build ARNs in the same format:
  - User: `acs:ram::{accountId}:user/{userName}`
  - Role: `acs:ram::{accountId}:role/{roleName}`
- Role mapping rules apply uniformly regardless of authentication path

---

## Architectural Asymmetry

| Aspect | STS Signature | OAuth Token |
|--------|---------------|-------------|
| Principal Detection | ARN structure (authoritative) | Response field (declarative) |
| Type Source | Aliyun STS API ARN field | Aliyun OAuth `type` field |
| Extensibility | Can add new ARN patterns | Requires new type handling |
| Assumed Role Support | ✅ Native via ARN parsing | ❌ Requires code addition |

---

## Configuration Reference

### Realm Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `auth.allow_assumed_role` | `false` | Allow assumed role authentication (STS path only) |
| `role_mapping.enabled` | `true` | Enable role mapping via UserRoleMapper |
| `auth.mode` | - | Authentication mode (`mock`, `sts`, `oauth`) |
| `iam.endpoint` | - | Override IAM/OAuth endpoint |

### File Locations

- Realm implementation: `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/CloudIamRealm.java`
- STS client: `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/AliyunStsClient.java`
- OAuth validator: `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/OAuthTokenValidator.java`
- Principal types: `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/IamPrincipal.java`

---

## Recommendations

### For Current Users

1. **Use STS Signature** if you need assumed role support
2. **Enable assumed roles** by setting `auth.allow_assumed_role: true` in elasticsearch.yml
3. **Be aware** that OAuth-based assumed role authentication will not work correctly

### For Future Development

1. Add assumed role support to `OAuthTokenValidator` by:
   - Handling `type: "assumed-role"` in the userinfo response
   - OR implementing ARN-based fallback similar to STS path
2. Consider unifying principal detection logic to reduce asymmetry
3. Add logging when principal type defaults occur for better debugging
