#!/usr/bin/env bash
#
# Cloud IAM E2E Validation Script
# Validates the Cloud IAM realm plugin implementation
#

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
ES_PORT="${ES_PORT:-9200}"
ES_URL="https://127.0.0.1:${ES_PORT}"
ES_USER="${ES_USER:-elastic}"
ES_HOME="${ES_HOME:-build/distribution/local/elasticsearch-9.2.4-SNAPSHOT}"

# Helper functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

check_prereqs() {
    log_info "Checking prerequisites..."

    # Check RAM credentials
    if [[ -z "${RAM_AK:-}" ]] || [[ -z "${RAM_SK:-}" ]]; then
        log_error "RAM_AK and RAM_SK environment variables must be set"
        exit 1
    fi

    # Check Python
    if ! command -v python3 &> /dev/null; then
        log_error "python3 is required"
        exit 1
    fi

    # Check curl
    if ! command -v curl &> /dev/null; then
        log_error "curl is required"
        exit 1
    fi

    log_info "✓ Prerequisites check passed"
}

wait_for_elasticsearch() {
    log_info "Waiting for Elasticsearch at ${ES_URL}..."

    local max_attempts=60
    local attempt=0

    while [[ $attempt -lt $max_attempts ]]; do
        if curl -k -s -u "${ES_USER}:${ES_PASS}" "${ES_URL}/_cluster/health" > /dev/null 2>&1; then
            log_info "✓ Elasticsearch is ready"
            return 0
        fi
        ((attempt++))
        sleep 1
    done

    log_error "Elasticsearch did not start within ${max_attempts} seconds"
    return 1
}

configure_cloud_iam_realm() {
    log_info "Configuring Cloud IAM realm..."

    # Configure Cloud IAM realm via cluster settings
    curl -k -s -u "${ES_USER}:${ES_PASS}" -X PUT "${ES_URL}/_cluster/settings" \
        -H "Content-Type: application/json" \
        -d '{
            "persistent": {
                "xpack.security.authc.realms.cloud.cloud_iam": {
                    "order": 0,
                    "role_mapping_enabled": true,
                    "allow_assumed_role": true
                }
            }
        }' | jq -r '.acknowledged'

    log_info "✓ Cloud IAM realm configured"
}

create_test_roles() {
    log_info "Creating test roles..."

    # Create read_only role
    curl -k -s -u "${ES_USER}:${ES_PASS}" -X PUT "${ES_URL}/_security/role/read_only" \
        -H "Content-Type: application/json" \
        -d '{
            "indices": [
                {
                    "names": ["*"],
                    "privileges": ["read", "view_index_metadata"]
                }
            ],
            "cluster": ["monitor"]
        }' | jq -r '.role.created'

    # Create data_writer role
    curl -k -s -u "${ES_USER}:${ES_PASS}" -X PUT "${ES_URL}/_security/role/data_writer" \
        -H "Content-Type: application/json" \
        -d '{
            "indices": [
                {
                    "names": ["data-*"],
                    "privileges": ["write", "create_index", "delete", "read"]
                }
            ]
        }' | jq -r '.role.created'

    # Create cluster_monitor role
    curl -k -s -u "${ES_USER}:${ES_PASS}" -X PUT "${ES_URL}/_security/role/cluster_monitor" \
        -H "Content-Type: application/json" \
        -d '{
            "cluster": ["monitor", "read"]
        }' | jq -r '.role.created'

    log_info "✓ Roles created: read_only, data_writer, cluster_monitor"
}

create_role_mappings() {
    log_info "Creating role mappings..."

    # Map all RAM users to data_writer and read_only roles
    curl -k -s -u "${ES_USER}:${ES_PASS}" -X PUT "${ES_URL}/_security/role_mapping/ram_all_users" \
        -H "Content-Type: application/json" \
        -d '{
            "roles": ["data_writer", "read_only"],
            "rules": {
                "all": [
                    {
                        "field": {
                            "metadata.cloud_principal_type": "user"
                        }
                    }
                ]
            }
        }' | jq -r '.created'

    log_info "✓ Role mapping created: ram_all_users → data_writer, read_only"
}

test_authentication() {
    log_info "Testing IAM authentication..."

    # Generate signed header using Python script
    local signed_header
    signed_header=$(python3 plugins/security-realm-cloud-iam/tools/aliyun_sts_sign.py \
        --access-key-id "$RAM_AK" \
        --access-key-secret "$RAM_SK")

    # Test authentication endpoint
    local response
    response=$(curl -k -s -X GET "${ES_URL}/_security/_authenticate" \
        -H "X-ES-IAM-Signed: ${signed_header}" \
        -w "\n%{http_code}")

    local http_code
    http_code=$(echo "$response" | tail -n1)
    local body
    body=$(echo "$response" | head -n-1)

    if [[ "$http_code" == "200" ]]; then
        log_info "✓ IAM authentication: working (HTTP 200)"
        echo "$body" | jq '.'
    else
        log_error "✗ IAM authentication failed (HTTP $http_code)"
        echo "$body" | jq '.'
        return 1
    fi
}

