#!/usr/bin/env bash
set -euo pipefail

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "error: missing command: $1" >&2; exit 1; }
}

die() {
  echo "error: $*" >&2
  exit 1
}

require_cmd curl
require_cmd python3

ES_HOME="${ES_HOME:-}"
KIBANA_HOME="${KIBANA_HOME:-}"
RAM_AK="${RAM_AK:-}"
RAM_SK="${RAM_SK:-}"
RAM_STS_TOKEN="${RAM_STS_TOKEN:-}"
RAM_ARN="${RAM_ARN:-}"

IAM_ENDPOINT="${IAM_ENDPOINT:-https://sts.aliyuncs.com}"
IAM_REGION="${IAM_REGION:-}"

WORK_DIR="${WORK_DIR:-$(pwd)/.ram-e2e}"
ES_VERSION="${ES_VERSION:-8.17.0}"
KIBANA_VERSION="${KIBANA_VERSION:-$ES_VERSION}"
AUTO_DOWNLOAD="${AUTO_DOWNLOAD:-1}"
ES_PORT="${ES_PORT:-9200}"
KIBANA_PORT="${KIBANA_PORT:-5601}"
PROXY_PORT="${PROXY_PORT:-9201}"
ES_URL="${ES_URL:-http://127.0.0.1:${ES_PORT}}"

PLUGIN_ZIP="${PLUGIN_ZIP:-}"
SKIP_BUILD="${SKIP_BUILD:-0}"
ALLOW_PLUGIN_INSTALL="${ALLOW_PLUGIN_INSTALL:-0}"
KEEP_RUNNING="${KEEP_RUNNING:-0}"
ALLOW_ASSUMED_ROLE="${ALLOW_ASSUMED_ROLE:-0}"

ADMIN_USER="${ADMIN_USER:-ram_admin}"
ADMIN_PASS="${ADMIN_PASS:-RamAdmin123!}"

ES_CONF="$WORK_DIR/es-config"
ES_DATA="$WORK_DIR/es-data"
ES_LOGS="$WORK_DIR/es-logs"
KBN_CONF="$WORK_DIR/kibana-config"
PROXY_SCRIPT="$WORK_DIR/es_iam_proxy.py"
DIST_DIR="$WORK_DIR/dist"

mkdir -p "$ES_CONF" "$ES_DATA" "$ES_LOGS" "$KBN_CONF" "$DIST_DIR"

abs_path() {
  local path="$1"
  if [ -d "$path" ]; then
    (cd "$path" && pwd)
  else
    (cd "$(dirname "$path")" && printf "%s/%s\n" "$(pwd)" "$(basename "$path")")
  fi
}

find_plugin_zip() {
  local found
  found=$(ls -1 plugins/security-realm-cloud-iam/build/distributions/security-realm-cloud-iam-*.zip 2>/dev/null | tail -n 1 || true)
  if [ -n "$found" ]; then
    echo "$found"
  fi
}

detect_platform() {
  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) echo "linux-x86_64" ;;
    aarch64|arm64) echo "linux-aarch64" ;;
    *) die "unsupported architecture: $arch" ;;
  esac
}

download_and_extract() {
  local name="$1"
  local version="$2"
  local platform="$3"
  local tarball="$DIST_DIR/${name}-${version}-${platform}.tar.gz"
  local url="https://artifacts.elastic.co/downloads/${name}/${name}-${version}-${platform}.tar.gz"
  if [ ! -f "$tarball" ]; then
    echo "downloading $url" >&2
    curl -fL "$url" -o "$tarball"
  fi
  tar -xzf "$tarball" -C "$DIST_DIR"
  echo "$DIST_DIR/${name}-${version}"
}

if [ -z "$ES_HOME" ]; then
  if [ "$AUTO_DOWNLOAD" = "1" ]; then
    platform="$(detect_platform)"
    ES_HOME="$(download_and_extract elasticsearch "$ES_VERSION" "$platform")"
  else
    die "set ES_HOME to your Elasticsearch install directory"
  fi
fi
if [ -z "$KIBANA_HOME" ]; then
  if [ "$AUTO_DOWNLOAD" = "1" ]; then
    platform="$(detect_platform)"
    KIBANA_HOME="$(download_and_extract kibana "$KIBANA_VERSION" "$platform")"
  else
    die "set KIBANA_HOME to your Kibana install directory"
  fi
