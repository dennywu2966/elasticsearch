# How to Start Elasticsearch with Both Plugins

**Quick Start Guide for Lance Vector + Cloud IAM Plugins**

---

## Prerequisites

```bash
# 1. Build plugins
./gradlew :plugins:lance-vector:assemble :plugins:security-realm-cloud-iam:assemble

# 2. Build local distribution
./gradlew localDistro

# 3. Navigate to ES directory
cd build/distribution/local/elasticsearch-*/
```

---

## Configuration

### Step 1: JVM Options for Arrow

```bash
# Create Arrow memory access configuration
cat > config/jvm.options.d/lance-arrow.options <<'EOF'
--add-opens=java.base/java.nio=ALL-UNNAMED
EOF
```

### Step 2: Security Configuration

```bash
# Enable security and configure Cloud IAM realm
cat >> config/elasticsearch.yml <<'EOF'

# --- Security Configuration ---
xpack.security.enabled: true
xpack.security.authc.realms.cloud_iam.cloud_iam_realm.order: 0
xpack.security.authc.realms.cloud_iam.cloud_iam_realm.role_mapping.enabled: true
xpack.security.authc.realms.cloud_iam.cloud_iam_realm.auth.allow_assumed_role: true
xpack.security.authc.realms.cloud_iam.cloud_iam_realm.auth.allowed_time_skew: 5m

# --- Trial License (auto-start) ---
xpack.license.self_generated.type: trial
EOF
```

---

## Start Elasticsearch with OSS Credentials

### Option 1: Using Startup Script (RECOMMENDED)

```bash
# Create startup script with OSS credentials
cat > start_es_with_plugins.sh <<'SCRIPT'
#!/bin/bash

# Read OSS credentials from ~/.oss/credentials.json
OSS_CREDS_FILE="$HOME/.oss/credentials.json"

if [ ! -f "$OSS_CREDS_FILE" ]; then
    echo "❌ ERROR: OSS credentials file not found: $OSS_CREDS_FILE"
    echo "   Create it with: {\"access_key_id\": \"YOUR_KEY\", \"access_key_secret\": \"YOUR_SECRET\"}"
    exit 1
fi

# Extract credentials (requires jq)
if command -v jq &> /dev/null; then
    export OSS_ACCESS_KEY_ID=$(jq -r '.access_key_id' "$OSS_CREDS_FILE")
    export OSS_ACCESS_KEY_SECRET=$(jq -r '.access_key_secret' "$OSS_CREDS_FILE")
else
    # Fallback: grep
    export OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' "$OSS_CREDS_FILE" | cut -d'"' -f4)
    export OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' "$OSS_CREDS_FILE" | cut -d'"' -f4)
fi

# Configure OSS endpoint (match your bucket region)
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

# Verify credentials are set
if [ -z "$OSS_ACCESS_KEY_ID" ] || [ -z "$OSS_ACCESS_KEY_SECRET" ]; then
    echo "❌ ERROR: Failed to read OSS credentials from $OSS_CREDS_FILE"
    exit 1
fi

echo "✅ OSS Environment Variables Set:"
echo "   OSS_ENDPOINT=$OSS_ENDPOINT"
echo "   OSS_ACCESS_KEY_ID=${OSS_ACCESS_KEY_ID:0:8}..."
echo "   OSS_ACCESS_KEY_SECRET=${OSS_ACCESS_KEY_SECRET:0:8}..."

# Start Elasticsearch
exec ./bin/elasticsearch "$@"
SCRIPT

chmod +x start_es_with_plugins.sh

# Start ES
./start_es_with_plugins.sh -d -p elasticsearch.pid

echo "✅ Elasticsearch starting..."
echo "   PID: $(cat elasticsearch.pid)"
echo "   Logs: logs/elasticsearch.log"
```

### Option 2: Manual Export (For Testing)

```bash
# Export OSS credentials
export OSS_ACCESS_KEY_ID="YOUR_ACCESS_KEY_ID"
export OSS_ACCESS_KEY_SECRET="YOUR_ACCESS_KEY_SECRET"
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

# Start ES
./bin/elasticsearch -d -p elasticsearch.pid
```

**CRITICAL**: Set OSS environment variables **BEFORE** starting ES. Native Lance Rust code reads from process environment, not Java's `System.getenv()`.

---

## Sanity Checks

### Check 1: Verify ES Started

