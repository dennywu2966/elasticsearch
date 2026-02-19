#!/bin/bash
##############################################################################
# Project Starter Script for Kibana + ES with Lance Vector + Cloud IAM
#
# This script:
# 1. Optionally rebuilds ES plugins from scratch
# 2. Starts Elasticsearch with HTTPS, Lance Vector, and Cloud IAM
# 3. Starts Kibana with Aliyun OAuth support
#
# Requirements:
# - OSS credentials in ~/.oss/credentials.json
# - ES source code in ../es-9.2.4-plugins
# - Kibana configuration already set up
##############################################################################

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

##############################################################################
# Configuration
##############################################################################

# Paths
ES_DIR="../es-9.2.4-plugins"
ES_DIST_DIR="$ES_DIR/build/distribution/local/elasticsearch-9.2.4-SNAPSHOT"
KIBANA_DIR="."
KIBANA_PORT=5601
ES_PORT=9200
PUBLIC_BASE_URL="http://47.236.247.55:5601"

# OSS Configuration
OSS_CREDS_FILE="$HOME/.oss/credentials.json"
OSS_BUCKET="denny-test-lance"

# ES Configuration
ES_PASSWORD="Summer11"
ES_USER="elastic"

# Memory settings
KIBANA_MEMORY_GB=12

# Force rebuild flag
FORCE_REBUILD=false

##############################################################################
# Parse Arguments
##############################################################################

while [[ $# -gt 0 ]]; do
    case $1 in
        --rebuild)
            FORCE_REBUILD=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--rebuild]"
            echo "  --rebuild    Force rebuild ES plugins from scratch"
            exit 1
            ;;
    esac
done

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

print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  Kibana + ES Stack Starter${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

rebuild_es_plugins() {
    log_info "Rebuilding Elasticsearch plugins from scratch..."

    cd "$ES_DIR"

    log_info "Cleaning previous build artifacts..."
    rm -rf build/distribution/

    log_info "Building lance-vector plugin..."
    ./gradlew :plugins:lance-vector:assemble

    log_info "Building security-realm-cloud-iam plugin..."
    ./gradlew :plugins:security-realm-cloud-iam:assemble

    log_info "Building local distribution..."
    ./gradlew localDistro

    log_success "ES plugins rebuilt successfully!"
    echo "  - lance-vector"
    echo "  - security-realm-cloud-iam"

    # Return to original directory
    cd - > /dev/null
}

check_and_rebuild_plugins() {
    cd "$ES_DIR"

    # Check if plugin zips exist and are recent
    LANCE_ZIP="$ES_DIR/plugins/lance-vector/build/distributions/lance-vector-*.zip"
    CLOUD_IAM_ZIP="$ES_DIR/plugins/security-realm-cloud-iam/build/distributions/security-realm-cloud-iam-*.zip"

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

    # Get source file modification times as reference
    LANCE_SRC_MTIME=$(find "$ES_DIR/plugins/lance-vector/src" -type f -name "*.java" -printf "%T@\n" 2>/dev/null | sort -r | head -1)
    CLOUD_IAM_SRC_MTIME=$(find "$ES_DIR/plugins/security-realm-cloud-iam/src" -type f -name "*.java" -printf "%T@\n" 2>/dev/null | sort -r | head -1)

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
    cd "$ES_DIST_DIR"

    # Check if plugins are installed in the distribution
    LANCE_PLUGIN=$(ls plugins/lance-vector/ 2>/dev/null | wc -l)
    CLOUD_IAM_PLUGIN=$(ls plugins/security-realm-cloud-iam/ 2>/dev/null | wc -l)

    if [ "$LANCE_PLUGIN" -eq 0 ] || [ "$CLOUD_IAM_PLUGIN" -eq 0 ]; then
        log_warning "Plugins not found in ES distribution - reinstalling..."

        # Find plugin zips
        LANCE_ZIP=$(ls "$ES_DIR/plugins/lance-vector/build/distributions/lance-vector-*.zip" 2>/dev/null | head -1)
        CLOUD_IAM_ZIP=$(ls "$ES_DIR/plugins/security-realm-cloud-aim/build/distributions/security-realm-cloud-iam-*.zip" 2>/dev/null | head -1)

        if [ -z "$LANCE_ZIP" ]; then
            log_error "lance-vector plugin zip not found. Run with --rebuild first."
            exit 1
        fi

        if [ -z "$CLOUD_IAM_ZIP" ]; then
            log_error "security-realm-cloud-iam plugin zip not found. Run with --rebuild first."
            exit 1
        fi

        # Install plugins
        log_info "Installing lance-vector plugin..."
        unzip -q -o "$LANCE_ZIP" -d plugins/

        log_info "Installing security-realm-cloud-iam plugin..."
        unzip -q -o "$CLOUD_IAM_ZIP" -d plugins/

        log_success "Plugins installed successfully"
    fi

    cd - > /dev/null
}

check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check if ES directory exists
    if [ ! -d "$ES_DIR" ]; then
        log_error "Elasticsearch directory not found: $ES_DIR"
        exit 1
    fi

    # Check OSS credentials
    if [ ! -f "$OSS_CREDS_FILE" ]; then
        log_error "OSS credentials not found: $OSS_CREDS_FILE"
        echo "Create it with:"
        echo "  mkdir -p ~/.oss"
        echo '  echo {"access_key_id": "YOUR_KEY", "access_key_secret": "YOUR_SECRET", "endpoint": "...", "region": "..."}' > ~/.oss/credentials.json
        exit 1
    fi

    log_success "Prerequisites check passed"
}

