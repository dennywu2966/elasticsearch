#!/bin/bash
##############################################################################
# Project Starter Script for Elasticsearch with Lance Vector + Cloud IAM
#
# This script:
# 1. Optionally rebuilds ES plugins from scratch
# 2. Starts Elasticsearch with HTTPS, Lance Vector, and Cloud IAM
# 3. Verifies plugins are loaded and OSS is configured
#
# Features supported (after P1-P2-P3 merge):
# - P1-P2: NRT (Near Real-Time) refresh infrastructure
# - P3: Shard-aware dataset mapping with configurable ShardingStrategy
# - Filter support: pre-filter/post-filter/AUTO strategies
# - nprobes parameter control for search tuning
# - OSS integration for remote Lance datasets
# - Configurable sharding: NONE (no filtering) or ES_ROUTING (Murmur3 hash)
#
# Requirements:
# - OSS credentials in ~/.oss/credentials.json
# - Built from source in this repository
#
# Usage:
#   ./project_starter.sh [--rebuild] [-d] [--jvm-heap SIZE]
#
# Options:
#   --rebuild       Force rebuild ES plugins from scratch
#   -d              Start ES in daemon mode (default: foreground)
#   --jvm-heap SIZE Set JVM heap size (default: 4g, format: Xm or Xg)
#   --no-oss        Start without OSS environment variables
#
# Examples:
#   ./project_starter.sh              # Start with 4GB heap in foreground
#   ./project_starter.sh -d           # Start in daemon mode
#   ./project_starter.sh --jvm-heap 8g  # Start with 8GB heap
#   ./project_starter.sh --rebuild    # Rebuild and start
##############################################################################

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

##############################################################################
# Configuration
##############################################################################

# Paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ES_DIST_DIR="$SCRIPT_DIR/build/distribution/local/elasticsearch-9.2.4-SNAPSHOT"
ES_PORT=9200

# OSS Configuration
OSS_CREDS_FILE="$HOME/.oss/credentials.json"
OSS_BUCKET="denny-test-lance"

# ES Configuration
ES_PASSWORD="Summer11"
ES_USER="elastic"

# JVM Configuration (4GB default for production-like testing)
# Format: Xm or Xg (e.g., 4g, 8g, 16g)
DEFAULT_JVM_HEAP="4g"

# Force rebuild flag
FORCE_REBUILD=false
DAEMON_MODE=false
SETUP_OSS=true
JVM_HEAP="$DEFAULT_JVM_HEAP"

##############################################################################
# Parse Arguments
##############################################################################

print_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --rebuild           Force rebuild ES plugins from scratch"
    echo "  -d                  Start ES in daemon mode (default: foreground)"
    echo "  --jvm-heap SIZE     Set JVM heap size (default: $DEFAULT_JVM_HEAP, format: Xm or Xg)"
    echo "  --no-oss            Start without OSS environment variables"
    echo "  -h, --help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                           # Start with 4GB heap in foreground"
    echo "  $0 -d                        # Start in daemon mode"
    echo "  $0 --jvm-heap 8g             # Start with 8GB heap"
    echo "  $0 --rebuild -d              # Rebuild and start in daemon mode"
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --rebuild)
            FORCE_REBUILD=true
            shift
            ;;
        -d)
            DAEMON_MODE=true
            shift
            ;;
        --jvm-heap)
            JVM_HEAP="$2"
            shift 2
            ;;
        --no-oss)
            SETUP_OSS=false
            shift
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            print_usage
            exit 1
            ;;
    esac
done

# Validate JVM heap format
if [[ ! "$JVM_HEAP" =~ ^[0-9]+[mgMG] ]]; then
    echo -e "${RED}Error: Invalid JVM heap format: $JVM_HEAP${NC}"
    echo "Expected format: Xm or Xg (e.g., 4g, 8g, 16g, 512m)"
    exit 1
fi

##############################################################################
# Functions
##############################################################################

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_feature() {
    echo -e "${CYAN}[FEATURE]${NC} $1"
}

