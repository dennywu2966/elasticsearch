#!/bin/bash
# Start ES with Lance Vector Plugin and OSS credentials for 7-day soak test
# This script should be run in a fresh shell session

set -euo pipefail

# Configuration
ES_HOME="/home/denny/projects/es-9.2.4-plugins-real-time/build/distribution/local/elasticsearch-9.2.4-SNAPSHOT"
CREDENTIALS_FILE="$HOME/.oss/credentials.json"
OUTPUT_DIR="/tmp/lance-soak-$(date +%Y%m%d)"
DURATION_DAYS=7

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log() {
    echo -e "${GREEN}[$(date '+%Y-%m-%d %H:%M:%S')]${NC} $*"
}

error() {
    echo -e "${RED}[ERROR]${NC} $*" >&2
    exit 1
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

# Check prerequisites
check_prerequisites() {
    log "Checking prerequisites..."

    # Check if ES distribution exists
    if [[ ! -d "$ES_HOME" ]]; then
        error "ES distribution not found at $ES_HOME. Run ./gradlew localDistro first."
    fi

    # Check if credentials file exists
    if [[ ! -f "$CREDENTIALS_FILE" ]]; then
        error "Credentials file not found at $CREDENTIALS_FILE"
    fi

    # Check if plugin is installed
    if [[ ! -d "$ES_HOME/plugins/lance-vector" ]]; then
        error "Lance Vector plugin not installed. Extract the plugin first."
    fi

    log "Prerequisites check passed ✓"
}

# Stop any existing ES process
stop_existing_es() {
    if [[ -f "$ES_HOME/elasticsearch.pid" ]]; then
        local pid=$(cat "$ES_HOME/elasticsearch.pid")
        if ps -p "$pid" > /dev/null 2>&1; then
            log "Stopping existing ES process (PID: $pid)..."
            kill "$pid"
            sleep 5
            if ps -p "$pid" > /dev/null 2>&1; then
                warn "ES did not stop gracefully, forcing..."
                kill -9 "$pid"
                sleep 2
            fi
        fi
        rm -f "$ES_HOME/elasticsearch.pid"
    fi
}

# Set OSS credentials from file
set_oss_credentials() {
    log "Setting OSS credentials..."

    export OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' "$CREDENTIALS_FILE" | cut -d'"' -f4)
    export OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' "$CREDENTIALS_FILE" | cut -d'"' -f4)
    export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

    if [[ -z "$OSS_ACCESS_KEY_ID" || -z "$OSS_ACCESS_KEY_SECRET" ]]; then
        error "Failed to extract OSS credentials from $CREDENTIALS_FILE"
    fi

    log "OSS credentials set: ACCESS_KEY_ID=${OSS_ACCESS_KEY_ID:0:10}... ENDPOINT=$OSS_ENDPOINT"
}

# Start ES with OSS credentials
start_elasticsearch() {
    log "Starting Elasticsearch..."

    cd "$ES_HOME"

    # Start ES in background (credentials are already exported in this shell)
    ./bin/elasticsearch -d -p elasticsearch.pid

    local pid=$(cat elasticsearch.pid)
    log "ES started with PID: $pid"

    # Wait for ES to be ready
    log "Waiting for ES to become ready (this may take 30-60 seconds)..."
    local max_wait=60
    local waited=0

    while [[ $waited -lt $max_wait ]]; do
        if curl -k -s -u elastic:Summer11 https://localhost:9200/_cluster/health > /dev/null 2>&1; then
            log "ES is ready! ✓"
            return 0
        fi
        sleep 1
        ((waited++))
        echo -n "."
    done

    echo ""
    error "ES did not become ready within ${max_wait}s"
}

# Create test index with Lance vector field
create_test_index() {
    log "Creating test index..."

    # First, check if index exists
    if curl -k -s -u elastic:Summer11 "https://localhost:9200/test-soak" > /dev/null 2>&1; then
        log "Index 'test-soak' already exists, skipping creation"
        return 0
    fi

    # Create index with Lance vector mapping
    curl -k -s -u elastic:Summer11 -X PUT "https://localhost:9200/test-soak" \
        -H "Content-Type: application/json" -d '
    {
      "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 0
      },
      "mappings": {
        "properties": {
          "content": { "type": "text" },
          "embedding": {
            "type": "lance_vector",
            "dims": 128,
            "storage": {
              "uri": "oss://denny-test-lance/soak-test/data.lance"
            }
          }
        }
      }
    }'

    if [[ $? -eq 0 ]]; then
        log "Test index created successfully ✓"
    else
        error "Failed to create test index"
    fi
}

# Index some test documents
index_test_documents() {
    log "Indexing test documents..."

    for i in {1..100}; do
        # Generate a simple vector (all 0.1 for testing)
        VECTOR="[$(printf '0.1, '; seq 2 127 | xargs -I{} printf '0.1, ')0.1]"

        curl -k -s -u elastic:Summer11 -X POST "https://localhost:9200/test-soak/_doc/$i" \
            -H "Content-Type: application/json" -d "{
            \"content\": \"Test document $i for soak testing\",
            \"embedding\": $VECTOR
        }" > /dev/null
    done

    log "Indexed 100 test documents ✓"
}

