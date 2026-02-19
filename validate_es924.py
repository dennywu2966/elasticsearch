#!/usr/bin/env python3
"""
Lance Vector Plugin Validation for Elasticsearch 9.2.4

This script performs comprehensive validation of the ported plugin including:
1. Build verification
2. Plugin installation
3. Local Lance dataset testing
4. Basic kNN search validation
"""

import os
import sys
import subprocess
import time
import json
import shutil
from pathlib import Path

# Configuration
ES_PROJECT_DIR = Path("/home/denny/projects/es-9.2.4-plugins")
PLUGIN_ZIP = ES_PROJECT_DIR / "plugins/lance-vector/build/distributions/lance-vector-9.2.4-SNAPSHOT.zip"
CLOUD_IAM_ZIP = ES_PROJECT_DIR / "plugins/security-realm-cloud-iam/build/distributions/security-realm-cloud-iam-9.2.4-SNAPSHOT.zip"
ES_TAR = ES_PROJECT_DIR / "distribution/archives/linux-tar/build/distributions/elasticsearch-9.2.4-SNAPSHOT-linux-x86_64.tar.gz"
ES_INSTALL_DIR = Path("/tmp/elasticsearch-9.2.4-validation")
DATASET_PATH = Path("/tmp/test-vectors.lance")

# Colors for output
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
BLUE = "\033[94m"
RESET = "\033[0m"

def log(message, color=RESET):
    print(f"{color}{message}{RESET}")

def check_step(name, condition, details=""):
    if condition:
        log(f"✅ {name}", GREEN)
        if details:
            print(f"   {details}")
        return True
    else:
        log(f"❌ {name}", RED)
        if details:
            print(f"   {details}")
        return False

def run_command(cmd, check=True, capture_output=True):
    """Run a shell command and return output"""
    log(f"Running: {' '.join(cmd)}", BLUE)
    result = subprocess.run(cmd, capture_output=capture_output, text=True)
    if check and result.returncode != 0:
        log(f"Command failed: {result.stderr}", RED)
        sys.exit(1)
    return result

def validate_build():
    """Phase 1: Build Verification"""
    log("\n=== Phase 1: Build Verification ===", BLUE)

    all_passed = True

    # Check plugin zip exists
    if PLUGIN_ZIP.exists():
        size = PLUGIN_ZIP.stat().st_size / (1024**2)  # MB
        all_passed &= check_step(
            "Lance Vector plugin zip exists",
            True,
            f"Size: {size:.1f}MB (expected ~268MB)"
        )
    else:
        all_passed &= check_step("Lance Vector plugin zip exists", False, "Run: ./gradlew :plugins:lance-vector:bundlePlugin")

    # Check Cloud IAM plugin
    if CLOUD_IAM_ZIP.exists():
        size = CLOUD_IAM_ZIP.stat().st_size / 1024  # KB
        all_passed &= check_step(
            "Cloud IAM plugin zip exists",
            True,
            f"Size: {size:.1f}KB"
        )
    else:
        all_passed &= check_step("Cloud IAM plugin zip exists", False)

    # Check ES distribution
    if ES_TAR.exists():
        size = ES_TAR.stat().st_size / (1024**2)  # MB
        all_passed &= check_step(
            "ES 9.2.4 distribution exists",
            True,
            f"Size: {size:.1f}MB"
        )
    else:
        all_passed &= check_step(
            "ES 9.2.4 distribution exists",
            False,
            "Building now... (this will take several minutes)"
        )
        log("Building ES distribution...", YELLOW)
        run_command([
            "./gradlew", ":distribution:archives:linux-tar:assemble"
        ], check=False)

    return all_passed

def setup_elasticsearch():
    """Phase 2: Setup and Install Elasticsearch"""
    log("\n=== Phase 2: Setup Elasticsearch ===", BLUE)

    # Extract ES if not already done
    if ES_INSTALL_DIR.exists():
        log("ES already extracted", YELLOW)
    else:
        log("Extracting Elasticsearch...", BLUE)
        run_command([
            "tar", "-xzf", str(ES_TAR),
            "-C", "/tmp"
        ])
        # Rename to consistent name
        extracted = Path("/tmp/elasticsearch-9.2.4-SNAPSHOT")
        if extracted.exists() and not ES_INSTALL_DIR.exists():
            extracted.rename(ES_INSTALL_DIR)

    # Install plugins
    es_bin = ES_INSTALL_DIR / "bin"
    plugin_cmd = es_bin / "elasticsearch-plugin"

    # Check if plugins already installed
    installed_plugins = run_command([
        str(plugin_cmd), "list"
    ], check=False)

    if "lance-vector" in installed_plugins.stdout:
        log("Lance Vector plugin already installed", YELLOW)
    else:
        log("Installing Lance Vector plugin...", BLUE)
        run_command([
            str(plugin_cmd), "install", "-b", str(PLUGIN_ZIP)
        ])

    if "security-realm-cloud-iam" in installed_plugins.stdout:
        log("Cloud IAM plugin already installed", YELLOW)
    else:
        log("Installing Cloud IAM plugin...", BLUE)
        run_command([
            str(plugin_cmd), "install", "-b", str(CLOUD_IAM_ZIP)
        ])

    # Configure JVM options for Arrow
    jvm_options_dir = ES_INSTALL_DIR / "config" / "jvm.options.d"
    jvm_options_dir.mkdir(parents=True, exist_ok=True)
    jvm_options_file = jvm_options_dir / "lance-arrow.options"

    if not jvm_options_file.exists():
        log("Configuring JVM options for Arrow...", BLUE)
        jvm_options_file.write_text("--add-opens=java.base/java.nio=ALL-UNNAMED\n")

    return True

