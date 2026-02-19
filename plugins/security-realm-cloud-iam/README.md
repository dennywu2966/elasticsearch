# Cloud IAM Realm Plugin for Elasticsearch

**Aliyun RAM Authentication Realm for Elasticsearch X-Pack Security**

---

## Overview

This plugin adds a custom security realm to Elasticsearch that enables authentication using Aliyun (Alibaba Cloud) Resource Access Management (RAM) credentials. It supports both RAM users and STS assumed role sessions.

### Features

- ✅ Aliyun RAM user authentication
- ✅ STS assumed role session authentication
- ✅ Flexible role mapping based on ARN patterns
- ✅ Account-level and user-level access control
- ✅ Mock mode for testing
- ✅ Configurable time skew tolerance

---

## Quick Start

### Prerequisites

- Elasticsearch 9.2.4-SNAPSHOT
- Trial or Platinum license (required for custom security realms)
- Aliyun RAM credentials (for production use)

### Installation

The plugin is built as part of the Elasticsearch build process:

```bash
# Build the plugin
./gradlew :plugins:security-realm-cloud-iam:assemble

# Build local distribution with plugin
./gradlew localDistro
```

### Configuration

Add to `elasticsearch.yml`:

```yaml
xpack.security.enabled: true
xpack.license.self_generated.type: trial

xpack.security.authc.realms.cloud_iam.cloud_iam_realm.order: 0
xpack.security.authc.realms.cloud_iam.cloud_iam_realm.role_mapping.enabled: true
xpack.security.authc.realms.cloud_iam.cloud_iam_realm.auth.allow_assumed_role: true
xpack.security.authc.realms.cloud_iam.cloud_iam_realm.auth.allowed_time_skew: 5m
```

### Start Elasticsearch

```bash
cd build/distribution/local/elasticsearch-9.2.4-SNAPSHOT

# Set elastic user password
./bin/elasticsearch-reset-password -u elastic -b

# Start ES
./bin/elasticsearch -d -p elasticsearch.pid
```

---

## Authentication

### Generate Signed Token

Use the provided Python script to generate an authentication header:

```bash
cd plugins/security-realm-cloud-iam/tools

# For production use with real credentials
python3 aliyun_sts_sign.py \
  --access-key-id YOUR_ACCESS_KEY_ID \
  --access-key-secret YOUR_ACCESS_KEY_SECRET

# For testing (mock mode)
python3 aliyun_sts_sign.py --mock
```

### Authenticate with Elasticsearch

```bash
# Get signed token
SIGNED_TOKEN=$(python3 plugins/security-realm-cloud-iam/tools/aliyun_sts_sign.py --mock)

# Authenticate
curl -H "X-ES-IAM-Signed: $SIGNED_TOKEN" \
  "http://localhost:9200/_security/_authenticate?pretty"
```

**Expected Response**:
```json
{
  "username" : "acs:ram::123456789:user/testuser",
  "roles" : [ "data_writer", "read_only" ],
  "full_name" : null,
  "email" : null,
  "metadata" : {
    "cloud_arn" : "acs:ram::123456789:user/testuser",
    "cloud_account" : "123456789",
    "cloud_principal_type" : "user",
    "cloud_user_id" : "123456789"
  },
  "enabled" : true
}
```

---

## Role Mapping

### Overview

Role mappings determine which Elasticsearch roles are assigned to authenticated RAM users based on their ARN and metadata.

### Quick Example

```bash
# Map all users in account 123456789 to data_writer role
curl -u elastic:password -X PUT "https://localhost:9200/_security/role_mapping/ram_account" \
  -H 'Content-Type: application/json' -d '{
    "enabled": true,
    "roles": ["data_writer"],
    "rules": {
      "field": {
        "metadata.cloud_account": "123456789"
      }
    }
  }'
```

### Available Options

#### Option 1: Match by Exact ARN

```bash
curl -u elastic:password -X PUT "https://localhost:9200/_security/role_mapping/specific_user" \
  -H 'Content-Type: application/json' -d '{
    "enabled": true,
    "roles": ["admin"],
    "rules": {
      "field": {
        "metadata.cloud_arn": "acs:ram::123456789:user/john.doe"
      }
    }
  }'
```

#### Option 2: Match by Account ID

