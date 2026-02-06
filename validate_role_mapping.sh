#!/bin/bash
# Validation script for Cloud IAM role mapping options
# Tests both positive and negative cases for ARN-based role mapping

set -e

ES_URL="http://localhost:9200"
ES_USER="elastic:T5J71AHxv_f-nKYFJAvm"

echo "========================================"
echo "Cloud IAM Role Mapping Validation"
echo "========================================"
echo ""

# Step 1: Check if ES is running
echo "Step 1: Checking Elasticsearch..."
if ! curl -k -s -u "$ES_USER" "$ES_URL/_cluster/health" > /dev/null 2>&1; then
    echo "❌ Elasticsearch is not running or not accessible"
    echo "   Please start ES with: ./gradlew run -Dtests.es.xpack.security.enabled=false -Drun.license_type=trial"
    exit 1
fi
echo "✅ Elasticsearch is running"
echo ""

# Step 2: Start trial license if needed
echo "Step 2: Ensuring trial license..."
curl -k -s -u "$ES_USER" -X POST "$ES_URL/_license/start_trial?acknowledge=true" | grep -q "acknowledged" && echo "✅ Trial license started" || echo "ℹ️  Trial license already active"
echo ""

# Step 3: Create test roles
echo "Step 3: Creating test roles..."

# Role with full access
curl -k -s -u "$ES_USER" -X PUT "$ES_URL/_security/role/arn_admin_role" \
  -H 'Content-Type: application/json' -d '{
    "cluster": ["all"],
    "indices": [{"names": ["*"], "privileges": ["all"]}]
  }' | grep -q "created" && echo "✅ Created arn_admin_role" || echo "⚠️  arn_admin_role already exists"

# Role with restricted access
curl -k -s -u "$ES_USER" -X PUT "$ES_URL/_security/role/arn_restricted_role" \
  -H 'Content-Type: application/json' -d '{
    "indices": [{"names": ["public-*"], "privileges": ["read"]}]
  }' | grep -q "created" && echo "✅ Created arn_restricted_role" || echo "⚠️  arn_restricted_role already exists"

echo ""

# Step 4: Delete existing role mappings if they exist
echo "Step 4: Cleaning up old role mappings..."
for mapping in arn_exact_match arn_by_account arn_by_user_id arn_all_match; do
    curl -k -s -u "$ES_USER" -X DELETE "$ES_URL/_security/role_mapping/$mapping" > /dev/null 2>&1 && echo "  Deleted old mapping: $mapping" || true
done
echo "✅ Cleanup complete"
echo ""

# Step 5: Create role mappings with different options
echo "Step 5: Creating role mappings..."

# Option 1: Match by exact ARN using username field
curl -k -s -u "$ES_USER" -X PUT "$ES_URL/_security/role_mapping/arn_exact_match" \
  -H 'Content-Type: application/json' -d '{
    "enabled": true,
    "roles": ["arn_admin_role"],
    "rules": {
      "field": {
        "username": "acs:ram::1437310945246567:user/dongdongplanet"
      }
    }
  }' | grep -q "created" && echo "✅ Created arn_exact_match (exact ARN via username)"

# Option 2: Match by account ID using metadata
curl -k -s -u "$ES_USER" -X PUT "$ES_URL/_security/role_mapping/arn_by_account" \
  -H 'Content-Type: application/json' -d '{
    "enabled": true,
    "roles": ["arn_restricted_role"],
    "rules": {
      "field": {
        "metadata.cloud_account": "1437310945246567"
      }
    }
  }' | grep -q "created" && echo "✅ Created arn_by_account (by account ID)"

# Option 3: Match by user ID using metadata
curl -k -s -u "$ES_USER" -X PUT "$ES_URL/_security/role_mapping/arn_by_user_id" \
  -H 'Content-Type: application/json' -d '{
    "enabled": true,
    "roles": ["arn_restricted_role"],
    "rules": {
      "field": {
        "metadata.cloud_user_id": "208715937258808475"
      }
    }
  }' | grep -q "created" && echo "✅ Created arn_by_user_id (by user ID)"

# Option 4: Match all users (fallback)
curl -k -s -u "$ES_USER" -X PUT "$ES_URL/_security/role_mapping/arn_all_match" \
  -H 'Content-Type: application/json' -d '{
    "enabled": true,
    "roles": ["arn_restricted_role"],
    "rules": {"all": []}
  }' | grep -q "created" && echo "✅ Created arn_all_match (all users fallback)"