test_privilege_enforcement() {
    log_info "Testing privilege enforcement..."

    local signed_header
    signed_header=$(python3 plugins/security-realm-cloud-iam/tools/aliyun_sts_sign.py \
        --access-key-id "$RAM_AK" \
        --access-key-secret "$RAM_SK")

    local passed=0
    local total=0

    # Test 1: Write to data-* indices (should succeed)
    ((total++))
    log_info "Test 1: Write to data-* indices"
    local response
    response=$(curl -k -s -X PUT "${ES_URL}/data-test/_doc/1" \
        -H "X-ES-IAM-Signed: ${signed_header}" \
        -H "Content-Type: application/json" \
        -d '{"test": "data"}' \
        -w "\n%{http_code}")
    local http_code
    http_code=$(echo "$response" | tail -n1)
    if [[ "$http_code" == "200" ]] || [[ "$http_code" == "201" ]]; then
        log_info "✓ Write to data-* indices: HTTP $http_code (allowed)"
        ((passed++))
    else
        log_error "✗ Write to data-* indices: HTTP $http_code (expected 200/201)"
    fi

    # Test 2: Delete cluster settings (should be denied)
    ((total++))
    log_info "Test 2: Delete cluster settings (should be denied)"
    response=$(curl -k -s -X DELETE "${ES_URL}/_cluster/settings" \
        -H "X-ES-IAM-Signed: ${signed_header}" \
        -w "\n%{http_code}")
    http_code=$(echo "$response" | tail -n1)
    if [[ "$http_code" == "403" ]]; then
        log_info "✓ Delete cluster settings: HTTP 403 (denied as expected)"
        ((passed++))
    else
        log_error "✗ Delete cluster settings: HTTP $http_code (expected 403)"
    fi

    # Test 3: Write to restricted-* indices (should be denied)
    ((total++))
    log_info "Test 3: Write to restricted-* indices (should be denied)"
    response=$(curl -k -s -X PUT "${ES_URL}/restricted-test/_doc/1" \
        -H "X-ES-IAM-Signed: ${signed_header}" \
        -H "Content-Type: application/json" \
        -d '{"test": "data"}' \
        -w "\n%{http_code}")
    http_code=$(echo "$response" | tail -n1)
    if [[ "$http_code" == "403" ]]; then
        log_info "✓ Write to restricted-* indices: HTTP 403 (denied as expected)"
        ((passed++))
    else
        log_error "✗ Write to restricted-* indices: HTTP $http_code (expected 403)"
    fi

    # Test 4: Read from any index (should succeed)
    ((total++))
    log_info "Test 4: Read from any index"
    response=$(curl -k -s -X GET "${ES_URL}/_all/_search" \
        -H "X-ES-IAM-Signed: ${signed_header}" \
        -H "Content-Type: application/json" \
        -d '{"query": {"match_all": {}}}' \
        -w "\n%{http_code}")
    http_code=$(echo "$response" | tail -n1)
    if [[ "$http_code" == "200" ]] || [[ "$http_code" == "404" ]]; then
        log_info "✓ Read from any index: HTTP $http_code (allowed)"
        ((passed++))
    else
        log_error "✗ Read from any index: HTTP $http_code (expected 200/404)"
    fi

    # Test 5: Read cluster health (should succeed)
    ((total++))
    log_info "Test 5: Read cluster health"
    response=$(curl -k -s -X GET "${ES_URL}/_cluster/health" \
        -H "X-ES-IAM-Signed: ${signed_header}" \
        -w "\n%{http_code}")
    http_code=$(echo "$response" | tail -n1)
    if [[ "$http_code" == "200" ]]; then
        log_info "✓ Read cluster health: HTTP 200 (allowed)"
        ((passed++))
    else
        log_error "✗ Read cluster health: HTTP $http_code (expected 200)"
    fi

    log_info "Privilege enforcement: $passed/$total tests passed"

    if [[ $passed -eq $total ]]; then
        log_info "✓ All privilege tests passed"
        return 0
    else
        log_error "✗ Some privilege tests failed"
        return 1
    fi
}

main() {
    echo "=========================================="
    echo "Cloud IAM E2E Validation"
    echo "=========================================="
    echo ""

    check_prereqs
    wait_for_elasticsearch
    configure_cloud_iam_realm
    create_test_roles
    create_role_mappings

    echo ""
    echo "=========================================="
    echo "Running Tests"
    echo "=========================================="
    echo ""

    if test_authentication && test_privilege_enforcement; then
        echo ""
        log_info "=== All Tests Passed ==="
        exit 0
    else
        echo ""
        log_error "=== Some Tests Failed ==="
        exit 1
    fi
}

# Get ES password from argument or prompt
if [[ -z "${ES_PASS:-}" ]]; then
    if [[ $# -gt 0 ]]; then
        ES_PASS="$1"
    else
        # Try to get password from auto-generated file
        if [[ -f "$ES_HOME/config/elastic-certificates.p12" ]]; then
            echo "Enter elastic password (or press Enter to auto-reset):"
            read -r ES_PASS
            if [[ -z "$ES_PASS" ]]; then
                ES_PASS=$("$ES_HOME/bin/elasticsearch-reset-password" -u elastic -b 2>&1 | grep "New value:" | cut -d' ' -f3)
            fi
        else
            echo "Enter elastic password:"
            read -r ES_PASS
        fi
    fi
fi

main "$@"
