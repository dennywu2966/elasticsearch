#!/usr/bin/env bash
# run_stress_test.sh — Run stress test scenarios for shard-aware Lance
set -euo pipefail

ES_URL="${ES_URL:-https://localhost:9200}"
ES_AUTH="${ES_AUTH:-elastic:Summer11}"
OUTPUT_DIR="${OUTPUT_DIR:-/tmp/lance-stress-results}"
GENERATOR="$(dirname "$0")/generate_sharded_dataset.py"

mkdir -p "$OUTPUT_DIR"

scenarios=(
    "S1:1:1000:128"
    "S2:5:1000:128"
    "S3:20:500:128"
)

for scenario in "${scenarios[@]}"; do
    IFS=: read -r name shards vps dims <<< "$scenario"
    echo "=== Scenario $name: $shards shards, $vps vectors/shard, ${dims}d ==="
    data_dir="$OUTPUT_DIR/$name"

    # Generate data
    python3 "$GENERATOR" --num-shards "$shards" --vectors-per-shard "$vps" \
        --dims "$dims" --output-dir "$data_dir"

    echo "  Dataset generated: $data_dir"
    echo "  Ground truth: $data_dir/ground_truth.json"
    echo ""
done

echo "All scenarios generated. Upload to OSS and run queries manually."
echo "See docs/plans/2026-02-06-p3-shard-consistency-design.md for test matrix."