print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  Elasticsearch Starter${NC}"
    echo -e "${BLUE}  Lance Vector + Cloud IAM${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
    echo -e "${CYAN}Features:${NC}"
    echo "  • P1-P2: NRT (Near Real-Time) refresh infrastructure"
    echo "  • P3: Shard-aware dataset mapping"
    echo "  • Filter support: pre/post-filter strategies"
    echo "  • nprobes parameter control"
    echo "  • OSS integration for remote datasets"
    echo ""
    echo -e "${CYAN}Configuration:${NC}"
    echo "  JVM Heap: ${JVM_HEAP}"
    echo "  OSS: $([ "$SETUP_OSS" = true ] && echo 'Enabled' || echo 'Disabled')"
    echo "  Mode: $([ "$DAEMON_MODE" = true ] && echo 'Daemon (background)' || echo 'Foreground')"
    echo ""
}

rebuild_es_plugins() {
    log_info "Rebuilding Elasticsearch plugins from scratch..."

    cd "$SCRIPT_DIR"

    # Backup the data directory to preserve indices
    if [ -d "$ES_DIST_DIR/data" ]; then
        log_info "Backing up data directory to preserve indices..."
        DATA_BACKUP_DIR="/tmp/es-data-backup-$(date +%s)"
        cp -r "$ES_DIST_DIR/data" "$DATA_BACKUP_DIR/"
        log_success "Data backed up to: $DATA_BACKUP_DIR"
    fi

    log_info "Cleaning previous build artifacts..."
    # Only remove the build directory, not the data
    rm -rf build/distribution/

    log_info "Building lance-vector plugin..."
    ./gradlew :plugins:lance-vector:assemble

    log_info "Building security-realm-cloud-iam plugin..."
    ./gradlew :plugins:security-realm-cloud-iam:assemble

    log_info "Building local distribution..."
    ./gradlew localDistro

    log_info "Installing plugins to distribution..."
    # Install plugins manually since localDistro doesn't do it
    # Each plugin needs its own subdirectory
    mkdir -p "$ES_DIST_DIR/plugins/lance-vector"
    mkdir -p "$ES_DIST_DIR/plugins/security-realm-cloud-iam"

    # Use find instead of ls to handle glob patterns better
    LANCE_ZIP=$(find "$SCRIPT_DIR/plugins/lance-vector/build/distributions/" -name "lance-vector-*.zip" -type f 2>/dev/null | head -1)
    CLOUD_IAM_ZIP=$(find "$SCRIPT_DIR/plugins/security-realm-cloud-iam/build/distributions/" -name "security-realm-cloud-iam-*.zip" -type f 2>/dev/null | head -1)

    if [ -n "$LANCE_ZIP" ]; then
        unzip -q -o "$LANCE_ZIP" -d "$ES_DIST_DIR/plugins/lance-vector/"
        log_success "lance-vector plugin installed"
    fi

    if [ -n "$CLOUD_IAM_ZIP" ]; then
        unzip -q -o "$CLOUD_IAM_ZIP" -d "$ES_DIST_DIR/plugins/security-realm-cloud-iam/"
        log_success "security-realm-cloud-iam plugin installed"
    fi

    log_success "ES plugins rebuilt successfully!"
    echo "  - lance-vector (with P1-P2-P3 features)"
    echo "  - security-realm-cloud-iam"

    # Restore the data directory if it was backed up
    if [ -n "$DATA_BACKUP_DIR" ] && [ -d "$DATA_BACKUP_DIR" ]; then
        log_info "Restoring data directory from backup..."
        mkdir -p "$ES_DIST_DIR/data"
        cp -r "$DATA_BACKUP_DIR/"* "$ES_DIST_DIR/data/"
        log_success "Data directory restored - indices preserved!"
        rm -rf "$DATA_BACKUP_DIR"
    fi

    cd - > /dev/null
}

