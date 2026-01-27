---
name: iam-validate
description: End-to-end validation for the Cloud IAM realm plugin to prevent regressions after code changes. Use when modifying Cloud IAM authentication code (AliyunStsClient, CloudIamRealm, IamPrincipal, IamClient), changing role mapping logic or privilege enforcement, committing Cloud IAM plugin changes, verifying IAM signature verification, or testing privilege enforcement (allowed vs denied actions).
---

# Cloud IAM Realm E2E Validation

**Status: ✅ VALIDATED (2026-01-27)**

The Cloud IAM realm plugin has been successfully validated and is working as expected.

## Validation Summary

- **Authentication**: ✅ Working
- **Signature Verification**: ✅ Working (Aliyun STS integration)
- **Role Mapping**: ✅ Working
- **Privilege Enforcement**: ✅ Working
- **License Requirement**: ✅ Requires trial or paid license

## Quick Start

### Prerequisites

1. **License**: Cloud IAM realm requires a trial or paid license (not available with basic license)
   ```bash
   # Start trial license
   curl -k -u elastic:password -X POST "https://localhost:9200/_license/start_trial?acknowledge=true"
   ```

2. **Configuration**: Add to `elasticsearch.yml`:
   ```yaml
   xpack.security.enabled: true
   xpack.license.self_generated.type: trial  # or use API to start trial
   xpack.security.authc.realms.cloud_iam.cloud_iam_realm.order: 0
   xpack.security.authc.realms.cloud_iam.cloud_iam_realm.role_mapping.enabled: true
   xpack.security.authc.realms.cloud_iam.cloud_iam_realm.auth.allow_assumed_role: true
   xpack.security.authc.realms.cloud_iam.cloud_iam_realm.auth.allowed_time_skew: 5m
   ```

### Usage

Generate signed header and authenticate:

```bash
# Generate signed header
SIGNED=$(python3 plugins/security-realm-cloud-iam/tools/aliyun_sts_sign.py \
  --access-key-id "$RAM_AK" --access-key-secret "$RAM_SK")

# Test authentication
curl -k -H "X-ES-IAM-Signed: $SIGNED" "https://localhost:9200/_security/_authenticate"

# Access ES with IAM credentials
curl -k -H "X-ES-IAM-Signed: $SIGNED" "https://localhost:9200/_cluster/health"
```

## Validation Test Results

### Test Environment

- **Elasticsearch**: 9.2.4-SNAPSHOT
- **License**: Trial (started via API)
- **Realm**: cloud_iam.cloud_iam_realm
- **RAM Account**: 1437310945246567 (Aliyun)

### Authentication Test

```bash
$ curl -k -H "X-ES-IAM-Signed: $SIGNED" "https://localhost:9200/_security/_authenticate"
```

**Result**: ✅ Success

```json
{
  "username": "acs:ram::1437310945246567:user/dongdongplanet",
  "roles": ["read_only", "data_writer"],
  "metadata": {
    "cloud_account": "1437310945246567",
    "cloud_user_id": "208715937258808475",
    "cloud_principal_type": "user",
    "cloud_arn": "acs:ram::1437310945246567:user/dongdongplanet"
  },
  "enabled": true,
  "authentication_realm": {"name": "cloud_iam_realm", "type": "cloud_iam"}
}
```

### Privilege Enforcement Tests

| Test | Expected | Result | HTTP Status |
|------|----------|--------|-------------|
| Write to `data-*` indices | ✅ Allow | ✅ Pass | 200 |
| Write to `restricted-*` indices | ❌ Deny | ✅ Pass | 403 |
| Read cluster health | ✅ Allow | ✅ Pass | 200 |
| Update cluster settings | ❌ Deny | ✅ Pass | 403 |

**All privilege tests passed ✅**

#### Test Details

1. **Write to allowed indices** (`data-*`):
   ```bash
   curl -k -X PUT "https://localhost:9200/data-test/_doc/1" \
     -H "X-ES-IAM-Signed: $SIGNED" \
     -d '{"test": "data"}'
   ```
   **Result**: ✅ HTTP 200 - Document created

2. **Write to denied indices** (`restricted-*`):
   ```bash
   curl -k -X PUT "https://localhost:9200/restricted-test/_doc/1" \
     -H "X-ES-IAM-Signed: $SIGNED" \
     -d '{"test": "data"}'
   ```
   **Result**: ✅ HTTP 403 - Permission denied as expected

3. **Read cluster health**:
   ```bash
   curl -k -X GET "https://localhost:9200/_cluster/health" \
     -H "X-ES-IAM-Signed: $SIGNED"
   ```
   **Result**: ✅ HTTP 200 - Access allowed

4. **Update cluster settings**:
   ```bash
   curl -k -X PUT "https://localhost:9200/_cluster/settings" \
     -H "X-ES-IAM-Signed: $SIGNED" \
     -d '{"persistent": {"indices.query.bool.max_clause_count": 5000}}'
   ```
   **Result**: ✅ HTTP 403 - Permission denied as expected