# Start metrics collection
start_metrics_collection() {
    log "Starting metrics collection..."

    export ES_PID=$(cat "$ES_HOME/elasticsearch.pid")
    export OUTPUT_DIR="$OUTPUT_DIR"
    export INTERVAL=60

    mkdir -p "$OUTPUT_DIR"

    # Start metrics collection in background
    cd /home/denny/projects/es-9.2.4-plugins-real-time/plugins/lance-vector/scripts
    nohup ./collect-baseline-metrics.sh > "$OUTPUT_DIR/metrics.log" 2>&1 &
    METRICS_PID=$!

    echo $METRICS_PID > "$OUTPUT_DIR/metrics.pid"
    log "Metrics collection started with PID: $METRICS_PID"
    log "Metrics output: $OUTPUT_DIR"
}

# Start soak test load
start_soak_load() {
    log "Starting soak test load..."

    # Create query script
    cat > "$OUTPUT_DIR/soak_query.sh" <<'EOF'
#!/bin/bash
VECTOR="[$(printf '0.1, '; seq 2 127 | xargs -I{} printf '0.1, ')0.1]"

while true; do
    curl -k -s -u elastic:Summer11 -X POST "https://localhost:9200/test-soak/_search" \
        -H "Content-Type: application/json" -d "{
            \"query\": { \"match\": { \"content\": \"test\" } },
            \"knn\": {
                \"field\": \"embedding\",
                \"query_vector\": $VECTOR,
                \"k\": 10,
                \"num_candidates\": 100
            }
        }" > /dev/null 2>&1

    if [[ $? -ne 0 ]]; then
        echo "[$(date)] Query failed, ES may be down" >> "$OUTPUT_DIR/errors.log"
    fi

    sleep 0.1  # 10 queries per second = 600 qpm
done
EOF

    chmod +x "$OUTPUT_DIR/soak_query.sh"

    # Start 10 concurrent load clients
    for i in {1..10}; do
        "$OUTPUT_DIR/soak_query.sh" &
        echo "Started load client $i with PID: $!"
    done

    log "Started 10 concurrent load clients (~10 qps total)"
}

# Print status information
print_status() {
    log "=== SOAK TEST STARTED ==="
    log ""
    log "ES Home:     $ES_HOME"
    log "ES PID:      $(cat $ES_HOME/elasticsearch.pid)"
    log "Output Dir:  $OUTPUT_DIR"
    log "Duration:    $DURATION_DAYS days"
    log ""
    log "Load Clients: 10 concurrent (~10 qps total)"
    log ""
    log "Monitoring Commands:"
    log "  - Check metrics: tail -f $OUTPUT_DIR/metrics.csv"
    log "  - Check errors:  tail -f $OUTPUT_DIR/errors.log"
    log "  - Check ES logs: tail -f $ES_HOME/logs/elasticsearch.log"
    log ""
    log "Stop Commands:"
    log "  - Stop load clients: pkill -f soak_query.sh"
    log "  - Stop metrics: kill $(cat $OUTPUT_DIR/metrics.pid)"
    log "  - Stop ES: cd $ES_HOME && ./bin/elasticsearch stop -p elasticsearch.pid"
    log ""
    log "After $DURATION_DAYS days, run:"
    log "  $0 --generate-report"
    log ""
}

# Generate final report
generate_report() {
    log "Generating final report..."

    cd /home/denny/projects/es-9.2.4-plugins-real-time/plugins/lance-vector/scripts
    export ES_PID=$(cat "$ES_HOME/elasticsearch.pid")
    export OUTPUT_DIR="$OUTPUT_DIR"

    # Run metrics collection summary
    if [[ -f "$OUTPUT_DIR/metrics.pid" ]]; then
        kill $(cat "$OUTPUT_DIR/metrics.pid") 2>/dev/null || true
    fi

    # The metrics script should have generated a summary when it was stopped
    if [[ -f "$OUTPUT_DIR/summary.txt" ]]; then
        cat "$OUTPUT_DIR/summary.txt"
    else
        warn "Summary file not found. Metrics collection may not have run properly."
    fi

    # Archive results
    local archive="$OUTPUT_DIR/../lance-soak-results-$(date +%Y%m%d).tar.gz"
    tar -czf "$archive" -C "$OUTPUT_DIR/.." "$(basename "$OUTPUT_DIR")"

    log "Results archived to: $archive"
}

# Main function
main() {
    local action="${1:-start}"

    case "$action" in
        start)
            check_prerequisites
            stop_existing_es
            set_oss_credentials
            start_elasticsearch
            create_test_index
            index_test_documents
            start_metrics_collection
            start_soak_load
            print_status
            ;;
        stop)
            log "Stopping soak test..."
            pkill -f soak_query.sh
            kill $(cat "$OUTPUT_DIR/metrics.pid" 2>/dev/null) 2>/dev/null || true
            log "Soak test stopped. Run '$0 --generate-report' to generate final report."
            ;;
        generate-report)
            generate_report
            ;;
        status)
            if [[ -f "$ES_HOME/elasticsearch.pid" ]]; then
                local pid=$(cat "$ES_HOME/elasticsearch.pid")
                if ps -p "$pid" > /dev/null 2>&1; then
                    log "ES is running (PID: $pid)"
                    log "Load clients: $(pgrep -f soak_query.sh | wc -l)"
                    log "Metrics collection: $(ps -p $(cat $OUTPUT_DIR/metrics.pid 2>/dev/null) 2>/dev/null && echo "running" || echo "not running")"
                else
                    warn "ES is not running"
                fi
            else
                warn "ES PID file not found"
            fi
            ;;
        *)
            echo "Usage: $0 {start|stop|generate-report|status}"
            echo ""
            echo "Commands:"
            echo "  start          - Start the 7-day soak test (default)"
            echo "  stop           - Stop the soak test"
            echo "  generate-report - Generate final report and archive results"
            echo "  status         - Show current status"
            exit 1
            ;;
    esac
}

# Run main if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