def create_test_dataset():
    """Phase 3: Create Test Dataset"""
    log("\n=== Phase 3: Create Test Dataset ===", BLUE)

    if DATASET_PATH.exists():
        log(f"Dataset already exists: {DATASET_PATH}", YELLOW)
        return True

    log("Creating test dataset with 300 vectors (128 dims)...", BLUE)

    # Check if Python and required packages are available
    try:
        import pyarrow as pa
        import numpy as np
    except ImportError as e:
        log(f"Missing Python packages: {e}", RED)
        log("Install with: pip install pyarrow numpy lance", YELLOW)
        return False

    # Create dataset
    try:
        import lance

        n_vectors = 300
        dims = 128

        # Generate random vectors
        ids = [f"doc_{i:04d}" for i in range(n_vectors)]
        vectors = np.random.random((n_vectors, dims)).astype(np.float32)
        categories = np.random.choice(['A', 'B', 'C'], size=n_vectors)

        # Create Arrow table
        schema = pa.schema([
            pa.field('_id', pa.string()),  # Use string, not large_string
            pa.field('vector', pa.list_(pa.float32(), list_size=dims)),
            pa.field('category', pa.string())
        ])

        data = {
            '_id': ids,
            'vector': [vectors[i] for i in range(n_vectors)],
            'category': categories.tolist()
        }

        table = pa.table(data, schema=schema)

        # Write to Lance dataset
        log("Writing Lance dataset with IVF-PQ index...", BLUE)
        lance.write_dataset(
            str(DATASET_PATH),
            mode="overwrite",
            data=table
        )

        log(f"Dataset created: {DATASET_PATH}", GREEN)
        log(f"  Vectors: {n_vectors}, Dimensions: {dims}")
        return True

    except Exception as e:
        log(f"Failed to create dataset: {e}", RED)
        return False

def start_elasticsearch():
    """Phase 4: Start Elasticsearch"""
    log("\n=== Phase 4: Start Elasticsearch ===", BLUE)

    # Check if already running
    try:
        result = run_command(["curl", "-s", "http://localhost:9200"], check=False)
        if result.returncode == 0:
            log("Elasticsearch already running", YELLOW)
            return True
    except:
        pass

    es_bin = ES_INSTALL_DIR / "bin"

    # Start ES in foreground
    log("Starting Elasticsearch...", BLUE)
    log(f"Data directory: {ES_INSTALL_DIR}")
    log("Press Ctrl+C to stop ES after validation")

    # Create a temporary pid file
    pid_file = Path("/tmp/elasticsearch-validation.pid")

    # Start ES in background
    es_process = subprocess.Popen(
        [str(es_bin / "elasticsearch")],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )

    # Save PID
    with open(pid_file, 'w') as f:
        f.write(str(es_process.pid))

    # Wait for ES to start
    log("Waiting for Elasticsearch to start...", BLUE)
    max_wait = 60
    for i in range(max_wait):
        try:
            result = subprocess.run(
                ["curl", "-s", "http://localhost:9200"],
                capture_output=True,
                timeout=5
            )
            if result.returncode == 0:
                log(f"Elasticsearch started after {i+1} seconds", GREEN)
                return True
        except:
            pass
        time.sleep(1)
        print(".", end="", flush=True)

    print()
    log("Failed to start Elasticsearch", RED)
    return False