```bash
# Wait for ES to start (up to 60 seconds)
for i in {1..60}; do
    if curl -s http://localhost:9200 > /dev/null 2>&1; then
        echo "✅ ES is responding on port 9200"
        break
    fi
    echo "Waiting for ES to start... ($i/60)"
    sleep 1
done

# Check cluster health
curl -s http://localhost:9200/_cluster/health?pretty
```

**Expected Output**:
```json
{
  "cluster_name" : "elasticsearch",
  "status" : "green",
  "number_of_nodes" : 1,
  "number_of_data_nodes" : 1
}
```

---

### Check 2: Verify Plugins Loaded

```bash
# Check both plugins are loaded
curl -s http://localhost:9200/_cat/plugins?v

# OR detailed plugin info
curl -s http://localhost:9200/_nodes/plugins?pretty | grep -E "lance-vector|cloud-iam"
```

**Expected Output**:
```
name      component        type     version
lance-vector    plugin
cloud-iam       plugin
```

---

### Check 3: Verify OSS Environment Variables in ES Process

```bash
# Verify OSS credentials are accessible to ES process
ES_PID=$(cat elasticsearch.pid)

if [ -f "/proc/$ES_PID/environ" ]; then
    echo "✅ OSS Environment Variables in ES Process:"
    cat /proc/$ES_PID/environ | tr '\0' '\n' | grep OSS || echo "❌ OSS vars NOT found in ES process!"
else
    echo "⚠️  Cannot check /proc (not Linux?), skipping OSS env verification"
fi
```

**Expected Output**:
```
OSS_ACCESS_KEY_ID=LTAI5t...
OSS_ACCESS_KEY_SECRET=xxxMxxx...
OSS_ENDPOINT=oss-ap-southeast-1.aliyuncs.com
```

If OSS variables are **missing**, Lance won't be able to access OSS datasets.

---

### Check 4: Verify Security is Enabled

```bash
# Check if security is enabled
curl -s http://localhost:9200/_xpack/security/features?pretty

# Start trial license if not active
curl -s -X POST "http://localhost:9200/_license/start_trial?acknowledge=true"
```

**Expected Output**:
```json
{
  "features" : {
    "security" : {
      "available" : true,
      "enabled" : true
    }
  }
}
```

---

### Check 5: Set Default Password

**⚠️ IMPORTANT**: **Always use `Summer11`** as the password for validation and testing.

**First time only** - ES will generate default password for `elastic` user:

```bash
# Check for default password in logs
grep "generated password" logs/elasticsearch.log

# Output: "The generated password for the elastic built-in user is: <password>"
#
# OR reset it manually to the standard password:
./bin/elasticsearch-reset-password -u elastic -b
# When prompted, enter: Summer11

# Verify password works
curl -s -u elastic:Summer11 http://localhost:9200/_cluster/health?pretty
```

**Why `Summer11`?**: This is the standard password used across all validation guides and scripts. Using a consistent password makes it easy to copy and run commands without modification.

---

### Check 6: Verify Cloud IAM Realm

```bash
# Check if Cloud IAM realm exists
curl -s -u elastic:Summer11 http://localhost:9200/_security/realm?pretty | grep -A5 cloud_iam
```

**Expected Output**:
```json
{
  "cloud_iam_realm" : {
    "order" : 0,
    "type" : "cloud_iam"
  }
}
```

---

### Check 7: Create Test Role Mapping

```bash
# Create simple role mapping for testing
curl -s -u elastic:Summer11 \
  -X POST "http://localhost:9200/_security/role_mapping/ram_users" \
  -H 'Content-Type: application/json' \
  -d '{
    "enabled": true,
    "roles": ["superuser"],
    "rules": {"all": []}
  }'

# Verify mapping created
curl -s -u elastic:Summer11 "http://localhost:9200/_security/role_mapping?pretty" | grep -A3 ram_users
```

---

## Quick Test: Create Lance Vector Index

```bash
# Create a simple test index with local Lance storage
curl -s -u elastic:Summer11 \
  -X PUT "http://localhost:9200/lance-test" \
  -H 'Content-Type: application/json' \
  -d '{
    "mappings": {
      "properties": {
        "embedding": {
          "type": "lance_vector",
          "dims": 128,
          "similarity": "cosine",
          "storage": {
            "type": "external",
            "uri": "file:///tmp/test-vectors.lance",
            "lance_id_column": "_id",
            "lance_vector_column": "vector",
            "read_only": true
          }
        }
      }
    }
  }'

# Verify index created
curl -s -u elastic:Summer11 "http://localhost:9200/lance-test?pretty"
```

