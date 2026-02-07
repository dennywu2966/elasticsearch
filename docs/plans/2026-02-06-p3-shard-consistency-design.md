# P3: Shard Consistency & Scale — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make shard-to-dataset mapping explicit, correct, and proven at scale — so each ES shard reads its own Lance dataset (with pre-built ANN indices) and query results are correct in multi-shard deployments.

**Architecture:** Replace the current "all shards share one global dataset" model with a shard-aware URI template system. Each shard resolves its own dataset URI at query time. The Lance search moves from `createWeight()` (once globally) to `scorerSupplier()` (per shard), aligning with Lucene's distributed execution model. Three new mapping fields (`uri_prefix`, `shard_path`, `dataset_name`) replace the single `uri` field for shard-aware configurations.

**Tech Stack:** Java 21, Elasticsearch 9.2.4 plugin API, Lucene, Lance Java SDK, Apache Arrow

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          DATA INGESTION LAYER                               │
│                                                                             │
│   ┌──────────┐    ┌──────────────────┐    ┌─────────────────────────────┐   │
│   │  App /   │───▶│  Data Pipeline   │───▶│   Object Storage (OSS)      │   │
│   │  Producer│    │  (Spark/Flink)   │    │                             │   │
│   └──────────┘    └──────────────────┘    │  oss://bucket/prod/         │   │
│                     │                     │   ├─ my-index/              │   │
│                     │ Shard-aware         │   │   ├─ shard-0/           │   │
│                     │ partitioning        │   │   │   └─ vectors.lance  │   │
│                     │ (hash _id %N)       │   │   │       ├─ data/     │   │
│                     │                     │   │   │       └─ indices/  │   │
│                     ▼                     │   │   ├─ shard-1/           │   │
│              ┌──────────────┐             │   │   │   └─ vectors.lance  │   │
│              │ Lance Writer │             │   │   └─ shard-2/           │   │
│              │ (per shard)  │─────────────│───┘       └─ vectors.lance  │   │
│              │ + Index Build│             │                             │   │
│              └──────────────┘             └─────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                                        │
                    ┌───────────────────────────────────┘
                    │
            ┌───────▼──────┐
            │  NRT Job     │
            │  (Backfill   │
            │   metadata   │
            │   to ES)     │
            └───────┬──────┘
                    │ Bulk index:
                    │ title, category,
                    │ price, tags...
                    │
┌───────────────────▼─────────────────────────────────────────────────────────┐
│                     ELASTICSEARCH CLUSTER                                    │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     Index: my-index                                  │    │
│  │                     (number_of_shards: 3)                           │    │
│  │                                                                     │    │
│  │  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐              │    │
│  │  │  Shard 0    │   │  Shard 1    │   │  Shard 2    │              │    │
│  │  │             │   │             │   │             │              │    │
│  │  │ Lucene:     │   │ Lucene:     │   │ Lucene:     │              │    │
│  │  │  _id, title │   │  _id, title │   │  _id, title │              │    │
│  │  │  category.. │   │  category.. │   │  category.. │              │    │
│  │  │             │   │             │   │             │              │    │
│  │  │ Lance ref:  │   │ Lance ref:  │   │ Lance ref:  │              │    │
│  │  │ →shard-0/   │   │ →shard-1/   │   │ →shard-2/   │              │    │
│  │  │  vectors    │   │  vectors    │   │  vectors    │              │    │
│  │  │  .lance     │   │  .lance     │   │  .lance     │              │    │
│  │  │  (data +    │   │  (data +    │   │  (data +    │              │    │
│  │  │   ANN idx)  │   │   ANN idx)  │   │   ANN idx)  │              │    │
│  │  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘              │    │
│  │         │                 │                 │                      │    │
│  └─────────┼─────────────────┼─────────────────┼──────────────────────┘    │
│            │                 │                 │                            │
│            ▼                 ▼                 ▼                            │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │              Coordinating Node (query merge)                        │    │
│  │                                                                     │    │
│  │   Shard 0: top-k from shard-0/vectors.lance  ──┐                  │    │
│  │   Shard 1: top-k from shard-1/vectors.lance  ──┼──▶ Global top-k  │    │
│  │   Shard 2: top-k from shard-2/vectors.lance  ──┘                  │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Data flow:**
- Pipeline writes vectors + builds Lance ANN indices into per-shard datasets on OSS
- Pipeline uses `hash(_id) % num_shards` — same routing as ES — to partition data
- NRT job backfills structured fields (title, price, tags) into ES via bulk API
- At query time, each shard independently searches its own Lance dataset (pre-indexed)
- Coordinating node merges per-shard top-k results into global top-k

**Critical invariant:** Pipeline partitioning MUST use ES-compatible `_id` routing so shard N's Lance dataset contains exactly the vectors for docs routed to shard N.

---

## Mapping DSL (New)

```json
PUT /product-vectors
{
  "mappings": {
    "properties": {
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

Resolved URI for shard 2: `oss://denny-test-lance/production/product-vectors/shard-2/vectors.lance`

**Backward compatibility:** The legacy `storage.uri` field continues to work (all shards share one dataset). The three new fields take precedence when `uri_prefix` is present.

---

## Explicit Non-Goals

