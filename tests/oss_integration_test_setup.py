#!/usr/bin/env python3
"""
OSS Integration Test Data Setup
Creates and uploads sharded Lance datasets to OSS
"""
import os
import sys
import json
import lance
import pyarrow as pa
import numpy as np

# Try to import oss2, if not available, provide installation instructions
try:
    from oss2 import Auth, Bucket
except ImportError:
    print("ERROR: oss2 not installed. Please install with:")
    print("  pip3 install oss2")
    sys.exit(1)

# Configuration
OSS_BUCKET = "denny-test-lance"
OSS_PREFIX = "regression-test/"
NUM_SHARDS = 3
DOCS_PER_SHARD = 1000
VECTOR_DIMS = 128

def read_oss_credentials():
    """Read OSS credentials from ~/.oss/credentials.json"""
    creds_file = os.path.expanduser("~/.oss/credentials.json")
    with open(creds_file) as f:
        return json.load(f)

def create_sharded_datasets():
    """Create sharded Lance datasets with IVF-PQ indices"""

    # Initialize OSS client
    oss_creds = read_oss_credentials()
    auth = Auth(oss_creds['access_key_id'], oss_creds['access_key_secret'])
    endpoint = oss_creds['endpoint'].replace('-internal.', '.')  # Use external endpoint for upload

    bucket = Bucket(auth, f"https://{endpoint}", OSS_BUCKET)

    # Clean up existing test data
    print(f"Cleaning up existing test data in OSS...")
    for obj in bucket.list_objects_v2(prefix=OSS_PREFIX).object_list:
        if obj.key.startswith(OSS_PREFIX):
            print(f"  Deleting: {obj.key}")
            bucket.delete_object(obj.key)

    print(f"Creating {NUM_SHARDS} shards with {DOCS_PER_SHARD} docs each...")

    for shard_id in range(NUM_SHARDS):
        print(f"Processing shard {shard_id}...")

        # Generate data for this shard
        start_id = shard_id * DOCS_PER_SHARD

        # Create schema (CRITICAL: use pa.string(), not pa.large_string())
        schema = pa.schema([
            pa.field("_id", pa.string()),
            pa.field("vector", pa.list_(pa.float32(), VECTOR_DIMS)),
            pa.field("category", pa.string()),
            pa.field("price", pa.float32()),
            pa.field("shard_id", pa.int32()),
        ])

        # Generate random data
        np.random.seed(42 + shard_id)  # Reproducible random data
        data = {
            "_id": [f"doc_{i}" for i in range(start_id, start_id + DOCS_PER_SHARD)],
            "vector": [np.random.random(VECTOR_DIMS).astype(np.float32).tolist() for _ in range(DOCS_PER_SHARD)],
            "category": np.random.choice(["electronics", "books", "clothing", "home"], DOCS_PER_SHARD).tolist(),
            "price": np.random.uniform(10, 1000, DOCS_PER_SHARD).astype(np.float32).tolist(),
            "shard_id": [shard_id] * DOCS_PER_SHARD,
        }

        table = pa.table(data, schema=schema)

        # Create local Lance dataset
        local_path = f"/tmp/shard-{shard_id}-v1.lance"
        if os.path.exists(local_path):
            import shutil
            shutil.rmtree(local_path)

        # Use lance.dataset.write_dataset for Lance 1.2+
        from lance.dataset import write_dataset
        dataset = write_dataset(data_obj=table, uri=local_path, mode="create")

        # Create IVF-PQ index for fast search
        print(f"  Creating IVF-PQ index for shard {shard_id}...")
        dataset.create_index(
            column="vector",
            index_type="IVF_PQ",
            metric="cosine",
            num_partitions=64,
            num_sub_vectors=16
        )

        # Upload to OSS
        oss_path = f"{OSS_PREFIX}shard-{shard_id}-v1.lance"
        print(f"  Uploading to oss://{OSS_BUCKET}/{oss_path}...")

        # Upload all files from the local dataset
        for root, dirs, files in os.walk(local_path):
            for file in files:
                local_file = os.path.join(root, file)
                relative_path = os.path.relpath(local_file, local_path)
                oss_file = f"{oss_path}/{relative_path}"

                # Read file and upload
                with open(local_file, 'rb') as f:
                    content = f.read()
                bucket.put_object(oss_file, content)

        print(f"  ✓ Shard {shard_id} uploaded: {DOCS_PER_SHARD} docs")

    print(f"\n✓ All {NUM_SHARDS} shards created and uploaded to OSS")
    print(f"  OSS Path: oss://{OSS_BUCKET}/{OSS_PREFIX}")

def update_shard_dataset(shard_id, version=2):
    """Update a specific shard dataset (for NRT refresh testing)"""

    print(f"\nUpdating shard {shard_id} to version {version}...")

    # Generate updated data with NEW vectors
    start_id = shard_id * DOCS_PER_SHARD

    schema = pa.schema([
        pa.field("_id", pa.string()),
        pa.field("vector", pa.list_(pa.float32(), VECTOR_DIMS)),
        pa.field("category", pa.string()),
        pa.field("price", pa.float32()),
        pa.field("shard_id", pa.int32()),
        pa.field("version", pa.int32()),
    ])

    # Use different seed for version 2 to get different vectors
    np.random.seed(100 + shard_id + version)
    data = {
        "_id": [f"doc_{i}" for i in range(start_id, start_id + DOCS_PER_SHARD)],
        "vector": [np.random.random(VECTOR_DIMS).astype(np.float32).tolist() for _ in range(DOCS_PER_SHARD)],
        "category": np.random.choice(["electronics", "books", "clothing", "home", "NEW_CATEGORY"], DOCS_PER_SHARD).tolist(),
        "price": np.random.uniform(10, 1000, DOCS_PER_SHARD).astype(np.float32).tolist(),
        "shard_id": [shard_id] * DOCS_PER_SHARD,
        "version": [version] * DOCS_PER_SHARD,
    }

    table = pa.table(data, schema=schema)

    local_path = f"/tmp/shard-{shard_id}-v{version}.lance"
    if os.path.exists(local_path):
        import shutil
        shutil.rmtree(local_path)

    from lance.dataset import write_dataset
    dataset = write_dataset(data_obj=table, uri=local_path, mode="create")

    # Create IVF-PQ index
    print(f"  Creating IVF-PQ index for shard {shard_id} v{version}...")
    dataset.create_index(
        column="vector",
        index_type="IVF_PQ",
        metric="cosine",
        num_partitions=64,
        num_sub_vectors=16
    )

    # Upload to OSS
    oss_creds = read_oss_credentials()
    auth = Auth(oss_creds['access_key_id'], oss_creds['access_key_secret'])
    endpoint = oss_creds['endpoint'].replace('-internal.', '.')
    bucket = Bucket(auth, f"https://{endpoint}", OSS_BUCKET)

    oss_path = f"{OSS_PREFIX}shard-{shard_id}-v{version}.lance"

    for root, dirs, files in os.walk(local_path):
        for file in files:
            local_file = os.path.join(root, file)
            relative_path = os.path.relpath(local_file, local_path)
            oss_file = f"{oss_path}/{relative_path}"

            with open(local_file, 'rb') as f:
                content = f.read()
            bucket.put_object(oss_file, content)

    print(f"  ✓ Shard {shard_id} v{version} uploaded to OSS")

if __name__ == "__main__":
    print("=" * 60)
    print("OSS Integration Test Data Setup")
    print("=" * 60)

    # Step 1: Create initial sharded datasets
    create_sharded_datasets()

    print("\n" + "=" * 60)
    print("Initial setup complete!")
    print("=" * 60)