check_and_rebuild_plugins() {
    cd "$SCRIPT_DIR"

    # Check if plugin zips exist and are recent
    LANCE_ZIP="$SCRIPT_DIR/plugins/lance-vector/build/distributions/lance-vector-*.zip"
    CLOUD_IAM_ZIP="$SCRIPT_DIR/plugins/security-realm-cloud-iam/build/distributions/security-realm-cloud-iam-*.zip"

    # Get modification times
    if ls $LANCE_ZIP 2>/dev/null; then
        LANCE_MTIME=$(ls -t $LANCE_ZIP | head -1 | xargs stat -c %Y)
    else
        LANCE_MTIME=0
    fi

    if ls $CLOUD_IAM_ZIP 2>/dev/null; then
        CLOUD_IAM_MTIME=$(ls -t $CLOUD_IAM_ZIP | head -1 | xargs stat -c %Y)
    else
        CLOUD_IAM_MTIME=0
    fi

    # Get source file modification times as reference (integer seconds)
    LANCE_SRC_MTIME=$(find "$SCRIPT_DIR/plugins/lance-vector/src" -type f -name "*.java" -printf "%T@\n" 2>/dev/null | sort -r | head -1 | cut -d. -f1)
    CLOUD_IAM_SRC_MTIME=$(find "$SCRIPT_DIR/plugins/security-realm-cloud-iam/src" -type f -name "*.java" -printf "%T@\n" 2>/dev/null | sort -r | head -1 | cut -d. -f1)

    cd - > /dev/null

    REBUILD_NEEDED=false

    if [ "$FORCE_REBUILD" = true ]; then
        log_info "Force rebuild requested"
        REBUILD_NEEDED=true
    elif [ ! -d "$ES_DIST_DIR" ]; then
        log_warning "ES distribution not found - rebuild required"
        REBUILD_NEEDED=true
    elif [ -z "$LANCE_SRC_MTIME" ] || [ -z "$CLOUD_IAM_SRC_MTIME" ]; then
        log_warning "Cannot determine plugin source ages - skipping rebuild check"
    else
        # Compare plugin zip times with source times
        if [ "$LANCE_MTIME" -lt "$LANCE_SRC_MTIME" ]; then
            log_warning "lance-vector plugin is older than source - rebuild recommended"
            REBUILD_NEEDED=true
        fi
        if [ "$CLOUD_IAM_MTIME" -lt "$CLOUD_IAM_SRC_MTIME" ]; then
            log_warning "cloud-iam plugin is older than source - rebuild recommended"
            REBUILD_NEEDED=true
        fi
    fi

    if [ "$REBUILD_NEEDED" = true ]; then
        rebuild_es_plugins
    else
        log_info "ES plugins are up to date - skipping rebuild"
        log_info "Use --rebuild flag to force rebuild"
    fi
}

verify_es_installation() {
    # Check if plugins are installed in the distribution
    LANCE_INSTALLED=false
    CLOUD_IAM_INSTALLED=false

    # Check for lance-vector plugin (by descriptor file in subdirectory)
    if [ -f "$ES_DIST_DIR/plugins/lance-vector/plugin-descriptor.properties" ]; then
        LANCE_INSTALLED=true
    fi

    # Check for security-realm-cloud-iam plugin (by jar file in subdirectory)
    if ls "$ES_DIST_DIR/plugins/security-realm-cloud-iam/"*.jar 1>/dev/null 2>&1; then
        CLOUD_IAM_INSTALLED=true
    fi

    if [ "$LANCE_INSTALLED" = false ] || [ "$CLOUD_IAM_INSTALLED" = false ]; then
        log_warning "Plugins not found in ES distribution - installing..."

        # Find plugin zips
        LANCE_ZIP=$(find "$SCRIPT_DIR/plugins/lance-vector/build/distributions/" -name "lance-vector-*.zip" -type f 2>/dev/null | head -1)
        CLOUD_IAM_ZIP=$(find "$SCRIPT_DIR/plugins/security-realm-cloud-iam/build/distributions/" -name "security-realm-cloud-iam-*.zip" -type f 2>/dev/null | head -1)

        if [ "$LANCE_INSTALLED" = false ]; then
            if [ -n "$LANCE_ZIP" ]; then
                log_info "Installing lance-vector plugin..."
                mkdir -p "$ES_DIST_DIR/plugins/lance-vector"
                unzip -q -o "$LANCE_ZIP" -d "$ES_DIST_DIR/plugins/lance-vector/"
                log_success "lance-vector plugin installed"
            else
                log_error "lance-vector plugin zip not found. Run with --rebuild first."
                exit 1
            fi
        fi

        if [ "$CLOUD_IAM_INSTALLED" = false ]; then
            if [ -n "$CLOUD_IAM_ZIP" ]; then
                log_info "Installing security-realm-cloud-iam plugin..."
                mkdir -p "$ES_DIST_DIR/plugins/security-realm-cloud-iam"
                unzip -q -o "$CLOUD_IAM_ZIP" -d "$ES_DIST_DIR/plugins/security-realm-cloud-iam/"
                log_success "security-realm-cloud-iam plugin installed"
            else
                log_error "security-realm-cloud-iam plugin zip not found. Run with --rebuild first."
                exit 1
            fi
        fi
    fi
}

