# Environment Variable Setup for Lance OSS Integration

## Problem

The Lance Rust SDK (accessed via JNI from `lance-java`) reads OSS configuration from C/C++ `getenv()` function, **not** from Java's `System.getenv()`. This creates a challenge because:

1. Java's `System.getenv()` returns an immutable map
2. The native Lance code reads from the process environment (C `getenv()`)
3. Setting environment variables in Java doesn't affect the native process environment

## Current Implementation (High Risk)

The current `RealLanceDataset.setEnvIfChanged()` uses reflection to modify the internal `System.getenv()` map:

```java
@SuppressForbidden(reason = "...")
private static void setEnvIfChanged(String name, String value) {
    var env = System.getenv();
    var field = env.getClass().getDeclaredField("m");
    field.setAccessible(true);
    var writableEnv = (java.util.Map<String, String>) field.get(env);
    writableEnv.put(name, value);
}
```

### Problems with This Approach:
1. **Thread Safety**: The environment map modification is not synchronized
2. **JVM Version Dependency**: Relies on internal implementation details (`ProcessEnvironment.m`)
3. **Native Code Visibility**: Changes to Java's `System.getenv()` may not be visible to native `getenv()`
4. **Security Manager**: May be blocked in secure environments
5. **Unpredictable Timing**: Changes may not propagate to native code before Lance initialization

## Recommended Approach (Production-Ready)

### Option 1: Set Environment Variables Before ES Starts (RECOMMENDED)

Set environment variables in the parent shell **before** starting Elasticsearch. This is the only reliable way to ensure native code can read them.

```bash
#!/bin/bash
# start_es_with_oss.sh

# 1. Read credentials from ~/.oss/credentials.json
export OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' ~/.oss/credentials.json | cut -d'"' -f4)

# 2. Set OSS endpoint (match bucket region)
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

# 3. Start ES - it will inherit these environment variables
cd build/distribution/local/elasticsearch-9.2.4-SNAPSHOT
./bin/elasticsearch -d -p elasticsearch.pid

# 4. Verify environment variables are set
ES_PID=$(cat elasticsearch.pid)
cat /proc/$ES_PID/environ | tr '\0' '\n' | grep OSS
```

**Why This Works**:
- Environment variables set in the parent shell are inherited by child processes
- Native C/C++ code can read these via `getenv()`
- No reflection, no thread safety issues
- Works consistently across JVM versions

### Option 2: Use lance-java's Explicit Configuration (Future)

If future versions of `lance-java` support explicit configuration:

```java
// Hypothetical API - check lance-java documentation
StorageOptions options = new StorageOptions.Builder()
    .withEndpoint("oss-ap-southeast-1.aliyuncs.com")
    .withAccessKeyId("...")
    .withAccessKeySecret("...")
    .build();

Dataset.open(allocator, uri, ReadOptions.builder().storageOptions(options).build());
```

**Status**: Not available in current `lance-java` version (1.0.0-beta.2)

### Option 3: Secure Settings Plugin (Alternative)

Store credentials in Elasticsearch's secure settings and use a custom JNI shim:

```java
// Plugin reads from elasticsearch.keystore
String accessKeyId = secureSettings.getString("lance.oss.access_key_id");
String accessKeySecret = secureSettings.getString("lance.oss.access_key_secret");

// Call native shim to set C environment variables
NativeLib.setEnv("OSS_ACCESS_KEY_ID", accessKeyId);
NativeLib.setEnv("OSS_ACCESS_KEY_SECRET", accessKeySecret);
```

**Pros**:
- Credentials stored securely in keystore
- No reflection
- Native code sets actual C environment variables

**Cons**:
- Requires native library (JNI/JNA)
- Additional complexity

## Operational Procedures

### 1. Initial Setup

```bash
# Create credentials file
mkdir -p ~/.oss
cat > ~/.oss/credentials.json <<EOF
{
  "access_key_id": "LTAI5t...",
  "access_key_secret": "...",
  "endpoint": "oss-ap-southeast-1.aliyuncs.com"
}
EOF

chmod 600 ~/.oss/credentials.json
```

### 2. Start Elasticsearch

```bash
cd build/distribution/local/elasticsearch-9.2.4-SNAPSHOT
./start_es_with_oss.sh -d
```

### 3. Verify Environment Variables

```bash
# Check ES process environment
ES_PID=$(cat elasticsearch.pid)
cat /proc/$ES_PID/environ | tr '\0' '\n' | grep OSS

# Expected output:
# OSS_ENDPOINT=oss-ap-southeast-1.aliyuncs.com
# OSS_ACCESS_KEY_ID=LTAI5t...
# OSS_ACCESS_KEY_SECRET=...
```

### 4. Monitor for Issues

```bash
# Check logs for OSS-related errors
grep -i "oss\|lance" logs/elasticsearch.log

# Common issues:
# - "Failed to set environment variable" → Reflection blocked
# - "Authentication failed" → Incorrect credentials
# - "Connection timeout" → Wrong endpoint or network issue
```

## Security Considerations

### Credential Storage

1. **Never hardcode credentials** in source code or configuration files
2. **Use `.oss/credentials.json`** with proper file permissions (600)
3. **Don't log credentials** - the current implementation correctly masks sensitive values

### Process Security

1. **File permissions**: Ensure `~/.oss/credentials.json` is readable only by the ES user
2. **Environment variables**: Visible in `/proc/<pid>/environ` - ensure host security
3. **Audit logging**: Log dataset access patterns (but not credentials)

## Troubleshooting

### Issue: Reflection Blocked

**Symptoms**:
```
Failed to set environment variable OSS_ACCESS_KEY_ID: ...
```

**Solution**: Use Option 1 (pre-set environment variables)

### Issue: Credentials Not Found

**Symptoms**:
```
AuthenticationFailed: Access denied
```

**Solution**: Verify credentials file exists and has correct values:
```bash
cat ~/.oss/credentials.json | jq .
```

### Issue: Wrong Endpoint

**Symptoms**:
```
NoSuchBucket: The specified bucket does not exist
```

**Solution**: Match endpoint to bucket region:
```bash
# Singapore bucket
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

# Beijing bucket
export OSS_ENDPOINT="oss-cn-beijing.aliyuncs.com"
```

## Migration Path

### Short-Term (Current)
- Keep existing reflection code as fallback
- **Document** that environment variables should be set before ES starts
- Log warnings when reflection is used

### Medium-Term
- Add validation to check if native environment is set
- Provide clear error messages if credentials are missing
- Consider secure settings integration

### Long-Term
- Wait for `lance-java` to support explicit configuration
- Implement secure settings plugin for credential management
- Deprecate and remove reflection-based approach

## References

- CLAUDE.md: ES startup procedures with OSS
- OSS_INTEGRATION_GUIDE.md: OSS setup guide
- future_plan_refined_zh.md P0.1: Resource governance requirements
