#!/bin/bash
# Baseline metrics collector for Lance Vector Plugin soak testing
# This script monitors Elasticsearch for resource leaks and performance degradation

set -euo pipefail

# Configuration
ES_HOME="${ES_HOME:-/home/denny/projects/es-9.2.4-plugins-real-time/build/distribution/local/elasticsearch-9.2.4-SNAPSHOT}"
ES_PID="${ES_PID:-}"
OUTPUT_DIR="${OUTPUT_DIR:-/tmp/lance-soak-metrics}"
INTERVAL="${INTERVAL:-60}"  # seconds between samples

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Output files
METRICS_FILE="$OUTPUT_DIR/metrics.csv"
SUMMARY_FILE="$OUTPUT_DIR/summary.txt"
ALERTS_FILE="$OUTPUT_DIR/alerts.log"

# Initialize CSV with headers
echo "timestamp,heap_used_mb,heap_max_mb,heap_percent,arrow_allocated_mb,open_fds,thread_count,query_count_p50,query_count_p95,query_count_p99,dataset_count,cache_hits,cache_misses" > "$METRICS_FILE"

# Alert thresholds
ALERT_HEAP_PERCENT=90
ALERT_FD_COUNT=10000
ALERT_THREAD_COUNT=2000
ALERT_ARROW_MB=500

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$ALERTS_FILE"
}

alert() {
    log "ALERT: $*"
}

get_es_pid() {
    if [[ -n "$ES_PID" ]]; then
        echo "$ES_PID"
    elif [[ -f "$ES_HOME/elasticsearch.pid" ]]; then
        cat "$ES_HOME/elasticsearch.pid"
    else
        # Try to find ES process
        pgrep -f "elasticsearch.*9.2.4-SNAPSHOT" | head -1
    fi
}

get_heap_metrics() {
    local pid=$1
    local jstat_output
    jstat_output=$(jstat -gc "$pid" 2>/dev/null || echo "")

    if [[ -z "$jstat_output" ]]; then
        echo "0,0,0"
        return
    fi

    # Parse jstat output: S0C S1C S0U S1U EC EU OU OC YGC FGC FGCT GCT
    # We want: heap_used (OU - OU is old gen used, but we need total)
    # Let's use a different approach with jmap or parse jstat properly

    # Alternative: Use /proc/{pid}/status for VmRSS and estimate
    local heap_used=$(awk '/VmRSS:/ {print $2}' /proc/$pid/status 2>/dev/null || echo "0")
    local heap_max=$(jinfo -heap "$pid" 2>/dev/null | awk '/MaxHeapSize/ {print $3}' || echo "0")

    # Convert from KB to MB
    heap_used=$((heap_used / 1024))
    heap_max=$((heap_max / 1024 / 1024))

    local heap_percent=0
    if [[ $heap_max -gt 0 ]]; then
        heap_percent=$((heap_used * 100 / heap_max))
    fi

    echo "$heap_used,$heap_max,$heap_percent"
}

get_arrow_memory() {
    # This would require JVM metrics or native memory tracking
    # For now, estimate based on RSS - heap
    # In production, use RealLanceDataset.getAllocatedMemory()
    echo "0"  # Placeholder - would need JMX or custom metrics endpoint
}

get_fd_count() {
    local pid=$1
    ls /proc/$pid/fd 2>/dev/null | wc -l || echo "0"
}

get_thread_count() {
    local pid=$1
    ls /proc/$pid/task 2>/dev/null | wc -l || echo "0"
}

get_query_metrics() {
    # Would need to query ES _nodes/stats or custom metrics endpoint
    # Placeholder for now
    echo "0,0,0"
}

get_dataset_cache_stats() {
    # Would need to query custom metrics endpoint
    # Placeholder for now
    echo "0,0,0"
}