```bash
curl -u elastic:password -X PUT "https://localhost:9200/_security/role_mapping/team" \
  -H 'Content-Type: application/json' -d '{
    "enabled": true,
    "roles": ["data_writer", "read_only"],
    "rules": {
      "field": {
        "metadata.cloud_account": "123456789"
      }
    }
  }'
```

#### Option 3: Match by Principal Type

```bash
curl -u elastic:password -X PUT "https://localhost:9200/_security/role_mapping/humans_only" \
  -H 'Content-Type: application/json' -d '{
    "enabled": true,
    "roles": ["admin"],
    "rules": {
      "field": {
        "metadata.cloud_principal_type": "user"
      }
    }
  }'
```

#### Option 4: Match All RAM Users

```bash
curl -u elastic:password -X PUT "https://localhost:9200/_security/role_mapping/all_ram" \
  -H 'Content-Type: application/json' -d '{
    "enabled": true,
    "roles": ["read_only"],
    "rules": {"all": []}
  }'
```

### Available Metadata Fields

| Field | Description | Example |
|-------|-------------|---------|
| `metadata.cloud_arn` | Full ARN (also username) | `acs:ram::123456789:user/testuser` |
| `metadata.cloud_account` | Aliyun account ID | `123456789` |
| `metadata.cloud_principal_type` | Type of principal | `user`, `role`, `assumed_role` |
| `metadata.cloud_user_id` | Aliyun internal user ID | `2873482734827348` |

---

## Documentation

### Comprehensive Validation Guide

For detailed role mapping options, validation results, best practices, and troubleshooting, see:

📖 **[COMPREHENSIVE_VALIDATION_GUIDE.md](docs/COMPREHENSIVE_VALIDATION_GUIDE.md)**

**Topics Covered**:
- Complete ARN structure explanation
- All role mapping options with examples
- Validation test results (positive and negative cases)
- Best practices for production deployments
- Troubleshooting common issues
- Complete multi-tenant SaaS example

### How to Start Elasticsearch with Plugins

For detailed setup instructions including Lance Vector plugin:

📖 **[../../how-to-start-es-with-plugins.md](../../how-to-start-es-with-plugins.md)**

---

## Configuration Reference

### Realm Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `order` | integer | `0` | Priority of this realm in authentication chain |
| `role_mapping.enabled` | boolean | `false` | Enable role mapping for this realm |
| `auth.allow_assumed_role` | boolean | `false` | Allow STS assumed role authentication |
| `auth.allowed_time_skew` | time | `5m` | Maximum clock skew for signature validation |
| `auth.mode` | string | `real` | Authentication mode: `real` or `mock` (testing) |
| `auth.mock_signature` | string | `"mock"` | Mock signature for testing |
| `auth.mock_arn_template` | string | See below | Mock ARN template for testing |

### Mock ARN Template

Default mock ARN template (for testing):
```
acs:ram::000000000000:user/%s
```

The `%s` placeholder is replaced with the mock signature value.

---

## Testing

### Unit Tests

```bash
# Run all Cloud IAM plugin tests
./gradlew :plugins:security-realm-cloud-iam:test

# Run specific test
./gradlew :plugins:security-realm-cloud-iam:test \
  --tests org.elasticsearch.plugin.security.cloudiam.CloudIamRealmTests
```

### Integration Tests

```bash
# Run integration tests
./gradlew :plugins:security-realm-cloud-iam:integTest
```

### Manual Testing with Mock Mode

1. Configure mock mode in `elasticsearch.yml`:
   ```yaml
   xpack.security.authc.realms.cloud_iam.cloud_iam_realm.auth.mode: mock
   ```

2. Create role mapping:
   ```bash
   curl -u elastic:password -X PUT "http://localhost:9200/_security/role_mapping/test" \
     -H 'Content-Type: application/json' -d '{
       "enabled": true,
       "roles": ["superuser"],
       "rules": {"all": []}
     }'
   ```

3. Generate mock token:
   ```bash
   MOCK_TOKEN=$(python3 plugins/security-realm-cloud-iam/tools/aliyun_sts_sign.py --mock)
   ```

4. Authenticate:
   ```bash
   curl -H "X-ES-IAM-Signed: $MOCK_TOKEN" \
     "http://localhost:9200/_security/_authenticate?pretty"
   ```

---

## Architecture

### Components

- **`CloudIamRealmPlugin`**: Main plugin class that registers the realm
- **`CloudIamRealm`**: Authentication realm implementation
- **`AliyunStsClient`**: STS client for verifying credentials
- **`MockIamClient`**: Mock client for testing
- **`IamPrincipal`**: Data class representing authenticated principal