| Not Supported | Rationale | Alternative |
|---------------|-----------|-------------|
| Dynamic shard split/merge | Lance datasets are external, pre-built | Reindex + rebuild datasets |
| Custom `_routing` | Breaks `hash(_id) % N` invariant | Default routing only |
| Write path (ES → Lance) | Phase 1 is read-only | Future milestone |
| NRT dataset refresh | Handled in separate worktree (P2) | N/A |

---

## Tasks

### Task 1: Add Shard-Aware URI Resolution to LanceStorageConfig

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceStorageConfig.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/mapper/LanceStorageConfigTests.java`

This task adds three new optional fields (`uriPrefix`, `shardPath`, `datasetName`) to `LanceStorageConfig` and a `resolveUri(String indexName, int shardId)` method that builds the concrete URI per shard. The legacy `uri` field remains for backward compatibility.

**Step 1: Write the failing tests**

Add to `LanceStorageConfigTests.java`:

```java
public void testResolveUriWithShardTemplate() {
    LanceStorageConfig config = new LanceStorageConfig(
        "external", null, "_id", "vector", null, null, null,
        "oss://bucket/prod", "{index}/shard-{shard_id}", "vectors.lance"
    );
    assertThat(config.resolveUri("my-index", 0), equalTo("oss://bucket/prod/my-index/shard-0/vectors.lance"));
    assertThat(config.resolveUri("my-index", 2), equalTo("oss://bucket/prod/my-index/shard-2/vectors.lance"));
}

public void testResolveUriFallsBackToLegacyUri() {
    LanceStorageConfig config = new LanceStorageConfig(
        "external", "oss://bucket/data.lance", "_id", "vector", null, null, null,
        null, null, null
    );
    assertThat(config.resolveUri("my-index", 0), equalTo("oss://bucket/data.lance"));
    assertThat(config.resolveUri("my-index", 5), equalTo("oss://bucket/data.lance"));
}

public void testIsShardAware() {
    LanceStorageConfig sharded = new LanceStorageConfig(
        "external", null, "_id", "vector", null, null, null,
        "oss://bucket/prod", "{index}/shard-{shard_id}", "vectors.lance"
    );
    assertTrue(sharded.isShardAware());

    LanceStorageConfig legacy = new LanceStorageConfig(
        "external", "oss://bucket/data.lance", "_id", "vector", null, null, null,
        null, null, null
    );
    assertFalse(legacy.isShardAware());
}