check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check if ES distribution exists
    if [ ! -d "$ES_DIST_DIR" ]; then
        log_error "Elasticsearch distribution not found: $ES_DIST_DIR"
        echo "Run with --rebuild to build from source"
        exit 1
    fi

    # Check OSS credentials (only if OSS is enabled)
    if [ "$SETUP_OSS" = true ] && [ ! -f "$OSS_CREDS_FILE" ]; then
        log_error "OSS credentials not found: $OSS_CREDS_FILE"
        echo "Create it with:"
        echo "  mkdir -p ~/.oss"
        echo '  echo '\''{"access_key_id": "YOUR_KEY", "access_key_secret": "YOUR_SECRET", "endpoint": "oss-ap-southeast-1.aliyuncs.com", "region": "ap-southeast-1"}'\'' > ~/.oss/credentials.json'
        echo ""
        echo "Or start without OSS: ./project_starter.sh --no-oss"
        exit 1
    fi

    log_success "Prerequisites check passed"
}

stop_existing_es() {
    # Kill any existing ES on port 9200
    if fuser -k 9200/tcp 2>/dev/null; then
        log_warning "Killed existing process on port 9200"
        sleep 2
    fi
}

configure_jvm_options() {
    # Create JVM options file for custom heap size and Arrow memory access
    JVM_OPTIONS_DIR="$ES_DIST_DIR/config/jvm.options.d"
    JVM_OPTIONS_FILE="$JVM_OPTIONS_DIR/custom-heap.options"

    mkdir -p "$JVM_OPTIONS_DIR"

    # Clear existing custom heap settings
    rm -f "$JVM_OPTIONS_FILE"

    # Set heap size
    echo "-Xms${JVM_HEAP}" > "$JVM_OPTIONS_FILE"
    echo "-Xmx${JVM_HEAP}" >> "$JVM_OPTIONS_FILE"

    # Add Arrow memory access option (REQUIRED for Lance/Arrow)
    echo "--add-opens=java.base/java.nio=ALL-UNNAMED" >> "$JVM_OPTIONS_FILE"

    log_info "JVM heap configured: ${JVM_HEAP}"
    log_info "Arrow memory access option added"
    echo "  Settings file: $JVM_OPTIONS_FILE"
}

start_elasticsearch() {
    cd "$ES_DIST_DIR"

    if [ "$SETUP_OSS" = true ]; then
        # Extract OSS credentials
        OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' "$OSS_CREDS_FILE" | cut -d'"' -f4)
        OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' "$OSS_CREDS_FILE" | cut -d'"' -f4)
        OSS_REGION=$(grep '"region"' "$OSS_CREDS_FILE" | cut -d'"' -f4)
        OSS_ENDPOINT=$(grep '"endpoint"' "$OSS_CREDS_FILE" | cut -d'"' -f4)
        OSS_BUCKET_NAME=$(grep '"bucket_name"' "$OSS_CREDS_FILE" | cut -d'"' -f4)

        # Use bucket_name from credentials if available, otherwise default
        if [ -n "$OSS_BUCKET_NAME" ]; then
            OSS_BUCKET="$OSS_BUCKET_NAME"
        fi

        # Export OSS environment variables BEFORE starting ES
        # This is CRITICAL for the native Lance Rust code
        export OSS_ACCESS_KEY_ID
        export OSS_ACCESS_KEY_SECRET
        export OSS_REGION
        export OSS_ENDPOINT
        export OSS_BUCKET

        log_info "OSS Configuration:"
        echo "  Endpoint: $OSS_ENDPOINT"
        echo "  Region: $OSS_REGION"
        echo "  Bucket: $OSS_BUCKET"
        echo "  AK: ${OSS_ACCESS_KEY_ID:0:8}..."
    else
        log_warning "Starting without OSS environment variables"
    fi

    if [ "$DAEMON_MODE" = true ]; then
        log_info "Starting Elasticsearch in daemon mode..."
        ./bin/elasticsearch -d -p es.pid > /tmp/es_startup.log 2>&1 &
        log_success "Elasticsearch starting in background..."
        echo "  Logs: $ES_DIST_DIR/logs/elasticsearch.log"
        echo "  PID file: $ES_DIST_DIR/es.pid"
    else
        log_info "Starting Elasticsearch in foreground mode..."
        log_info "Press Ctrl+C to stop"
        exec ./bin/elasticsearch
    fi
}