### Authentication Flow

1. Client sends signed request with `X-ES-IAM-Signed` header
2. `CloudIamRealm` extracts signature from header
3. `AliyunStsClient` calls Aliyun STS GetCallerIdentity API
4. ARN is extracted from response
5. `UserRoleMapper` maps ARN to Elasticsearch roles based on rules
6. Authentication result returned with assigned roles

### Metadata Extraction

The plugin extracts the following metadata from the ARN:

```java
String arn = "acs:ram::123456789:user/testuser";
String accountId = "123456789";
String principalType = "user";  // or "role", "assumed_role"
String userId = "testuser";
```

---

## Production Deployment

### Security Considerations

1. **Use Trial License**: Required for custom security realms
   ```bash
   curl -X POST "http://localhost:9200/_license/start_trial?acknowledge=true"
   ```

2. **Disable Mock Mode**: Ensure `auth.mode` is not set to `mock`
   ```yaml
   xpack.security.authc.realms.cloud_iam.cloud_iam_realm.auth.mode: real
   ```

3. **Use HTTPS**: Enable SSL/TLS for production
   ```yaml
   xpack.security.http.ssl.enabled: true
   ```

4. **Set Appropriate Time Skew**: Adjust based on your environment
   ```yaml
   xpack.security.authc.realms.cloud_iam.cloud_iam_realm.auth.allowed_time_skew: 5m
   ```

### Multi-Node Deployment

For multi-node clusters, ensure all nodes have:
- Same realm configuration
- Trial license activated
- Role mappings configured (stored in cluster state)

---

## Troubleshooting

### Issue: Realm Not Loaded

**Symptoms**: Role mappings fail, realm not listed in authentication realms

**Solution**:
1. Check license type: `curl "localhost:9200/_license?pretty"`
2. Verify plugin loaded: `curl "localhost:9200/_cat/plugins?v"`
3. Check logs: `tail -100 logs/elasticsearch.log`

### Issue: Authentication Fails

**Symptoms**: `401 Unauthorized` response

**Solution**:
1. Verify signature generation
2. Check clock skew (ensure system time is synchronized)
3. Test with mock mode first
4. Check realm logs: `grep "cloud_iam" logs/elasticsearch.log`

### Issue: Role Mapping Not Applied

**Symptoms**: User authenticates but doesn't receive expected roles

**Solution**:
1. List role mappings: `curl -u elastic:password "localhost:9200/_security/role_mapping?pretty"`
2. Verify mapping is enabled
3. Check ARN pattern matches actual user ARN
4. Test authentication response: See "Authentication" section above

For comprehensive troubleshooting, see the **[COMPREHENSIVE_VALIDATION_GUIDE.md](docs/COMPREHENSIVE_VALIDATION_GUIDE.md)**.

---

## Contributing

### Running Tests

```bash
# Unit tests
./gradlew :plugins:security-realm-cloud-iam:test

# Integration tests
./gradlew :plugins:security-realm-cloud-iam:integTest

# Check code style
./gradlew :plugins:security-realm-cloud-iam:spotlessJavaCheck
```

### Code Structure

```
plugins/security-realm-cloud-iam/
├── src/main/java/.../cloudiam/
│   ├── CloudIamRealmPlugin.java      # Plugin entry point
│   ├── CloudIamRealm.java             # Realm implementation
│   ├── CloudIamRealmSettings.java     # Configuration settings
│   ├── AliyunStsClient.java           # Real STS client
│   ├── MockIamClient.java             # Mock client for testing
│   └── IamPrincipal.java              # Principal data class
├── src/test/java/.../cloudiam/
│   ├── CloudIamRealmTests.java        # Realm tests
│   └── AliyunStsClientTests.java      # STS client tests
├── tools/
│   └── aliyun_sts_sign.py             # Signature generation tool
└── docs/
    └── COMPREHENSIVE_VALIDATION_GUIDE.md  # Detailed role mapping guide
```

---

## License

Elastic License 2.0

---

## Support

For issues and questions:
1. Check the [COMPREHENSIVE_VALIDATION_GUIDE.md](docs/COMPREHENSIVE_VALIDATION_GUIDE.md)
2. Review Elasticsearch logs
3. Check role mapping configuration
4. Verify license and realm settings

---

**Version**: 9.2.4-SNAPSHOT
**Last Updated**: 2026-01-27