## Configuration Examples

### Create Roles and Role Mappings

```bash
# Create data_writer role
curl -k -u elastic:password -X PUT "https://localhost:9200/_security/role/data_writer" \
  -H "Content-Type: application/json" \
  -d '{
    "indices": [{
      "names": ["data-*"],
      "privileges": ["write", "create_index", "delete", "read"]
    }]
  }'

# Create read_only role
curl -k -u elastic:password -X PUT "https://localhost:9200/_security/role/read_only" \
  -H "Content-Type: application/json" \
  -d '{
    "indices": [{"names": ["*"], "privileges": ["read", "view_index_metadata"]}],
    "cluster": ["monitor"]
  }'

# Create role mapping (match all RAM users)
curl -k -u elastic:password -X PUT "https://localhost:9200/_security/role_mapping/ram_all_users" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "roles": ["data_writer", "read_only"],
    "rules": {"all": []}
  }'
```

### Advanced Role Mapping (Match by Metadata)

```bash
# Match only RAM users (not assumed roles)
curl -k -u elastic:password -X PUT "https://localhost:9200/_security/role_mapping/ram_users_only" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "roles": ["data_writer"],
    "rules": {
      "all": [{
        "field": {"metadata.cloud_principal_type": "user"}
      }]
    }
  }'

# Match specific account
curl -k -u elastic:password -X PUT "https://localhost:9200/_security/role_mapping/specific_account" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "roles": ["superuser"],
    "rules": {
      "all": [{
        "field": {"metadata.cloud_account": "1437310945246567"}
      }]
    }
  }'
```

## Key Implementation Details

### Authentication Flow

1. **Client** sends request with `X-ES-IAM-Signed` header containing:
   - Aliyun STS GetCallerIdentity request parameters
   - HMAC signature (HMAC-SHA1 or HMAC-SHA256)
   - Base64-encoded JSON payload

2. **ES Realm** (`CloudIamRealm`):
   - Extracts and validates signed header
   - Checks timestamp (configurable skew, default 5 minutes)
   - Checks nonce (prevents replay attacks)
   - Forwards to Aliyun STS for verification

3. **Aliyun STS** (`AliyunStsClient`):
   - Verifies signature via GetCallerIdentity API
   - Returns ARN, Account ID, User ID, Principal Type

4. **Role Mapping**:
   - Maps authenticated user to ES roles based on metadata
   - Supports rules on `cloud_arn`, `cloud_account`, `cloud_principal_type`, `cloud_user_id`

### Security Features

- ✅ **Signature Verification**: Handled by Aliyun STS (secure by design)
- ✅ **Replay Protection**: Nonce caching (configurable TTL, default 5 minutes)
- ✅ **Timestamp Validation**: Configurable skew (default 5 minutes)
- ✅ **Caching**: Auth results cached (configurable TTL, default 5 minutes)
- ✅ **Assumed Role Support**: Optional (controlled by `allow_assumed_role` setting)

### Metadata Available for Role Mapping

Authenticated user metadata:
```json
{
  "cloud_arn": "acs:ram::1437310945246567:user/dongdongplanet",
  "cloud_account": "1437310945246567",
  "cloud_user_id": "208715937258808475",
  "cloud_principal_type": "user"  // or "role", "assumed_role"
}
```

## Troubleshooting

### License Issue

**Symptom**: `Realms [cloud_iam/cloud_iam_realm] were skipped because they are not permitted on the current license`

**Solution**: Start trial license
```bash
curl -k -u elastic:password -X POST "https://localhost:9200/_license/start_trial?acknowledge=true"
```

### No Roles Mapped

**Symptom**: `Authentication was terminated by realm [cloud_iam_realm] - no roles mapped`

**Solution**: Create role mappings
```bash
curl -k -u elastic:password -X PUT "https://localhost:9200/_security/role_mapping/ram_all_users" \
  -H "Content-Type: application/json" \
  -d '{"enabled": true, "roles": ["read_only"], "rules": {"all": []}}'
```

### Replay Attack Detected

**Symptom**: `replayed iam token`

**Solution**: Generate new signature for each request (nonce is single-use)

## Key Files

**Plugin**:
- `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/AliyunStsClient.java` - STS verification
- `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/CloudIamRealm.java` - Authentication realm
- `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/CloudIamRealmSettings.java` - Configuration
- `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/IamPrincipal.java` - Principal representation
- `plugins/security-realm-cloud-iam/tools/aliyun_sts_sign.py` - Reference signing implementation

**Documentation**:
- `CLOUD-IAM-VALIDATION.md` - This file
- `docs/cloud-iam-e2e.sh` - E2E validation script

## References

- **Aliyun ARN Format**: `acs:service:region:account-id:resource`
- **STS API**: Aliyun STS GetCallerIdentity
- **Signature Method**: Aliyun RPC signature (HMAC-SHA1 or HMAC-SHA256)