fi
if [ ! -d "$ES_HOME" ]; then
  die "ES_HOME not found: $ES_HOME"
fi
if [ ! -d "$KIBANA_HOME" ]; then
  die "KIBANA_HOME not found: $KIBANA_HOME"
fi

fetch_ram_credentials() {
  local role
  role=$(curl -s --connect-timeout 1 http://100.100.100.200/latest/meta-data/ram/security-credentials/ || true)
  if [ -z "$role" ]; then
    return 1
  fi
  local payload
  payload=$(curl -s --connect-timeout 1 "http://100.100.100.200/latest/meta-data/ram/security-credentials/${role}" || true)
  if [ -z "$payload" ]; then
    return 1
  fi
  python3 - <<PY
import json, os, sys
data = json.loads("""$payload""")
ak = data.get("AccessKeyId")
sk = data.get("AccessKeySecret")
token = data.get("SecurityToken")
if not ak or not sk:
    sys.exit(1)
print(f"RAM_AK={ak}")
print(f"RAM_SK={sk}")
if token:
    print(f"RAM_STS_TOKEN={token}")
PY
}

if [ -z "$RAM_AK" ] || [ -z "$RAM_SK" ]; then
  echo "RAM_AK/RAM_SK not set; attempting to fetch ECS instance role credentials..."
  creds=$(fetch_ram_credentials || true)
  if [ -n "$creds" ]; then
    eval "$creds"
    if [ "$ALLOW_ASSUMED_ROLE" = "0" ]; then
      ALLOW_ASSUMED_ROLE="1"
    fi
  else
    die "set RAM_AK and RAM_SK (or run on ECS with instance RAM role)"
  fi
fi

if [ -z "$PLUGIN_ZIP" ]; then
  PLUGIN_ZIP="$(find_plugin_zip)"
fi
if [ -z "$PLUGIN_ZIP" ] && [ "$SKIP_BUILD" = "0" ]; then
  ./gradlew :plugins:security-realm-cloud-iam:bundlePlugin
  PLUGIN_ZIP="$(find_plugin_zip)"
fi
if [ -z "$PLUGIN_ZIP" ]; then
  die "plugin zip not found; set PLUGIN_ZIP or build with ./gradlew :plugins:security-realm-cloud-iam:bundlePlugin"
fi
PLUGIN_ZIP="$(abs_path "$PLUGIN_ZIP")"

if "$ES_HOME/bin/elasticsearch-plugin" list | grep -q "security-realm-cloud-iam"; then
  echo "plugin already installed"
else
  if [ "$ALLOW_PLUGIN_INSTALL" != "1" ]; then
    die "plugin not installed. re-run with ALLOW_PLUGIN_INSTALL=1 to install into $ES_HOME"
  fi
  "$ES_HOME/bin/elasticsearch-plugin" install --batch "file://${PLUGIN_ZIP}"
fi

cat > "$ES_CONF/elasticsearch.yml" <<EOF
cluster.name: ram-e2e
node.name: ram-e2e-1
path.data: ${ES_DATA}
path.logs: ${ES_LOGS}
discovery.type: single-node
xpack.security.enabled: true
xpack.security.autoconfiguration.enabled: false
xpack.security.http.ssl.enabled: false
xpack.security.transport.ssl.enabled: false
xpack.security.authc.realms.file.file1:
  order: 0
xpack.security.authc.realms.cloud_iam.iam1:
  order: 1
  auth.mode: aliyun
  auth.signed_header: X-ES-IAM-Signed
  auth.allowed_time_skew: 5m
  auth.allow_assumed_role: ${ALLOW_ASSUMED_ROLE}
  replay.nonce_ttl: 5m
  replay.nonce_max_entries: 50000
  iam.endpoint: ${IAM_ENDPOINT}
  iam.region: ${IAM_REGION}
  cache.ttl: 5m
  cache.negative_ttl: 20s
  role_mapping.enabled: true
EOF

export ES_PATH_CONF="$ES_CONF"
"$ES_HOME/bin/elasticsearch-users" useradd "$ADMIN_USER" -p "$ADMIN_PASS" -r superuser >/dev/null 2>&1 || true

ES_LOG="$WORK_DIR/es.out"
KBN_LOG="$WORK_DIR/kibana.out"
PROXY_LOG="$WORK_DIR/proxy.out"

cleanup() {
  if [ "$KEEP_RUNNING" = "1" ]; then
    return
  fi
  if [ -n "${KIBANA_PID:-}" ]; then
    kill "$KIBANA_PID" >/dev/null 2>&1 || true
  fi
  if [ -n "${PROXY_PID:-}" ]; then
    kill "$PROXY_PID" >/dev/null 2>&1 || true
  fi
  if [ -n "${ES_PID:-}" ]; then
    kill "$ES_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "starting Elasticsearch..."
"$ES_HOME/bin/elasticsearch" >"$ES_LOG" 2>&1 &
ES_PID=$!

echo "waiting for Elasticsearch..."
for _ in $(seq 1 120); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$ES_URL/" || true)
  if [ "$code" = "200" ] || [ "$code" = "401" ]; then
    break
  fi
  sleep 1
done

ROLE_RULE='{"field":{"metadata.cloud_principal_type":"user"}}'
if [ -n "$RAM_ARN" ]; then
  ROLE_RULE="{\"field\":{\"metadata.cloud_arn\":\"$RAM_ARN\"}}"
elif [ "$ALLOW_ASSUMED_ROLE" = "1" ]; then
  ROLE_RULE='{"any":[{"field":{"metadata.cloud_principal_type":"user"}},{"field":{"metadata.cloud_principal_type":"assumed_role"}}]}'
fi

echo "creating role mapping..."
curl -s -u "${ADMIN_USER}:${ADMIN_PASS}" \
  -H "Content-Type: application/json" \
  -X PUT "${ES_URL}/_security/role_mapping/ram_all_users" \
  -d "{\"enabled\":true,\"roles\":[\"superuser\"],\"rules\":${ROLE_RULE}}" >/dev/null

echo "generating signed header..."
SIGNED=$(python3 plugins/security-realm-cloud-iam/tools/aliyun_sts_sign.py \
  --access-key-id "$RAM_AK" \
  --access-key-secret "$RAM_SK" \
  ${RAM_STS_TOKEN:+--security-token "$RAM_STS_TOKEN"})

echo "validating IAM auth..."
curl -s -H "X-ES-IAM-Signed: ${SIGNED}" "${ES_URL}/_security/_authenticate"
echo

cat > "$PROXY_SCRIPT" <<'PY'
#!/usr/bin/env python3
import base64, hashlib, hmac, json, os, uuid
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
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        from http.client import HTTPConnection, HTTPSConnection
        conn = HTTPSConnection(parsed.hostname, port) if parsed.scheme == "https" else HTTPConnection(parsed.hostname, port)
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

HTTPServer(("0.0.0.0", int(os.environ.get("PROXY_PORT", "9201"))), Proxy).serve_forever()
PY

echo "starting IAM signing proxy..."
RAM_AK="$RAM_AK" RAM_SK="$RAM_SK" RAM_STS_TOKEN="$RAM_STS_TOKEN" ES_URL="$ES_URL" PROXY_PORT="$PROXY_PORT" \
  python3 "$PROXY_SCRIPT" >"$PROXY_LOG" 2>&1 &
PROXY_PID=$!

cat > "$KBN_CONF/kibana.yml" <<EOF
server.host: 127.0.0.1
server.port: ${KIBANA_PORT}
elasticsearch.hosts: [ "http://127.0.0.1:${PROXY_PORT}" ]
xpack.security.enabled: false
EOF

echo "starting Kibana..."
KBN_PATH_CONF="$KBN_CONF" "$KIBANA_HOME/bin/kibana" >"$KBN_LOG" 2>&1 &
KIBANA_PID=$!

echo "waiting for Kibana..."
for _ in $(seq 1 120); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${KIBANA_PORT}/api/status" || true)
  if [ "$code" = "200" ]; then
    break
  fi
  sleep 1
done

echo "Kibana is up on http://127.0.0.1:${KIBANA_PORT}"
echo "logs: $ES_LOG, $KBN_LOG, $PROXY_LOG"