echo ""
echo "========================================"
echo "Role Mapping Configuration Complete"
echo "========================================"
echo ""
echo "Active Role Mappings:"
curl -k -s -u "$ES_USER" "$ES_URL/_security/role_mapping?pretty" | grep -E "(name|roles.*\[|enabled)"
echo ""

# Step 6: Test authentication and role assignment
echo "========================================"
echo "Testing Authentication & Role Assignment"
echo "========================================"
echo ""

# Check if credentials are available
if [ -z "$RAM_AK" ] || [ -z "$RAM_SK" ]; then
    echo "⚠️  RAM_AK and RAM_SK environment variables not set"
    echo "   Please set them with:"
    echo "   export RAM_AK='your-access-key-id'"
    echo "   export RAM_SK='your-access-key-secret'"
    echo ""
    echo "   Then run:"
    echo "   source test_role_mapping_positive.sh"
    echo "   source test_role_mapping_negative.sh"
    echo ""
    exit 0
fi

# Generate signed header
echo "Generating IAM signature..."
SIGNED=$(python3 plugins/security-realm-cloud-iam/tools/aliyun_sts_sign.py \
  --access-key-id "$RAM_AK" \
  --access-key-secret "$RAM_SK")

echo "✅ Signature generated"
echo ""

# Test authentication
echo "Testing authentication..."
AUTH_RESPONSE=$(curl -k -s -X GET "$ES_URL/_security/_authenticate" \
  -H "X-ES-IAM-Signed: $SIGNED")

echo "Authentication Response:"
echo "$AUTH_RESPONSE" | jq '.' 2>/dev/null || echo "$AUTH_RESPONSE"
echo ""

# Extract username and roles
USERNAME=$(echo "$AUTH_RESPONSE" | jq -r '.username' 2>/dev/null)
ROLES=$(echo "$AUTH_RESPONSE" | jq -r '.roles[]' 2>/dev/null | tr '\n' ', ' | sed 's/,$//')
METADATA_ARN=$(echo "$AUTH_RESPONSE" | jq -r '.metadata.cloud_arn' 2>/dev/null)
METADATA_ACCOUNT=$(echo "$AUTH_RESPONSE" | jq -r '.metadata.cloud_account' 2>/dev/null)
METADATA_USER_ID=$(echo "$AUTH_RESPONSE" | jq -r '.metadata.cloud_user_id' 2>/dev/null)

echo "========================================"
echo "Validation Results"
echo "========================================"
echo ""
echo "Username (ARN): $USERNAME"
echo "Assigned Roles: $ROLES"
echo "Metadata:"
echo "  cloud_arn: $METADATA_ARN"
echo "  cloud_account: $METADATA_ACCOUNT"
echo "  cloud_user_id: $METADATA_USER_ID"
echo ""

# Check if admin role was assigned
if echo "$ROLES" | grep -q "arn_admin_role"; then
    echo "✅ POSITIVE CASE PASSED: Exact ARN match assigned admin role"
else
    echo "❌ POSITIVE CASE FAILED: Admin role not assigned"
fi

# Check if restricted role was assigned
if echo "$ROLES" | grep -q "arn_restricted_role"; then
    echo "✅ RESTRICTED ROLE ASSIGNED: User has restricted access"
else
    echo "⚠️  RESTRICTED ROLE NOT ASSIGNED"
fi

echo ""
echo "========================================"
echo "Next Steps: Manual Testing"
echo "========================================"
echo ""
echo "To test privilege enforcement:"
echo ""
echo "1. Test with admin privileges (should succeed):"
echo "   curl -k -X PUT '$ES_URL/protected-index/_doc/1' \\"
echo "     -H 'X-ES-IAM-Signed: $SIGNED' \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"test\": \"data\"}'"
echo ""
echo "2. Test with restricted privileges (should fail):"
echo "   curl -k -X PUT '$ES_URL/admin-index/_doc/1' \\"
echo "     -H 'X-ES-IAM-Signed: $SIGNED' \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"test\": \"data\"}'"
echo ""
echo "3. Test read access (should succeed):"
echo "   curl -k -X GET '$ES_URL/public-data/_search' \\"
echo "     -H 'X-ES-IAM-Signed: $SIGNED'"
echo ""