collect_metrics() {
    local pid=$1
    local timestamp=$(date +%s)

    # Get all metrics
    local heap_metrics
    local arrow_mb
    local fd_count
    local thread_count
    local query_metrics
    local cache_stats

    heap_metrics=$(get_heap_metrics "$pid")
    arrow_mb=$(get_arrow_memory)
    fd_count=$(get_fd_count "$pid")
    thread_count=$(get_thread_count "$pid")
    query_metrics=$(get_query_metrics)
    cache_stats=$(get_dataset_cache_stats)

    # Parse heap metrics
    local heap_used heap_max heap_percent
    IFS=',' read -r heap_used heap_max heap_percent <<< "$heap_metrics"

    # Parse query metrics
    local q_p50 q_p95 q_p99
    IFS=',' read -r q_p50 q_p95 q_p99 <<< "$query_metrics"

    # Parse cache stats
    local dataset_count cache_hits cache_misses
    IFS=',' read -r dataset_count cache_hits cache_misses <<< "$cache_stats"

    # Write to CSV
    echo "$timestamp,$heap_used,$heap_max,$heap_percent,$arrow_mb,$fd_count,$thread_count,$q_p50,$q_p95,$q_p99,$dataset_count,$cache_hits,$cache_misses" >> "$METRICS_FILE"

    # Check for alerts
    if [[ $heap_percent -gt $ALERT_HEAP_PERCENT ]]; then
        alert "Heap usage at ${heap_percent}% (threshold: ${ALERT_HEAP_PERCENT}%)"
    fi

    if [[ $fd_count -gt $ALERT_FD_COUNT ]]; then
        alert "FD count at ${fd_count} (threshold: ${ALERT_FD_COUNT})"
    fi

    if [[ $thread_count -gt $ALERT_THREAD_COUNT ]]; then
        alert "Thread count at ${thread_count} (threshold: ${ALERT_THREAD_COUNT})"
    fi

    if [[ $arrow_mb -gt $ALERT_ARROW_MB ]]; then
        alert "Arrow memory at ${arrow_mb}MB (threshold: ${ALERT_ARROW_MB}MB)"
    fi

    # Print current status
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Heap: ${heap_used}MB/${heap_max}MB (${heap_percent}%) | FDs: ${fd_count} | Threads: ${thread_count}"
}

generate_summary() {
    {
        echo "=== Lance Vector Plugin Soak Test Summary ==="
        echo "Collection Period: $(head -1 "$METRICS_FILE" | cut -d',' -f1) to $(tail -1 "$METRICS_FILE" | cut -d',' -f1)"
        echo ""
        echo "Heap Usage (MB):"
        awk -F',' 'NR>1 {print $2}' "$METRICS_FILE" | sort -n | awk '{min=$1; max=$1; sum+=$1; count++} END {print "  Min: " min " MB"; print "  Max: " max " MB"; print "  Avg: " (sum/count) " MB"}'
        echo ""
        echo "FD Count:"
        awk -F',' 'NR>1 {print $6}' "$METRICS_FILE" | sort -n | awk '{min=$1; max=$1; sum+=$1; count++} END {print "  Min: " min; print "  Max: " max; print "  Avg: " (sum/count)}'
        echo ""
        echo "Thread Count:"
        awk -F',' 'NR>1 {print $7}' "$METRICS_FILE" | sort -n | awk '{min=$1; max=$1; sum+=$1; count++} END {print "  Min: " min; print "  Max: " max; print "  Avg: " (sum/count)}'
        echo ""
        echo "Alerts triggered:"
        if [[ -s "$ALERTS_FILE" ]]; then
            grep -c "ALERT:" "$ALERTS_FILE" || echo "0"
            echo ""
            echo "Recent alerts:"
            tail -10 "$ALERTS_FILE"
        else
            echo "  None"
        fi
    } | tee "$SUMMARY_FILE"
}

cleanup() {
    log "Stopping metrics collection"
    generate_summary
    exit 0
}

# Main collection loop
main() {
    local pid
    pid=$(get_es_pid)

    if [[ -z "$pid" ]]; then
        echo "Error: Could not find Elasticsearch PID"
        echo "Set ES_PID environment variable or ensure ES is running"
        exit 1
    fi

    echo "Starting metrics collection for ES PID: $pid"
    echo "Output directory: $OUTPUT_DIR"
    echo "Interval: ${INTERVAL}s"
    echo "Press Ctrl+C to stop and generate summary"

    # Trap signals for cleanup
    trap cleanup SIGINT SIGTERM

    # Collection loop
    while true; do
        collect_metrics "$pid"
        sleep "$INTERVAL"
    done
}

# Run main if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