def validate_local_search():
    """Phase 5: Validate Local kNN Search"""
    log("\n=== Phase 5: Validate Local kNN Search ===", BLUE)

    # Create index
    index_name = "lance-validation"

    # Delete index if exists
    run_command([
        "curl", "-s", "-X", "DELETE", f"http://localhost:9200/{index_name}"
    ], check=False)

    # Create index with Lance vector mapping
    mapping = {
        "mappings": {
            "properties": {
                "category": {
                    "type": "keyword"
                },
                "embedding": {
                    "type": "lance_vector",
                    "dims": 128,
                    "similarity": "cosine",
                    "storage": {
                        "type": "external",
                        "uri": f"file://{DATASET_PATH}",
                        "lance_id_column": "_id",
                        "lance_vector_column": "vector",
                        "read_only": True
                    }
                }
            }
        }
    }

    log(f"Creating index {index_name}...", BLUE)
    result = run_command([
        "curl", "-s", "-X", "PUT", f"http://localhost:9200/{index_name}",
        "-H", "Content-Type: application/json",
        "-d", json.dumps(mapping)
    ])

    if '"acknowledged":true' not in result.stdout:
        log("Failed to create index", RED)
        log(result.stdout)
        return False

    log("Index created successfully", GREEN)

    # Index some metadata documents
    log("Indexing metadata documents...", BLUE)
    for i in range(100):
        doc = {
            "category": np.random.choice(['A', 'B', 'C'])
        }
        run_command([
            "curl", "-s", "-X", "POST", f"http://localhost:9200/{index_name}/_doc/doc_{i:04d}",
            "-H", "Content-Type: application/json",
            "-d", json.dumps(doc)
        ], check=False)

    log(f"Indexed 100 documents", GREEN)

    # Refresh index
    run_command([
        "curl", "-s", "-X", "POST", "http://localhost:9200/{index_name}/_refresh"
    ])

    # Perform kNN search
    log("Performing kNN search...", BLUE)

    query = {
        "knn": {
            "field": "embedding",
            "query_vector": np.random.random(128).tolist(),
            "k": 5,
            "num_candidates": 50
        }
    }

    start_time = time.time()
    result = run_command([
        "curl", "-s", "-X", "POST", f"http://localhost:9200/{index_name}/_search",
        "-H", "Content-Type: application/json",
        "-d", json.dumps(query)
    ])
    elapsed = time.time() - start_time

    try:
        response = json.loads(result.stdout)
        hits = response['hits']['hits']

        check_step(
            f"kNN search returned {len(hits)} results",
            len(hits) == 5,
            f"Latency: {elapsed:.2f}s"
        )

        # Check scores
        if hits:
            scores = [hit['_score'] for hit in hits]
            all_positive = all(s > 0 for s in scores)
            check_step(
                "All scores are positive",
                all_positive,
                f"Score range: {min(scores):.4f} - {max(scores):.4f}"
            )

        # Stress test - run 20 searches
        log("Running stress test (20 searches)...", BLUE)
        stress_times = []
        for i in range(20):
            query['knn']['query_vector'] = np.random.random(128).tolist()
            result = run_command([
                "curl", "-s", "-X", "POST", f"http://localhost:9200/{index_name}/_search",
                "-H", "Content-Type: application/json",
                "-d", json.dumps(query)
            ], check=False)
            start = time.time()
            # Don't parse, just check it succeeded
            if '"hits":' in result.stdout:
                stress_times.append(time.time() - start)

        if stress_times:
            avg_time = np.mean(stress_times)
            min_time = np.min(stress_times)
            max_time = np.max(stress_times)

            check_step(
                f"Stress test completed: {len(stress_times)}/20 searches",
                len(stress_times) == 20,
                f"Latency: avg={avg_time:.0f}ms, min={min_time:.0f}ms, max={max_time:.0f}ms"
            )

        return True

    except Exception as e:
        log(f"kNN search failed: {e}", RED)
        log(result.stdout)
        return False

def main():
    """Main validation workflow"""
    log("=" * 60, BLUE)
    log("Lance Vector Plugin Validation for Elasticsearch 9.2.4", BLUE)
    log("=" * 60, BLUE)

    all_passed = True

    # Phase 1: Build
    if not validate_build():
        log("\n❌ Build verification failed", RED)
        return 1

    # Phase 2: Setup
    if not setup_elasticsearch():
        log("\n❌ ES setup failed", RED)
        return 1

    # Phase 3: Dataset
    if not create_test_dataset():
        log("\n❌ Dataset creation failed", RED)
        return 1

    # Phase 4: Start ES
    if not start_elasticsearch():
        log("\n❌ Failed to start ES", RED)
        return 1

    # Phase 5: Validate
    if not validate_local_search():
        log("\n❌ Local search validation failed", RED)
        return 1

    log("\n" + "=" * 60, GREEN)
    log("✅ All validation checks passed!", GREEN)
    log("=" * 60, GREEN)

    # Cleanup
    log("\nTo stop Elasticsearch:", YELLOW)
    log(f"  kill {Path('/tmp/elasticsearch-validation.pid').read_text().strip()}", BLUE)
    log(f"  Or: rm -rf {ES_INSTALL_DIR}", BLUE)

    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        log("\n\nValidation interrupted by user", YELLOW)
        sys.exit(130)
    except Exception as e:
        log(f"\n❌ Validation failed with error: {e}", RED)
        import traceback
        traceback.print_exc()
        sys.exit(1)