start_elasticsearch() {
    log_info "Starting Elasticsearch..."

    cd "$ES_DIST_DIR"

    # Kill any existing ES on port 9200
    if fuser -k 9200/tcp 2>/dev/null; then
        log_warning "Killed existing process on port 9200"
        sleep 2
    fi

    # Extract OSS credentials
    OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' "$OSS_CREDS_FILE" | cut -d'"' -f4)
    OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' "$OSS_CREDS_FILE" | cut -d'"' -f4)
    OSS_REGION=$(grep '"region"' "$OSS_CREDS_FILE" | cut -d'"' -f4)
    OSS_ENDPOINT=$(grep '"endpoint"' "$OSS_CREDS_FILE" | cut -d'"' -f4)

    # Export OSS environment variables
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

    # Start ES in background
    ./bin/elasticsearch -d -p es.pid > /tmp/es_startup.log 2>&1 &

    log_success "Elasticsearch starting in background..."
    echo "  Logs: $ES_DIST_DIR/logs/elasticsearch.log"

    # Wait for ES to be ready
    log_info "Waiting for Elasticsearch to be ready..."
    for i in {1..60}; do
        if curl -sk -u "$ES_USER:$ES_PASSWORD" "https://127.0.0.1:$ES_PORT/" > /dev/null 2>&1; then
            log_success "Elasticsearch is ready!"

            # Verify plugins are loaded
            log_info "Verifying plugins are loaded..."
            sleep 2

            PLUGINS_OUTPUT=$(curl -sk -u "$ES_USER:$ES_PASSWORD" "https://127.0.0.1:$ES_PORT/_cat/plugins?v" 2>/dev/null)
            if echo "$PLUGINS_OUTPUT" | grep -q "lance-vector"; then
                log_success "✓ lance-vector plugin loaded"
            else
                log_error "✗ lance-vector plugin NOT loaded!"
                log_error "  You may need to rebuild ES with --rebuild flag"
            fi

            if echo "$PLUGINS_OUTPUT" | grep -q "cloud-iam"; then
                log_success "✓ security-realm-cloud-iam plugin loaded"
            else
                log_error "✗ security-realm-cloud-iam plugin NOT loaded!"
                log_error "  You may need to rebuild ES with --rebuild flag"
            fi

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

wait_for_kibana() {
    log_info "Waiting for Kibana to be ready..."

    for i in {1..120}; do
        if curl -s "http://localhost:$KIBANA_PORT/api/status" 2>/dev/null | grep -q "available"; then
            log_success "Kibana is ready!"
            return 0
        fi
        echo -n "."
        sleep 3
    done
    echo ""

    log_warning "Kibana taking longer than expected. Check console for progress."
    log_info "You can access Kibana at: $PUBLIC_BASE_URL"
}

verify_stack() {
    log_info "Verifying stack status..."

    # Check ES health
    ES_HEALTH=$(curl -sk -u "$ES_USER:$ES_PASSWORD" "https://127.0.0.1:$ES_PORT/_cluster/health?pretty" 2>/dev/null | grep '"status"' | cut -d'"' -f4)
    if [ "$ES_HEALTH" = "green" ]; then
        log_success "Elasticsearch: GREEN health"
    else
        log_warning "Elasticsearch: $ES_HEALTH health"
    fi

    # Check plugins
    PLUGINS=$(curl -sk -u "$ES_USER:$ES_PASSWORD" "https://127.0.0.1:$ES_PORT/_cat/plugins" 2>/dev/null | grep -E "lance-vector|cloud-iam" | wc -l)
    if [ "$PLUGINS" -ge 2 ]; then
        log_success "Plugins loaded: $PLUGINS (lance-vector + cloud-iam)"
    else
        log_warning "Only $PLUGINS plugins loaded - restart with --rebuild if needed"
    fi

    # Check Kibana
    KIBANA_STATUS=$(curl -s "http://localhost:$KIBANA_PORT/api/status" 2>/dev/null | grep -o '"level":"[^"]*"' | cut -d'"' -f4)
    if [ "$KIBANA_STATUS" = "available" ]; then
        log_success "Kibana: Available"
    else
        log_warning "Kibana: $KIBANA_STATUS"
    fi
}

print_access_info() {
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  Stack Ready!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "${BLUE}Access URLs:${NC}"
    echo "  Elasticsearch: https://127.0.0.1:$ES_PORT"
    echo "              Username: $ES_USER"
    echo "              Password: $ES_PASSWORD"
    echo ""
    echo "  Kibana:      $PUBLIC_BASE_URL"
    echo ""
    echo -e "${BLUE}Authentication Options:${NC}"
    echo "  1. Aliyun RAM OAuth (recommended)"
    echo "  2. Basic Auth (elastic / $ES_PASSWORD)"
    echo ""
    echo -e "${BLUE}To stop the stack:${NC}"
    echo "  # Stop ES"
    echo "  cd $ES_DIST_DIR"
    echo "  kill \$(cat es.pid)"
    echo ""
    echo "  # Stop Kibana"
    echo "  pkill -f 'node.*kibana'"
    echo ""
    echo -e "${BLUE}To rebuild ES plugins:${NC}"
    echo "  ./project-starter.sh --rebuild"
    echo ""
    echo -e "${BLUE}Logs:${NC}"
    echo "  ES:  $ES_DIST_DIR/logs/elasticsearch.log"
    echo "  Kibana: /tmp/kibana_startup.log"
    echo ""
}

##############################################################################
# Main Script
##############################################################################

print_header

# Save current directory
ORIGINAL_DIR=$(pwd)
trap "cd $ORIGINAL_DIR" EXIT

# Check and rebuild plugins if needed
check_and_rebuild_plugins

# Verify plugin installation in ES distribution
verify_es_installation

# Check prerequisites
check_prerequisites

# Start Elasticsearch
start_elasticsearch

# Start Kibana in background
log_info "Starting Kibana..."
cd "$ORIGINAL_DIR"

# Kill any existing Kibana
pkill -9 -f "node.*kibana" 2>/dev/null || true
sleep 2

# Start Kibana with all required parameters
NODE_OPTIONS="--max-old-space-size=$((KIBANA_MEMORY_GB * 1024))" \
    yarn start --dev --no-base-path > /tmp/kibana_startup.log 2>&1 &

log_success "Kibana starting in background..."
echo "  Memory: ${KIBANA_MEMORY_GB}GB"
echo "  Parameters: --dev --no-base-path"
echo "  Public URL: $PUBLIC_BASE_URL"

# Wait for Kibana and verify
wait_for_kibana

# Verify the complete stack
verify_stack

# Print access information
print_access_info

log_success "Stack startup complete!"
