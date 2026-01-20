# RAM IAM E2E Validation (Elasticsearch + Kibana)

## Scope
Validate the Cloud IAM realm end to end: token parsing, STS verification, role mapping, cache/replay, and Kibana connectivity.

## Prereqs
- Aliyun RAM user with `sts:GetCallerIdentity` permission (v1 uses RAM User only).
- ES nodes can reach the configured STS endpoint.
- Time sync on client and ES nodes (NTP).
- Elasticsearch 8.17 or 9.x and Kibana of matching major version.
- Plugin ZIP built for the exact ES version.
- JDK for building: ES 9.x uses the toolchain in the repo; ES 8.17 works with JDK 21.

## Automated Run
Use the script at `docs/ram-e2e-validate.sh`. It runs ES, creates a file-realm admin, configures the realm, validates IAM auth, and starts a Kibana signing proxy.

Required environment variables:
```
ES_HOME=/path/to/elasticsearch
KIBANA_HOME=/path/to/kibana
RAM_AK=...
RAM_SK=...
```
Optional:
```
RAM_STS_TOKEN=...
RAM_ARN=acs:ram::123456789:user/iam-test
ES_VERSION=8.17.0
KIBANA_VERSION=8.17.0
AUTO_DOWNLOAD=1
IAM_ENDPOINT=https://sts.aliyuncs.com
IAM_REGION=cn-hangzhou
ALLOW_PLUGIN_INSTALL=1
KEEP_RUNNING=1
```

Run:
```
bash docs/ram-e2e-validate.sh
```

Notes:
- The script uses HTTP and disables TLS for local validation.
- It creates a broad role mapping (any RAM user -> superuser) unless `RAM_ARN` is set.

## Build the Plugin ZIP
### ES 9.x
```
./gradlew :plugins:security-realm-cloud-iam:bundlePlugin
```
ZIP: `plugins/security-realm-cloud-iam/build/distributions/security-realm-cloud-iam-*.zip`

### ES 8.17
Checkout the 8.17 branch of this repo and build with JDK 21:
```
./gradlew :plugins:security-realm-cloud-iam:bundlePlugin
```

## Install the Plugin
```
$ES_HOME/bin/elasticsearch-plugin install file:///path/to/security-realm-cloud-iam-*.zip
```
Restart Elasticsearch.

## Configure Elasticsearch
`elasticsearch.yml` (example):
```
xpack.security.enabled: true
xpack.security.authc.realms.cloud_iam.iam1:
  order: 0
  auth.mode: aliyun
  auth.signed_header: X-ES-IAM-Signed
  auth.allowed_time_skew: 5m
  replay.nonce_ttl: 5m
  replay.nonce_max_entries: 50000
  iam.endpoint: https://sts.aliyuncs.com
  iam.region: cn-hangzhou
  cache.ttl: 5m
  cache.negative_ttl: 20s
  role_mapping.enabled: true
```

## Role Mapping
```
PUT /_security/role_mapping/ram_kibana
{
  "enabled": true,
  "roles": [ "kibana_system", "superuser" ],
  "rules": { "field": { "metadata.cloud_arn": "acs:ram::123456789:user/iam-test" } }
}
```

## Generate a Signed Header
Use the helper:
```
python3 plugins/security-realm-cloud-iam/tools/aliyun_sts_sign.py \
  --access-key-id "$RAM_AK" \
  --access-key-secret "$RAM_SK"
```
Copy the output as the header value for `X-ES-IAM-Signed`.

## Elasticsearch Validation (Direct)
1) Auth success:
```
curl -s -H "X-ES-IAM-Signed: $SIGNED" http://localhost:9200/_security/_authenticate
```
Expect `metadata.cloud_arn` and mapped roles.

2) Auth failure (bad signature):
Use a wrong signature or stale timestamp; expect 401.

3) Replay protection:
Reuse the same header twice; the second should fail (nonce reuse or STS rejection).

4) Cache effectiveness:
Send multiple requests with new nonces and same AK; enable debug logs and confirm fewer STS calls.

## Kibana Validation (Single IAM Identity via Signing Proxy)
Kibana cannot generate per-request IAM signatures. For validation, run a tiny signing proxy and point Kibana to it. This makes Kibana operate as a single IAM identity.

### Start a Local Signing Proxy (HTTP only, test use)
Save as `/tmp/es_iam_proxy.py`:
```python
#!/usr/bin/env python3
import base64, hashlib, hmac, http.client, json, os, uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import quote, urlparse
from datetime import datetime, timezone

ES_URL = os.environ.get("ES_URL", "http://127.0.0.1:9200")
AK = os.environ["RAM_AK"]
SK = os.environ["RAM_SK"]
STS_TOKEN = os.environ.get("RAM_STS_TOKEN", "")

def percent_encode(s):
    return quote(s, safe="-_.~")

def sign(params):
    canonical = "&".join(f"{percent_encode(k)}={percent_encode(v)}" for k, v in sorted(params.items()))
    string_to_sign = "GET&%2F&" + percent_encode(canonical)
    mac = hmac.new((SK + "&").encode(), string_to_sign.encode(), hashlib.sha1).digest()
    return base64.b64encode(mac).decode()

def build_header():
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    params = {
        "Action": "GetCallerIdentity",
        "Version": "2015-04-01",
        "Format": "JSON",
        "AccessKeyId": AK,
        "SignatureMethod": "HMAC-SHA1",
        "SignatureVersion": "1.0",
        "SignatureNonce": str(uuid.uuid4()),
        "Timestamp": ts,
    }
    if STS_TOKEN:
        params["SecurityToken"] = STS_TOKEN
    params["Signature"] = sign(params)
    payload = json.dumps(params, separators=(",", ":"), sort_keys=True)
    return base64.b64encode(payload.encode()).decode()

class Proxy(BaseHTTPRequestHandler):
    def do_ANY(self):
        parsed = urlparse(ES_URL)
        conn = http.client.HTTPConnection(parsed.hostname, parsed.port or 80)
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        headers = {k: v for k, v in self.headers.items() if k.lower() != "host"}
        headers["X-ES-IAM-Signed"] = build_header()
        conn.request(self.command, self.path, body=body, headers=headers)
        resp = conn.getresponse()
        self.send_response(resp.status)
        for k, v in resp.getheaders():
            if k.lower() not in ("transfer-encoding", "connection"):
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(resp.read())

    def do_GET(self): self.do_ANY()
    def do_POST(self): self.do_ANY()
    def do_PUT(self): self.do_ANY()
    def do_DELETE(self): self.do_ANY()

HTTPServer(("0.0.0.0", 9201), Proxy).serve_forever()
```
Run:
```
RAM_AK=... RAM_SK=... ES_URL=http://127.0.0.1:9200 python3 /tmp/es_iam_proxy.py
```

### Configure Kibana
`kibana.yml`:
```
elasticsearch.hosts: [ "http://127.0.0.1:9201" ]
xpack.security.enabled: false
```
Start Kibana and verify:
- Kibana UI loads and Dev Tools works.
- ES audit log shows the IAM principal.

## Cleanup
Remove the plugin, delete the role mapping, and restore configs.
