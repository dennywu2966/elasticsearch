# Shard-Aware Dataset Mapping

## Overview

The Lance Vector plugin supports two dataset mapping modes:

### Legacy Mode (single dataset)
All shards read from the same Lance dataset. Suitable for single-shard indices
or testing.

### Shard-Aware Mode (per-shard datasets)
Each shard reads from its own Lance dataset. Required for correct multi-shard
behavior at scale.

## Configuration

### Legacy Mode
```json
{
  "storage": {
    "uri": "oss://bucket/dataset.lance"
  }
}
```

All shards share `dataset.lance`. Works for single shard or testing.

### Shard-Aware Mode
```json
{
  "storage": {
    "uri_prefix": "oss://bucket/production",
    "shard_path": "{index}/shard-{shard_id}",
    "dataset_name": "vectors.lance"
  }
}
```

**Resolved URI**: `{uri_prefix}/{shard_path}/{dataset_name}`
**Example**: `oss://bucket/production/my-index/shard-0/vectors.lance`

### Template Variables

| Variable | Replaced With | Example |
|----------|--------------|---------|
| `{index}` | ES index name | `product-vectors` |
| `{shard_id}` | Numeric shard ID (0-based) | `0`, `1`, `2` |

## Critical Invariant

The data pipeline MUST partition vectors using the same `_id` routing as
Elasticsearch: `hash(_id) % number_of_shards`. If the pipeline uses different
routing, shard N's Lance dataset will contain vectors for docs NOT on shard N,
causing zero recall.

ES uses Murmur3 hash function for `_id` routing. The Python generator script
includes a simplified `es_shard_for_id()` function that approximates this
behavior using MD5 hash.

## Not Supported

- Dynamic shard split/merge (use reindex + rebuild datasets)
- Custom `_routing` (default `_id` routing only)
- Write path from ES to Lance (read-only)

## Example: Complete Mapping

```json
PUT /product-vectors
{
  "settings": {
    "number_of_shards": 5,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "product_id": { "type": "keyword" },
      "name": { "type": "text" },
      "embedding": {
        "type": "lance_vector",
        "dims": 768,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri_prefix": "oss://denny-test-lance/production",
          "shard_path": "{index}/shard-{shard_id}",
          "dataset_name": "vectors.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector",
          "read_only": true
        }
      }
    }
  }
}
```

This mapping resolves to:
- Shard 0: `oss://denny-test-lance/production/product-vectors/shard-0/vectors.lance`
- Shard 1: `oss://denny-test-lance/production/product-vectors/shard-1/vectors.lance`
- Shard 2: `oss://denny-test-lance/production/product-vectors/shard-2/vectors.lance`
- ...

## Data Pipeline Requirements

1. **Shard-Partitioning**: When building Lance datasets, partition vectors by
   target shard using the same hash routing as ES.

2. **Index Building**: Build IVF-PQ or HNSW indices on each shard's dataset
   independently.

3. **OSS Upload**: Upload each shard's dataset to its resolved URI path.

4. **Validation**: Verify that each shard's dataset contains only vectors for
   documents that would route to that shard.

See `plugins/lance-vector/tools/generate_sharded_dataset.py` for a reference
implementation that generates partitioned datasets.

## Backward Compatibility

Existing indices using `storage.uri` continue to work unchanged. The shard-aware
fields (`uri_prefix`, `shard_path`, `dataset_name`) are only used when
`uri_prefix` is present.

## Query Behavior

In shard-aware mode, each shard's query executor independently:
1. Resolves its dataset URI using `{index}` and `{shard_id}`
2. Opens its Lance dataset (cached per-URI)
3. Performs ANN search for top-K candidates
4. Joins results with Lucene `_id` index for filter application

This ensures each shard searches only its own vectors, enabling linear scaling
with shard count.