reset_elastic_password() {
    log_info "Resetting elastic user password to Summer11..."

    cd "$ES_DIST_DIR"

    # Use the bulk API to reset password (more reliable than the reset-password tool)
    # First, try to get the auto-generated password from logs
    AUTO_PASSWORD=$(grep "successfully reset" "$ES_DIST_DIR/logs/elasticsearch.log" 2>/dev/null | grep "elastic" | tail -1 | sed 's/.*New value: //' || echo "")

    if [ -n "$AUTO_PASSWORD" ]; then
        # Use the auto-generated password to reset to Summer11
        HTTP_CODE=$(curl -sk -w "%{http_code}" -u "elastic:$AUTO_PASSWORD" -X POST "https://127.0.0.1:$ES_PORT/_security/user/elastic/_password" \
            -H "Content-Type: application/json" \
            -d "{\"password\":\"$ES_PASSWORD\"}" \
            -o /dev/null 2>&1)

        if [ "$HTTP_CODE" = "200" ]; then
            log_success "Password reset to Summer11 using auto-generated password"
            return 0
        fi
    fi

    # If that failed, try using the reset-password tool with -b (batch mode)
    if ./bin/elasticsearch-reset-password -u elastic -b <<EOF 2>&1 | grep -q "successfully reset"; then
$ES_PASSWORD
$ES_PASSWORD
EOF
        log_success "Password reset to Summer11 using reset-password tool"
        return 0
    else
        log_warning "Failed to auto-reset password. Manual reset required:"
        echo "  cd $ES_DIST_DIR"
        echo "  ./bin/elasticsearch-reset-password -u elastic -b"
        echo "  Then enter: $ES_PASSWORD"
        return 1
    fi
}

wait_for_elasticsearch() {
    log_info "Waiting for Elasticsearch to be ready..."

    for i in {1..60}; do
        if curl -sk "https://127.0.0.1:$ES_PORT/" > /dev/null 2>&1; then
            log_success "Elasticsearch is ready!"

            # Verify plugins are loaded from logs (more reliable than API with unknown password)
            log_info "Verifying plugins are loaded..."
            sleep 2

            if grep -q "loaded plugin \[lance-vector\]" "$ES_DIST_DIR/logs/elasticsearch.log" 2>/dev/null; then
                log_success "lance-vector plugin loaded"
                log_feature "  P1-P2: NRT refresh infrastructure available"
                log_feature "  P3: Shard-aware dataset mapping available"
            else
                log_error "lance-vector plugin NOT loaded!"
                log_error "  You may need to rebuild ES with --rebuild flag"
            fi

            if grep -q "loaded plugin \[security-realm-cloud-iam\]" "$ES_DIST_DIR/logs/elasticsearch.log" 2>/dev/null; then
                log_success "security-realm-cloud-iam plugin loaded"
            else
                log_error "security-realm-cloud-iam plugin NOT loaded!"
                log_error "  You may need to rebuild ES with --rebuild flag"
            fi

            # Reset password to Summer11
            reset_elastic_password

            return 0
        fi
        echo -n "."
        sleep 2
    done
    echo ""

    log_error "Elasticsearch failed to start. Check logs:"
    echo "  tail -50 $ES_DIST_DIR/logs/elasticsearch.log"
    exit 1
}

