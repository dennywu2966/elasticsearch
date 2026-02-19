#!/usr/bin/env python3
"""Generate sharded Lance datasets for stress testing.

Usage:
    python generate_sharded_dataset.py \
        --num-shards 5 \
        --vectors-per-shard 100000 \
        --dims 768 \
        --output-dir /tmp/lance-stress-test \
        --similarity cosine

Outputs:
    /tmp/lance-stress-test/shard-0/vectors.lance
    /tmp/lance-stress-test/shard-1/vectors.lance
    ...
"""
import argparse
import hashlib
import json
import os

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False
    print("WARNING: numpy not installed, using random module instead")

def es_shard_for_id(doc_id: str, num_shards: int) -> int:
    """Replicate ES default _id routing: Murmur3 hash mod num_shards."""
    # Simplified: use consistent hash. For exact ES compat, use Murmur3.
    h = int(hashlib.md5(doc_id.encode()).hexdigest(), 16)
    return h % num_shards

def random_vector(dims, similarity="cosine"):
    """Generate a random vector with optional normalization."""
    if HAS_NUMPY:
        rng = __import__("random").Random(42)  # For consistent seed across runs
        # Use numpy for better performance
        import numpy as np
        np.random.seed(42)
        vec = np.random.standard_normal(dims).astype(np.float32)
        if similarity == "cosine":
            vec = vec / np.linalg.norm(vec)
        return vec
    else:
        import random
        random.seed(42)
        vec = [random.gauss(0, 1) for _ in range(dims)]
        if similarity == "cosine":
            norm = sum(x * x for x in vec) ** 0.5
            vec = [x / norm for x in vec]
        return vec

def generate(args):
    try:
        import lance
        import pyarrow as pa
        HAS_LANCE = True
    except ImportError:
        HAS_LANCE = False
        print("WARNING: lance not installed, generating JSON fixtures instead")

    os.makedirs(args.output_dir, exist_ok=True)
    total = args.num_shards * args.vectors_per_shard

    print(f"Generating {total} vectors ({args.dims}d) across {args.num_shards} shards...")

    # Partition by shard
    shard_data = {s: {"ids": [], "vectors": []} for s in range(args.num_shards)}

    if HAS_NUMPY:
        import numpy as np
        np.random.seed(42)
        for i in range(total):
            doc_id = f"doc-{i:08d}"
            shard = es_shard_for_id(doc_id, args.num_shards)
            vec = np.random.standard_normal(args.dims).astype(np.float32)
            if args.similarity == "cosine":
                vec = vec / np.linalg.norm(vec)
            shard_data[shard]["ids"].append(doc_id)
            shard_data[shard]["vectors"].append(vec)
    else:
        import random
        random.seed(42)
        for i in range(total):
            doc_id = f"doc-{i:08d}"
            shard = es_shard_for_id(doc_id, args.num_shards)
            vec = [random.gauss(0, 1) for _ in range(args.dims)]
            if args.similarity == "cosine":
                norm = sum(x * x for x in vec) ** 0.5
                vec = [x / norm for x in vec]
            shard_data[shard]["ids"].append(doc_id)
            shard_data[shard]["vectors"].append(vec)

    for s in range(args.num_shards):
        shard_dir = os.path.join(args.output_dir, f"shard-{s}")
        os.makedirs(shard_dir, exist_ok=True)
        ids = shard_data[s]["ids"]
        vecs = shard_data[s]["vectors"]
        print(f"  Shard {s}: {len(ids)} vectors")

        if HAS_LANCE:
            if HAS_NUMPY:
                import numpy as np
                vecs_array = np.array(vecs)
                table = pa.table({
                    "_id": pa.array(ids, type=pa.string()),
                    "vector": pa.FixedSizeListArray.from_arrays(
                        pa.array(vecs_array.flatten(), type=pa.float32()),
                        args.dims
                    )
                })
            else:
                # Convert list of lists to flattened array
                flat_vecs = []
                for v in vecs:
                    flat_vecs.extend(v)
                table = pa.table({
                    "_id": pa.array(ids, type=pa.string()),
                    "vector": pa.FixedSizeListArray.from_arrays(
                        pa.array(flat_vecs, type=pa.float32()),
                        args.dims
                    )
                })
            ds = lance.write_dataset(table, os.path.join(shard_dir, "vectors.lance"), mode="overwrite")
            # Build IVF-PQ index
            if len(ids) >= 256:
                ds.create_index("vector", index_type="IVF_PQ", num_partitions=min(256, len(ids) // 10))
                print(f"    Built IVF-PQ index")
        else:
            # Fallback: write JSON fixture for FakeLanceDataset
            with open(os.path.join(shard_dir, "vectors.json"), "w") as f:
                vecs_serializable = [v.tolist() if HAS_NUMPY else v for v in vecs]
                json.dump({"dims": args.dims, "vectors": {
                    ids[i]: vecs_serializable[i] for i in range(len(ids))
                }}, f)

    # Write ground truth for recall validation (small sample)
    print("Writing ground truth...")
    if HAS_NUMPY:
        import numpy as np
        query_vecs = np.random.standard_normal((10, args.dims)).astype(np.float32)
        if args.similarity == "cosine":
            query_vecs = query_vecs / np.linalg.norm(query_vecs, axis=1, keepdims=True)
    else:
        import random
        query_vecs = [[random.gauss(0, 1) for _ in range(args.dims)] for _ in range(10)]

    ground_truth = []
    for qi, qv in enumerate(query_vecs):
        # Simplified: compute scores for first 100 docs only
        sample_size = min(100, total)
        scores = []
        for i in range(sample_size):
            doc_id = f"doc-{i:08d}"
            # Find shard and vector
            for s in range(args.num_shards):
                if doc_id in shard_data[s]["ids"]:
                    idx = shard_data[s]["ids"].index(doc_id)
                    vec = shard_data[s]["vectors"][idx]
                    if HAS_NUMPY:
                        import numpy as np
                        qv_np = np.array(qv) if not isinstance(qv, np.ndarray) else qv
                        vec_np = np.array(vec) if not isinstance(vec, np.ndarray) else vec
                        if args.similarity == "cosine":
                            score = float(np.dot(vec_np, qv_np))
                        else:
                            score = float(-np.linalg.norm(vec_np - qv_np))
                    else:
                        if args.similarity == "cosine":
                            dot = sum(a * b for a, b in zip(vec, qv))
                            score = dot
                        else:
                            score = -sum((a - b) ** 2 for a, b in zip(vec, qv)) ** 0.5
                    scores.append({"id": doc_id, "score": score})
                    break
        scores.sort(key=lambda x: x["score"], reverse=True)
        ground_truth.append({
            "query_index": qi,
            "query_vector": (qv.tolist() if HAS_NUMPY else qv),
            "top_10": scores[:10]
        })

    with open(os.path.join(args.output_dir, "ground_truth.json"), "w") as f:
        json.dump(ground_truth, f, indent=2)
    print(f"Done. Output: {args.output_dir}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--num-shards", type=int, default=5)
    parser.add_argument("--vectors-per-shard", type=int, default=100000)
    parser.add_argument("--dims", type=int, default=768)
    parser.add_argument("--output-dir", default="/tmp/lance-stress-test")
    parser.add_argument("--similarity", default="cosine")
    generate(parser.parse_args())