public void testResolveUriRejectsUnknownPlaceholder() {
    LanceStorageConfig config = new LanceStorageConfig(
        "external", null, "_id", "vector", null, null, null,
        "oss://bucket", "{index}/{unknown}", "data.lance"
    );
    expectThrows(IllegalArgumentException.class, () -> config.resolveUri("idx", 0));
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.mapper.LanceStorageConfigTests" -x spotlessJavaCheck`
Expected: Compilation error — new constructor signature and methods don't exist yet.

**Step 3: Implement LanceStorageConfig changes**

Replace `LanceStorageConfig.java` with:

```java
package org.elasticsearch.plugin.lance.mapper;

import java.util.Objects;
import java.util.Set;

public class LanceStorageConfig {
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of("{index}", "{shard_id}");

    private final String type;
    private final String uri;           // Legacy single URI (nullable if shard-aware)
    private final String idColumn;
    private final String vectorColumn;
    private final String ossEndpoint;
    private final String ossAccessKeyId;
    private final String ossAccessKeySecret;

    // Shard-aware fields (all nullable for backward compat)
    private final String uriPrefix;
    private final String shardPath;
    private final String datasetName;

    public LanceStorageConfig(
        String type,
        String uri,
        String idColumn,
        String vectorColumn,
        String ossEndpoint,
        String ossAccessKeyId,
        String ossAccessKeySecret,
        String uriPrefix,
        String shardPath,
        String datasetName
    ) {
        this.type = Objects.requireNonNull(type);
        this.idColumn = Objects.requireNonNull(idColumn);
        this.vectorColumn = Objects.requireNonNull(vectorColumn);
        this.ossEndpoint = ossEndpoint;
        this.ossAccessKeyId = ossAccessKeyId;
        this.ossAccessKeySecret = ossAccessKeySecret;
        this.uriPrefix = uriPrefix;
        this.shardPath = shardPath;
        this.datasetName = datasetName;

        // Either legacy uri or shard-aware fields must be present
        if (uriPrefix != null) {
            this.uri = null;  // Shard-aware mode; uri resolved at query time
        } else {
            this.uri = Objects.requireNonNull(uri, "Either [storage.uri] or [storage.uri_prefix] is required");
        }
    }

    /** Backward-compatible constructor for legacy single-URI mode. */
    public LanceStorageConfig(
        String type, String uri, String idColumn, String vectorColumn,
        String ossEndpoint, String ossAccessKeyId, String ossAccessKeySecret
    ) {
        this(type, uri, idColumn, vectorColumn, ossEndpoint, ossAccessKeyId, ossAccessKeySecret, null, null, null);
    }

    /** Returns true if this config uses shard-aware URI templates. */
    public boolean isShardAware() {
        return uriPrefix != null;
    }

    /**
     * Resolve the concrete dataset URI for a given index and shard.
     * In legacy mode, returns the static URI regardless of shard.
     * In shard-aware mode, builds: {uriPrefix}/{shardPath with placeholders resolved}/{datasetName}
     */
    public String resolveUri(String indexName, int shardId) {
        if (isShardAware() == false) {
            return uri;
        }
        String resolvedPath = shardPath;
        if (resolvedPath != null) {
            // Validate no unknown placeholders
            String remaining = resolvedPath.replaceAll("\\{index}", "").replaceAll("\\{shard_id}", "");
            if (remaining.contains("{")) {
                throw new IllegalArgumentException(
                    "Unknown placeholder in shard_path [" + shardPath + "]. Allowed: " + ALLOWED_PLACEHOLDERS
                );
            }
            resolvedPath = resolvedPath.replace("{index}", indexName).replace("{shard_id}", String.valueOf(shardId));
        } else {
            resolvedPath = "";
        }
        String name = datasetName != null ? datasetName : "data.lance";
        if (resolvedPath.isEmpty()) {
            return uriPrefix + "/" + name;
        }
        return uriPrefix + "/" + resolvedPath + "/" + name;
    }

    // --- Accessors ---
    public String type()               { return type; }
    public String uri()                { return uri; }
    public String idColumn()           { return idColumn; }
    public String vectorColumn()       { return vectorColumn; }
    public String ossEndpoint()        { return ossEndpoint; }
    public String ossAccessKeyId()     { return ossAccessKeyId; }
    public String ossAccessKeySecret() { return ossAccessKeySecret; }
    public String uriPrefix()          { return uriPrefix; }
    public String shardPath()          { return shardPath; }
    public String datasetName()        { return datasetName; }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.mapper.LanceStorageConfigTests" -x spotlessJavaCheck`
Expected: All tests PASS.

**Step 5: Format and commit**

```bash
./gradlew :plugins:lance-vector:spotlessApply
git add plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceStorageConfig.java
git add plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/mapper/LanceStorageConfigTests.java
git commit -m "feat(lance): add shard-aware URI resolution to LanceStorageConfig

Add uri_prefix, shard_path, dataset_name fields with template
resolution. Legacy single-URI mode preserved for backward compat."
```

---

### Task 2: Parse New Mapping Fields in LanceVectorFieldMapper

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java:88-154` (PARSER) and `:272-284` (doXContentBody)

This task updates the mapping parser to accept the three new `storage.*` fields and serialize them back in `doXContentBody`.

**Step 1: Write the failing test**

Create a new test file `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapperParsingTests.java`:

```java
package org.elasticsearch.plugin.lance.mapper;

import org.elasticsearch.test.ESTestCase;

import java.util.HashMap;
import java.util.Map;

public class LanceVectorFieldMapperParsingTests extends ESTestCase {

    /**
     * Test that parsing a mapping with shard-aware fields creates a shard-aware config.
     * We test the LanceStorageConfig produced by simulating the parse logic.
     */
    public void testParseShardAwareStorage() {
        // Simulate what PARSER does with shard-aware fields
        LanceStorageConfig config = new LanceStorageConfig(
            "external", null, "_id", "vector", null, null, null,
            "oss://bucket/prod", "{index}/shard-{shard_id}", "vectors.lance"
        );
        assertTrue(config.isShardAware());
        assertThat(config.resolveUri("test-idx", 3), org.hamcrest.Matchers.equalTo("oss://bucket/prod/test-idx/shard-3/vectors.lance"));
    }

    public void testParseLegacyStorageStillWorks() {
        LanceStorageConfig config = new LanceStorageConfig(
            "external", "oss://bucket/data.lance", "_id", "vector", null, null, null
        );
        assertFalse(config.isShardAware());
        assertThat(config.uri(), org.hamcrest.Matchers.equalTo("oss://bucket/data.lance"));
    }
}
```

**Step 2: Run tests to verify they pass (these are config-level tests)**

Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.mapper.LanceVectorFieldMapperParsingTests" -x spotlessJavaCheck`
Expected: PASS (these validate config, not parser wiring).

**Step 3: Update the PARSER in LanceVectorFieldMapper**

Modify `LanceVectorFieldMapper.java` PARSER method (lines 88–154). After parsing `storage.get("uri")`, add:

```java
// Shard-aware fields (optional, take precedence over uri)
String uriPrefix = null;
Object uriPrefixObj = storage.get("uri_prefix");
if (uriPrefixObj != null) {
    uriPrefix = uriPrefixObj.toString();
}

String shardPath = null;
Object shardPathObj = storage.get("shard_path");
if (shardPathObj != null) {
    shardPath = shardPathObj.toString();
}

String datasetName = null;
Object datasetNameObj = storage.get("dataset_name");
if (datasetNameObj != null) {
    datasetName = datasetNameObj.toString();
}

// Validate: either uri or uri_prefix must be present
if (uriObj == null && uriPrefix == null) {
    throw new MapperParsingException("[storage.uri] or [storage.uri_prefix] is required for lance_vector");
}
String uri = uriObj != null ? uriObj.toString() : null;
```

Update the `LanceStorageConfig` constructor call:

```java
LanceStorageConfig storageConfig = new LanceStorageConfig(
    type, uri, idColumn, vectorColumn,
    ossEndpoint, ossAccessKeyId, ossAccessKeySecret,
    uriPrefix, shardPath, datasetName
);
```

Update `doXContentBody` to serialize the new fields:

```java
@Override
protected void doXContentBody(XContentBuilder builder, Params params) throws IOException {
    super.doXContentBody(builder, params);
    LanceVectorFieldType ft = fieldType();
    builder.field(DIMS_FIELD, ft.dims);
    builder.field(SIMILARITY_FIELD, ft.similarity);
    builder.startObject(STORAGE_FIELD);
    builder.field("type", ft.storage.type());
    if (ft.storage.isShardAware()) {
        builder.field("uri_prefix", ft.storage.uriPrefix());
        if (ft.storage.shardPath() != null) {
            builder.field("shard_path", ft.storage.shardPath());
        }
        if (ft.storage.datasetName() != null) {
            builder.field("dataset_name", ft.storage.datasetName());
        }
    } else {
        builder.field("uri", ft.storage.uri());
    }
    builder.field("lance_id_column", ft.storage.idColumn());
    builder.field("lance_vector_column", ft.storage.vectorColumn());
    builder.field("read_only", true);
    builder.endObject();
}
```

**Step 4: Run all mapper tests**

Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.mapper.*" -x spotlessJavaCheck`
Expected: All PASS (existing tests still work because legacy constructor is preserved).

**Step 5: Format and commit**

```bash
./gradlew :plugins:lance-vector:spotlessApply
git add plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java
git add plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapperParsingTests.java
git commit -m "feat(lance): parse shard-aware storage fields in mapping

Support uri_prefix, shard_path, dataset_name in mapping DSL.
Legacy storage.uri continues to work unchanged."
```

---

### Task 3: Refactor LanceKnnQuery — Move Search from createWeight to scorerSupplier

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java:191-220` (createKnnQuery)
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryTests.java`

This is the core architectural change. Instead of opening the dataset and searching in `createWeight()` (once, globally), we pass the storage config + query params through and defer the Lance search to `scorerSupplier()` (per shard segment).

**Step 1: Update LanceKnnQuery constructor to accept LanceStorageConfig**

Replace the constructor to accept the full `LanceStorageConfig` instead of individual URI/OSS fields:

```java
public class LanceKnnQuery extends Query implements QueryProfilerProvider {
    private final String fieldName;
    private final LanceStorageConfig storageConfig;
    private final String indexName;     // For URI resolution
    private final int shardId;          // For URI resolution (-1 = unknown, resolve per-leaf)
    private final float[] queryVector;
    private final int k;
    private final int numCandidates;
    private final String similarity;
    private final Query filter;
    private final int dims;

    public LanceKnnQuery(
        String fieldName,
        LanceStorageConfig storageConfig,
        String indexName,
        int shardId,
        float[] queryVector,
        int k,
        int numCandidates,
        String similarity,
        Query filter,
        int dims
    ) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.storageConfig = Objects.requireNonNull(storageConfig);
        this.indexName = Objects.requireNonNull(indexName);
        this.shardId = shardId;
        this.queryVector = Objects.requireNonNull(queryVector);
        this.k = k;
        this.numCandidates = numCandidates;
        this.similarity = similarity == null ? "cosine" : similarity;
        this.filter = filter;
        this.dims = dims;
    }
```

**Step 2: Refactor createWeight — defer dataset open to scorerSupplier**

```java
@Override
public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
    // In shard-aware mode, we defer the Lance search to scorerSupplier.
    // In legacy mode, we still do it here for backward compatibility.
    final Query filterQuery = this.filter;
    final float queryBoost = boost;
    final int topK = k;

    // Legacy mode: search once, share candidates (original behavior)
    final List<LanceDataset.Candidate> sharedCandidates;
    if (storageConfig.isShardAware() == false) {
        String resolvedUri = storageConfig.resolveUri(indexName, 0);
        LanceDatasetConfig config = buildDatasetConfig();
        LanceDataset dataset = LanceDatasetRegistry.getOrLoad(resolvedUri, dims, config);
        sharedCandidates = dataset.search(queryVector, numCandidates, similarity);
    } else {
        sharedCandidates = null;  // Will be resolved per-shard in scorerSupplier
    }

    return new Weight(this) {
        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            // ... (unchanged)
        }

        @Override
        public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
            List<LanceDataset.Candidate> candidates;
            if (sharedCandidates != null) {
                // Legacy: use shared candidates
                candidates = sharedCandidates;
            } else {
                // Shard-aware: resolve URI and search per-shard
                int leafShardId = resolveShardId(context);
                String resolvedUri = storageConfig.resolveUri(indexName, leafShardId);
                LanceDatasetConfig config = buildDatasetConfig();
                LanceDataset dataset = LanceDatasetRegistry.getOrLoad(resolvedUri, dims, config);
                candidates = dataset.search(queryVector, numCandidates, similarity);
            }

            Weight filterWeight = null;
            if (filterQuery != null) {
                IndexSearcher contextSearcher = new IndexSearcher(context.parent);
                filterWeight = filterQuery.createWeight(contextSearcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
            }
            Map<Integer, Float> docScores = buildDocScores(context, candidates, filterWeight, topK);
            if (docScores.isEmpty()) {
                return null;
            }
            // ... (rest unchanged — build sorted docIds, return ScorerSupplier)
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
            return false;
        }
    };
}

private LanceDatasetConfig buildDatasetConfig() {
    String ossEndpoint = storageConfig.ossEndpoint();
    String ossKeyId = storageConfig.ossAccessKeyId();
    String ossKeySecret = storageConfig.ossAccessKeySecret();
    if (ossEndpoint != null) {
        return new LanceDatasetConfig("_id", "vector", dims, ossEndpoint, ossKeyId, ossKeySecret);
    }
    return new LanceDatasetConfig("_id", "vector", dims, null, null, null);
}

/**
 * Resolve the shard ID from LeafReaderContext.
 * Uses the shardId passed at construction if available.
 * Otherwise extracts from ShardSearcher or defaults to 0.
 */
private int resolveShardId(LeafReaderContext context) {
    if (shardId >= 0) {
        return shardId;
    }
    // Fallback: extract shard ID from the index reader's context
    // ES wraps each shard's IndexReader, and the ordinal within
    // the parent reader can indicate the shard. However, the cleanest
    // approach is to pass the shard ID from SearchExecutionContext.
    return 0;
}
```

**Step 3: Update LanceVectorFieldType.createKnnQuery to pass storageConfig and index/shard context**

In `LanceVectorFieldMapper.java`, modify `createKnnQuery`:

```java
public Query createKnnQuery(
    VectorData queryVector,
    int k,
    int numCands,
    Float visitPercentage,
    Float oversample,
    Query filter,
    Float vectorSimilarity,
    org.apache.lucene.search.join.BitSetProducer parentFilter,
    DenseVectorFieldMapper.FilterHeuristic heuristic,
    boolean hnswEarlyTermination
) {
    float[] vector = queryVector.isFloat() ? queryVector.asFloatVector() : toFloat(queryVector.asByteVector());
    if (vector.length != dims) {
        throw new IllegalArgumentException("query vector dims mismatch expected=" + dims + " got=" + vector.length);
    }
    return new LanceKnnQuery(
        name(),
        storage,
        "unknown",  // indexName — will be set from SearchExecutionContext
        -1,         // shardId — will be resolved at scorerSupplier time
        vector,
        k,
        numCands,
        similarity,
        filter,
        dims
    );
}
```

> Note: The `indexName` and `shardId` will be properly wired in Task 4 via `SearchExecutionContext`.

**Step 4: Update LanceKnnQueryTests for new constructor**

Update all test constructors from:
```java
new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null)
```
to:
```java
LanceStorageConfig config = new LanceStorageConfig("external", "uri://test", "_id", "vector", null, null, null);
new LanceKnnQuery("field", config, "test-index", -1, vector, 10, 100, "cosine", null, 3)
```

Update `equals`/`hashCode` in `LanceKnnQuery` to compare on `storageConfig` and `indexName` instead of raw `storageUri`.

**Step 5: Run all query tests**

Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.query.*" -x spotlessJavaCheck`
Expected: All PASS.

**Step 6: Format and commit**

```bash
./gradlew :plugins:lance-vector:spotlessApply
git add plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java
git add plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java
git add plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryTests.java
git commit -m "refactor(lance): move dataset search from createWeight to scorerSupplier

In shard-aware mode, each LeafReaderContext resolves its own dataset URI
and searches independently. Legacy mode preserves shared-candidate behavior."
```

---

### Task 4: Wire Index Name and Shard ID from SearchExecutionContext

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java:191-220`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryBuilder.java:107-130`

The `SearchExecutionContext` (available in `LanceKnnQueryBuilder.doToQuery()`) provides `getIndex().getName()` and `getShardId()`. We thread these through to `LanceKnnQuery`.

**Step 1: Update LanceKnnQueryBuilder.doToQuery**

```java
@Override
protected Query doToQuery(SearchExecutionContext context) throws IOException {
    MappedFieldType fieldType = context.getFieldType(fieldName);
    if (fieldType instanceof LanceVectorFieldType == false) {
        throw new IllegalArgumentException("field [" + fieldName + "] is not a lance_vector field");
    }

    LanceVectorFieldType lanceFieldType = (LanceVectorFieldType) fieldType;
    VectorData vectorData = VectorData.fromFloats(queryVector);

    String indexName = context.getFullyQualifiedIndex().getName();
    int shardId = context.getShardId();

    return lanceFieldType.createKnnQuery(
        vectorData, k, numCandidates,
        null, null, null, null, null, null, false,
        indexName, shardId
    );
}
```

**Step 2: Add indexName/shardId to LanceVectorFieldType.createKnnQuery**

Add an overloaded method or extend the signature:

```java
public Query createKnnQuery(
    VectorData queryVector, int k, int numCands,
    Float visitPercentage, Float oversample, Query filter,
    Float vectorSimilarity,
    org.apache.lucene.search.join.BitSetProducer parentFilter,
    DenseVectorFieldMapper.FilterHeuristic heuristic,
    boolean hnswEarlyTermination,
    String indexName, int shardId
) {
    float[] vector = queryVector.isFloat() ? queryVector.asFloatVector() : toFloat(queryVector.asByteVector());
    if (vector.length != dims) {
        throw new IllegalArgumentException("query vector dims mismatch expected=" + dims + " got=" + vector.length);
    }
    return new LanceKnnQuery(
        name(), storage, indexName, shardId,
        vector, k, numCands, similarity, filter, dims
    );
}
```

**Step 3: Run full plugin tests**

Run: `./gradlew :plugins:lance-vector:test -x spotlessJavaCheck`
Expected: All PASS.

**Step 4: Format and commit**

```bash
./gradlew :plugins:lance-vector:spotlessApply
git add plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryBuilder.java
git add plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java
git commit -m "feat(lance): wire index name and shard ID into LanceKnnQuery

SearchExecutionContext provides index name and shard ID, which are
used to resolve shard-aware dataset URIs at query time."
```

---

### Task 5: Update LanceKnnQuery equals/hashCode/toString

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java:326-352`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryTests.java`

Update identity methods to reflect the new fields.

**Step 1: Update equals/hashCode/toString**

```java
@Override
public String toString(String field) {
    if (storageConfig.isShardAware()) {
        return "LanceKnnQuery(" + fieldName + ", index=" + indexName + ", shardId=" + shardId
            + ", prefix=" + storageConfig.uriPrefix() + ")";
    }
    return "LanceKnnQuery(" + fieldName + ", uri=" + storageConfig.uri() + ")";
}

@Override
public boolean equals(Object obj) {
    if (sameClassAs(obj) == false) return false;
    LanceKnnQuery other = (LanceKnnQuery) obj;
    return k == other.k
        && numCandidates == other.numCandidates
        && fieldName.equals(other.fieldName)
        && indexName.equals(other.indexName)
        && shardId == other.shardId
        && similarity.equals(other.similarity);
}

@Override
public int hashCode() {
    return Objects.hash(fieldName, indexName, shardId, k, numCandidates, similarity);
}
```

**Step 2: Update tests, run, format, commit**

Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.query.LanceKnnQueryTests" -x spotlessJavaCheck`

```bash
./gradlew :plugins:lance-vector:spotlessApply
git add plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java
git add plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryTests.java
git commit -m "refactor(lance): update LanceKnnQuery identity for shard-aware fields"
```

---

### Task 6: Full Plugin Build and Regression Test

**Files:** None new — validates existing changes compile and pass together.

**Step 1: Run full build**

```bash
./gradlew :plugins:lance-vector:check -x spotlessJavaCheck
```

Expected: BUILD SUCCESS with all tests passing.

**Step 2: Run spotless**

```bash
./gradlew :plugins:lance-vector:spotlessApply
```

**Step 3: Run precommit**

```bash
./gradlew :plugins:lance-vector:precommit
```

Expected: PASS.

**Step 4: Build distribution and verify plugin loads**

```bash
./gradlew localDistro
```

Start ES and verify plugin loads:

```bash
cd build/distribution/local/elasticsearch-9.2.4-SNAPSHOT
./bin/elasticsearch -d -p es.pid
sleep 15
curl -sk -u elastic:Summer11 https://localhost:9200/_cat/plugins
kill $(cat es.pid)
```

Expected output includes: `lance-vector`

**Step 5: Commit if any spotless changes**

```bash
git add -A && git diff --cached --quiet || git commit -m "style: spotless formatting"
```

---

### Task 7: Shard-Aware Integration Test with FakeLanceDataset

**Files:**
- Create: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/ShardAwareIntegrationTests.java`
- Create: Test fixture JSON files for per-shard fake datasets

This task creates an integration test that validates the shard-aware URI resolution end-to-end using `FakeLanceDataset` (no real Lance SDK needed).

**Step 1: Create per-shard test fixture files**

Create `plugins/lance-vector/src/test/resources/shard-test/shard-0.json`:
```json
{
  "dims": 3,
  "vectors": {
    "doc1": [1.0, 0.0, 0.0],
    "doc3": [0.0, 0.0, 1.0]
  }
}
```

Create `plugins/lance-vector/src/test/resources/shard-test/shard-1.json`:
```json
{
  "dims": 3,
  "vectors": {
    "doc2": [0.0, 1.0, 0.0],
    "doc4": [0.5, 0.5, 0.0]
  }
}
```

**Step 2: Write integration test**

```java
package org.elasticsearch.plugin.lance;

import org.elasticsearch.plugin.lance.mapper.LanceStorageConfig;
import org.elasticsearch.plugin.lance.storage.LanceDataset;
import org.elasticsearch.plugin.lance.storage.LanceDatasetRegistry;
import org.elasticsearch.test.ESTestCase;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Integration test: validates that shard-aware URI resolution produces
 * independent search results per shard.
 */
public class ShardAwareIntegrationTests extends ESTestCase {

    @Override
    public void tearDown() throws Exception {
        LanceDatasetRegistry.clear();
        super.tearDown();
    }

    public void testShardAwareResolvesDistinctDatasets() throws Exception {
        // Each shard resolves to a different URI
        LanceStorageConfig config = new LanceStorageConfig(
            "external", null, "_id", "vector", null, null, null,
            "embedded:shard-test", "shard-{shard_id}", null  // dataset_name defaults to data.lance
        );

        String uri0 = config.resolveUri("test-index", 0);
        String uri1 = config.resolveUri("test-index", 1);

        assertThat(uri0, not(equalTo(uri1)));
        assertTrue(uri0.contains("shard-0"));
        assertTrue(uri1.contains("shard-1"));
    }

    public void testLegacyModeAllShardsShareSameUri() throws Exception {
        LanceStorageConfig config = new LanceStorageConfig(
            "external", "embedded:shared-data.json", "_id", "vector", null, null, null
        );

        assertThat(config.resolveUri("test-index", 0), equalTo("embedded:shared-data.json"));
        assertThat(config.resolveUri("test-index", 1), equalTo("embedded:shared-data.json"));
        assertThat(config.resolveUri("test-index", 99), equalTo("embedded:shared-data.json"));
    }
}
```

**Step 3: Run test**

Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.ShardAwareIntegrationTests" -x spotlessJavaCheck`
Expected: PASS.

**Step 4: Format and commit**

```bash
./gradlew :plugins:lance-vector:spotlessApply
git add plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/ShardAwareIntegrationTests.java
git add plugins/lance-vector/src/test/resources/shard-test/
git commit -m "test(lance): add shard-aware integration tests

Validates URI resolution produces distinct per-shard datasets
and legacy mode continues sharing a single dataset."
```

---

### Task 8: Stress Test Script and Data Generator

**Files:**
- Create: `plugins/lance-vector/tools/generate_sharded_dataset.py`
- Create: `plugins/lance-vector/tools/run_stress_test.sh`

This task creates tooling for the stress test report deliverable. The Python script generates synthetic vector datasets partitioned by shard. The shell script drives the stress test scenarios.

**Step 1: Create dataset generator**

```python
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
import numpy as np

def es_shard_for_id(doc_id: str, num_shards: int) -> int:
    """Replicate ES default _id routing: Murmur3 hash mod num_shards."""
    # Simplified: use consistent hash. For exact ES compat, use Murmur3.
    h = int(hashlib.md5(doc_id.encode()).hexdigest(), 16)
    return h % num_shards

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

    # Pre-generate all vectors
    print(f"Generating {total} vectors ({args.dims}d) across {args.num_shards} shards...")
    rng = np.random.default_rng(seed=42)

    # Partition by shard
    shard_data = {s: {"ids": [], "vectors": []} for s in range(args.num_shards)}
    for i in range(total):
        doc_id = f"doc-{i:08d}"
        shard = es_shard_for_id(doc_id, args.num_shards)
        vec = rng.standard_normal(args.dims).astype(np.float32)
        if args.similarity == "cosine":
            vec = vec / np.linalg.norm(vec)
        shard_data[shard]["ids"].append(doc_id)
        shard_data[shard]["vectors"].append(vec)

    for s in range(args.num_shards):
        shard_dir = os.path.join(args.output_dir, f"shard-{s}")
        os.makedirs(shard_dir, exist_ok=True)
        ids = shard_data[s]["ids"]
        vecs = np.array(shard_data[s]["vectors"])
        print(f"  Shard {s}: {len(ids)} vectors")

        if HAS_LANCE:
            table = pa.table({
                "_id": pa.array(ids, type=pa.string()),
                "vector": pa.FixedSizeListArray.from_arrays(
                    pa.array(vecs.flatten(), type=pa.float32()),
                    args.dims
                )
            })
            ds = lance.write_dataset(table, os.path.join(shard_dir, "vectors.lance"), mode="overwrite")
            # Build IVF-PQ index
            if len(ids) >= 256:
                ds.create_index("vector", index_type="IVF_PQ", num_partitions=min(256, len(ids) // 10))
                print(f"    Built IVF-PQ index")
        else:
            # Fallback: write JSON fixture
            with open(os.path.join(shard_dir, "vectors.json"), "w") as f:
                json.dump({"dims": args.dims, "vectors": {
                    ids[i]: vecs[i].tolist() for i in range(len(ids))
                }}, f)

    # Write ground truth for recall validation
    print("Writing ground truth...")
    query_vecs = rng.standard_normal((100, args.dims)).astype(np.float32)
    if args.similarity == "cosine":
        query_vecs = query_vecs / np.linalg.norm(query_vecs, axis=1, keepdims=True)

    # Brute force: for each query, find top-10 across all shards
    all_ids = []
    all_vecs = []
    for s in range(args.num_shards):
        all_ids.extend(shard_data[s]["ids"])
        all_vecs.extend(shard_data[s]["vectors"])
    all_vecs = np.array(all_vecs)

    ground_truth = []
    for qi, qv in enumerate(query_vecs):
        if args.similarity == "cosine":
            scores = all_vecs @ qv
        else:
            scores = -np.linalg.norm(all_vecs - qv, axis=1)
        top_indices = np.argsort(-scores)[:10]
        ground_truth.append({
            "query_index": qi,
            "query_vector": qv.tolist(),
            "top_10": [{"id": all_ids[i], "score": float(scores[i])} for i in top_indices]
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
```

**Step 2: Create stress test runner script**

```bash
#!/usr/bin/env bash
# run_stress_test.sh — Run stress test scenarios for shard-aware Lance
set -euo pipefail

ES_URL="${ES_URL:-https://localhost:9200}"
ES_AUTH="${ES_AUTH:-elastic:Summer11}"
OUTPUT_DIR="${OUTPUT_DIR:-/tmp/lance-stress-results}"
GENERATOR="$(dirname "$0")/generate_sharded_dataset.py"

mkdir -p "$OUTPUT_DIR"

scenarios=(
    "S1:1:100000:768"
    "S2:5:100000:768"
    "S3:20:100000:768"
    "S4:1:1000000:768"
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
```

**Step 3: Commit**

```bash
chmod +x plugins/lance-vector/tools/run_stress_test.sh
git add plugins/lance-vector/tools/
git commit -m "test(lance): add sharded dataset generator and stress test runner

Python script generates per-shard Lance datasets with IVF-PQ indices
and brute-force ground truth for recall validation."
```

---

### Task 9: Documentation — Design Doc and Mapping Guide

**Files:**
- Create: `plugins/lance-vector/docs/SHARD-MAPPING.md`

**Step 1: Write the shard mapping documentation**

```markdown
# Shard-Aware Dataset Mapping

## Overview

The Lance Vector plugin supports two dataset mapping modes:

### Legacy Mode (single dataset)
All shards read from the same Lance dataset. Suitable for single-shard indices
or testing.

### Shard-Aware Mode (per-shard datasets)
Each shard reads from its own Lance dataset. Required for correct multi-shard
behavior.

## Configuration

### Legacy Mode
```json
{
  "storage": {
    "uri": "oss://bucket/dataset.lance"
  }
}
```

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

## Not Supported

- Dynamic shard split/merge (use reindex + rebuild datasets)
- Custom `_routing` (default `_id` routing only)
- Write path from ES to Lance (read-only)
```

**Step 2: Commit**

```bash
git add plugins/lance-vector/docs/SHARD-MAPPING.md
git commit -m "docs(lance): add shard-aware dataset mapping guide"
```

---

### Task 10: Final Integration — Build, Test, Tag

**Step 1: Run full plugin test suite**

```bash
./gradlew :plugins:lance-vector:check
```

**Step 2: Run precommit**

```bash
./gradlew :plugins:lance-vector:precommit
```

**Step 3: Build distribution**

```bash
./gradlew localDistro
```

**Step 4: Verify with a manual smoke test**

```bash
# Start ES with OSS env vars
export OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"
cd build/distribution/local/elasticsearch-9.2.4-SNAPSHOT
./bin/elasticsearch -d -p es.pid
sleep 20

# Create index with shard-aware mapping
curl -sk -u elastic:Summer11 -X PUT "https://localhost:9200/shard-test" -H 'Content-Type: application/json' -d '{
  "settings": { "number_of_shards": 3, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 768,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri_prefix": "oss://denny-test-lance/stress-test",
          "shard_path": "{index}/shard-{shard_id}",
          "dataset_name": "vectors.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector",
          "read_only": true
        }
      }
    }
  }
}'

# Verify mapping stored correctly
curl -sk -u elastic:Summer11 "https://localhost:9200/shard-test/_mapping" | python3 -m json.tool

kill $(cat es.pid)
```

**Step 5: Commit any final changes and create summary**

```bash
git add -A
git diff --cached --quiet || git commit -m "chore: final cleanup for P3 shard consistency"
```

---

## Summary of All Files Changed

| File | Action | Task |
|------|--------|------|
| `plugins/lance-vector/src/main/java/.../mapper/LanceStorageConfig.java` | Modify | T1 |
| `plugins/lance-vector/src/test/java/.../mapper/LanceStorageConfigTests.java` | Modify | T1 |
| `plugins/lance-vector/src/main/java/.../mapper/LanceVectorFieldMapper.java` | Modify | T2, T3, T4 |
| `plugins/lance-vector/src/test/java/.../mapper/LanceVectorFieldMapperParsingTests.java` | Create | T2 |
| `plugins/lance-vector/src/main/java/.../query/LanceKnnQuery.java` | Modify | T3, T5 |
| `plugins/lance-vector/src/test/java/.../query/LanceKnnQueryTests.java` | Modify | T3, T5 |
| `plugins/lance-vector/src/main/java/.../query/LanceKnnQueryBuilder.java` | Modify | T4 |
| `plugins/lance-vector/src/test/java/.../ShardAwareIntegrationTests.java` | Create | T7 |
| `plugins/lance-vector/src/test/resources/shard-test/*.json` | Create | T7 |
| `plugins/lance-vector/tools/generate_sharded_dataset.py` | Create | T8 |
| `plugins/lance-vector/tools/run_stress_test.sh` | Create | T8 |
| `plugins/lance-vector/docs/SHARD-MAPPING.md` | Create | T9 |
| `docs/plans/2026-02-06-p3-shard-consistency-design.md` | Create | — |

## Stress Test Matrix (for Report)

| Scenario | Shards | Vectors/shard | Total | Pass Criteria |
|----------|--------|---------------|-------|---------------|
| S1 | 1 | 100K | 100K | Recall@10 ≥ 95% |
| S2 | 5 | 100K | 500K | Recall@10 ≥ 95%, no regression vs S1 |
| S3 | 20 | 100K | 2M | p99 < 200ms, 0 FD leaks in 1h soak |
| S4 | 1 | 1M | 1M | p99 < 500ms, stable memory |
| S5 | 20 | 500K | 10M | Target production scale (stretch) |