verify_stack() {
    log_info "Verifying stack status..."

    # Check ES health via logs (more reliable when password is unknown)
    if grep -q "started" "$ES_DIST_DIR/logs/elasticsearch.log" 2>/dev/null; then
        log_success "Elasticsearch: Started and ready"
    else
        log_warning "Elasticsearch: May not be fully started"
    fi

    # Check plugins from logs
    PLUGINS_LOADED=0
    if grep -q "loaded plugin \[lance-vector\]" "$ES_DIST_DIR/logs/elasticsearch.log" 2>/dev/null; then
        PLUGINS_LOADED=$((PLUGINS_LOADED + 1))
    fi
    if grep -q "loaded plugin \[security-realm-cloud-iam\]" "$ES_DIST_DIR/logs/elasticsearch.log" 2>/dev/null; then
        PLUGINS_LOADED=$((PLUGINS_LOADED + 1))
    fi

    if [ "$PLUGINS_LOADED" -ge 2 ]; then
        log_success "Plugins loaded: $PLUGINS_LOADED/2 (lance-vector + cloud-iam)"
    else
        log_warning "Only $PLUGINS_LOADED/2 plugins loaded - check logs"
    fi

    # Verify cluster settings for NRT refresh
    if grep -q "started" "$ES_DIST_DIR/logs/elasticsearch.log" 2>/dev/null; then
        log_info "NRT Refresh Settings:"
        echo "  Cluster settings available via API:"
        echo "  GET _cluster/settings?filter_path=*.lance.refresh.*"
        echo ""
        echo "  Index settings available via API:"
        echo "  GET {index}/_settings?filter_path=*.lance.refresh.*"
    fi
}

print_access_info() {
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  Elasticsearch Ready!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "${BLUE}Access URLs:${NC}"
    echo "  Elasticsearch: https://127.0.0.1:$ES_PORT"
    echo "              Username: $ES_USER"
    echo "              Password: $ES_PASSWORD"
    echo ""
    echo -e "${CYAN}Features Available:${NC}"
    echo "  • Lance Vector kNN search"
    echo "  • P1-P2: NRT refresh infrastructure"
    echo "  • P3: Shard-aware dataset mapping"
    echo "  • Filter support (pre/post-filter/AUTO)"
    echo "  • nprobes parameter control"
    echo "  • OSS integration for remote datasets"
    echo "  • Aliyun RAM OAuth authentication"
    echo ""
    echo -e "${BLUE}Configure NRT refresh (cluster level):${NC}"
    echo "  PUT _cluster/settings"
    echo '  {"persistent": {"lance.refresh.enabled": true, "lance.refresh.interval": "5s"}}'
    echo ""
    echo -e "${BLUE}Configure shard-aware storage (index level):${NC}"
    echo '  PUT {index}'
    echo '  {"mappings": {"properties": {"embedding": {"type": "lance_vector",'
    echo '    "storage": {"type": "external", "uri_prefix": "oss://bucket/",'
    echo '    "shard_path": "data/{index}/shard-{shard_id}", "dataset_name": "vectors.lance",'
    echo '    "sharding_strategy": "ES_ROUTING"}}}}}'
    echo ""
    echo -e "${CYAN}  Sharding Strategy Options:${NC}"
    echo "  • ES_ROUTING (default for shard-aware): Filter using ES's Murmur3 hash"
    echo "  • NONE (default for legacy mode): No candidate filtering"
    echo -e "${BLUE}Authentication Options:${NC}"
    echo "  1. Aliyun RAM OAuth (if configured in elasticsearch.yml)"
    echo "  2. Basic Auth with password: $ES_PASSWORD"
    echo ""
    echo -e "${BLUE}To stop Elasticsearch:${NC}"
    echo "  cd $ES_DIST_DIR"
    printf '  kill $(cat es.pid)\n'
    echo ""
    echo -e "${BLUE}To restart with different JVM heap:${NC}"
    echo "  ./project_starter.sh --jvm-heap 8g -d"
    echo ""
    echo -e "${BLUE}To rebuild ES plugins (preserves data):${NC}"
    echo "  ./project_starter.sh --rebuild"
    echo ""
    echo -e "${BLUE}Logs:${NC}"
    echo "  ES:  $ES_DIST_DIR/logs/elasticsearch.log"
    echo ""
}

##############################################################################
# Main Script
##############################################################################

print_header

# Save current directory
ORIGINAL_DIR=$(pwd)

# Check and rebuild plugins if needed
check_and_rebuild_plugins

# Verify plugin installation in ES distribution
verify_es_installation

# Check prerequisites
check_prerequisites

# Stop any existing ES
stop_existing_es

# Configure JVM options (4GB default or custom)
configure_jvm_options

# Start Elasticsearch
if [ "$DAEMON_MODE" = true ]; then
    start_elasticsearch
    cd "$ORIGINAL_DIR"
    wait_for_elasticsearch
    verify_stack
    print_access_info
    log_success "Elasticsearch startup complete!"
else
    # Foreground mode - never returns
    start_elasticsearch
fi