**Expected**: Index created successfully (even if dataset doesn't exist yet)

---

## Quick Test: Verify Cloud IAM Authentication

```bash
# Generate signed header (requires Python)
python3 <<'PYTHON'
import sys
sys.path.append('plugins/security-realm-cloud-iam/tools')
import aliyun_sts_sign

# Use your RAM credentials
signed = aliyun_sts_sign.generate_signed_header(
    access_key_id="YOUR_RAM_ACCESS_KEY_ID",
    access_key_secret="YOUR_RAM_ACCESS_KEY_SECRET"
)
print(signed)
PYTHON

# Test authentication (replace SIGNED with actual signed header)
SIGNED="<your_signed_header>"

curl -s -H "X-ES-IAM-Signed: $SIGNED" \
  "http://localhost:9200/_security/_authenticate"
```

**Expected**: Authentication response with user ARN and roles

---

## Troubleshooting

### Issue: ES Won't Start

```bash
# Check logs
tail -100 logs/elasticsearch.log

# Common issues:
# 1. Port 9200 already in use
# 2. Java version wrong (need JDK 21)
# 3. Invalid JVM options
```

### Issue: Lance Plugin Not Loaded

```bash
# Verify plugin zip exists
ls -lh plugins/lance-vector/

# Should see: lance-vector-*.zip (~280MB)

# Reinstall if missing
cd /path/to/es-9.2.4-plugins
./gradlew :plugins:lance-vector:assemble
unzip -o plugins/lance-vector/build/distributions/lance-vector-*.zip \
  -d build/distribution/local/elasticsearch-*/plugins/
```

### Issue: OSS Credentials Not Recognized

```bash
# Verify environment variables are set
env | grep OSS

# Re-check in ES process
ES_PID=$(cat elasticsearch.pid)
cat /proc/$ES_PID/environ | tr '\0' '\n' | grep OSS

# If missing, restart ES with proper environment:
./bin/elasticsearch -d -p elasticsearch.pid
```

### Issue: Security Realm Not Loaded

```bash
# Check license status
curl -s "http://localhost:9200/_license" | jq '.license.type'

# Should be "trial" or "platinum"
# If "basic", start trial:
curl -s -X POST "http://localhost:9200/_license/start_trial?acknowledge=true"
```

---

## Production Deployment

For multi-node deployment with shared OSS storage:

```bash
# On each node:
# 1. Copy ES distribution
# 2. Configure JVM options (same as above)
# 3. Configure elasticsearch.yml (add cluster.name, discovery.seed_hosts)
# 4. Set OSS credentials (same across all nodes)
# 5. Start ES

# Example elasticsearch.yml additions:
cluster.name: lance-production
network.host: _eth0_
discovery.seed_hosts: ["node1-ip", "node2-ip", "node3-ip"]
cluster.initial_master_nodes: ["node1", "node2", "node3"]
```

---

## Summary Checklist

Before using ES for production:

- [ ] Both plugins loaded: `curl -s localhost:9200/_cat/plugins`
- [ ] OSS env vars in ES process: Check `/proc/$PID/environ`
- [ ] Security enabled: `curl -s localhost:9200/_xpack/security/features`
- [ ] Trial license active: `curl -s localhost:9200/_license | jq '.license.type'`
- [ ] Cloud IAM realm exists: `curl -s localhost:9200/_security/realm`
- [ ] Can create Lance index: `curl -X PUT localhost:9200/test-index`
- [ ] Memory leaks fixed: Check git log for "Fix memory leaks"

---

## Quick Commands Reference

```bash
# Start ES
./start_es_with_plugins.sh -d -p elasticsearch.pid

# Check if running
ps aux | grep elasticsearch

# Check logs
tail -f logs/elasticsearch.log

# Stop ES
kill $(cat elasticsearch.pid)

# Restart
kill $(cat elasticsearch.pid); ./start_es_with_plugins.sh -d -p elasticsearch.pid
```

---

**Last Updated**: 2026-01-27
**Plugins**: lance-vector + security-realm-cloud-iam
**ES Version**: 9.2.4-SNAPSHOT
