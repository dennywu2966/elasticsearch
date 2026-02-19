# Lance Vector Plugin 架构设计文档

## 文档概述

本文档详细描述了 Lance Vector Plugin 的架构设计，包括 P1-P2-P3 阶段的完整实现。

**版本**: 9.2.4
**更新日期**: 2026-02-07
**状态**: 生产就绪 (P1-P2-P3 已合并完成)

---

## 目录

- [系统架构概览](#系统架构概览)
- [P1-P2-P3 功能特性](#p1-p2-p3-功能特性)
- [核心组件设计](#核心组件设计)
- [数据流设计](#数据流设计)
- [性能优化策略](#性能优化策略)
- [安全与可靠性](#安全与可靠性)
- [扩展性设计](#扩展性设计)

---

## 系统架构概览

### 整体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Elasticsearch 9.2.4                           │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                   Lance Vector Plugin                               │  │
│  │                                                                       │  │
│  │  ┌─────────────┐  Query Layer  ┌──────────────┐  Storage Layer     │  │
│  │  │             │ ─────────────▶│               │ ──────────────────▶  │  │
│  │  │  DSL Parser │              │  Lance KNN     │                   │  │
│  │  │  (Builder) │              │  Query        │                   │  │
│  │  │             │              │               │  ┌──────────────┐    │  │
│  │  └─────────────┘              └──────────────┘  │              │    │  │
│  │                                                │  Lance        │    │  │
│  │  ┌─────────────┐  Configuration Layer                 │  Dataset     │    │  │
│  │  │Field Mapper│ ──────────────────────────────────────│  (Registry)   │    │  │
│  │  │             │                                        │              │    │  │
│  │  │  - Validate│                                        │  ┌──────────┐ │    │  │
│  │  │  - Parse   │                                        │  │  Cache   │ │    │  │
│  │  │  - Create  │                                        │  │  (LRU)   │ │    │  │
│  │  │             │                                        │  └──────────┘ │    │  │
│  │  └─────────────┘                                        │              │    │  │
│  │                                                        │              │    │  │
│  │  ┌─────────────┐  Refresh Layer (P1-P2)                    │              │    │  │
│  │  │  Refresh    │ ──────────────────────────────────────▶│              │    │  │
│  │  │  Service    │                                        │  ┌──────────┐ │    │  │
│  │  │             │                                        │  │Versioned │ │    │  │
│  │  │  - Poll     │                                        │  │  Dataset │ │    │  │
│  │  │  - Detect   │                                        │  │(Atomic) │ │    │  │
│  │  │  - Swap     │                                        │  └──────────┘ │    │  │
│  │  └─────────────┘                                        │              │    │  │
│  │                                                        │              │    │  │
│  │  ┌─────────────┐  Metrics Layer                              │              │    │  │
│  │  │  Metrics    │ ◀─────────────────────────────────────────│              │    │  │
│  │  │             │                                        │  ┌──────────┐ │    │  │
│  │  │  - Search   │                                        │  │  Stats    │ │    │  │
│  │  │  - Refresh  │                                        │  │Collector │ │    │  │
│  │  │  - Cache    │                                        │  └──────────┘ │    │  │
│  │  └─────────────┘                                        │              │    │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                      │                                          │           │
│                      ▼                                          ▼           │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │                   External Storage                             │  │
│  │                                                                  │  │
│  │  ┌─────────────┐           ┌────────────┐      ┌─────────┐  │  │
│  │  │  Local FS   │           │   OSS      │      │   S3    │  │  │
│  │  │  file://    │           │   oss://   │      │  s3://   │  │  │
│  │  └─────────────┘           └────────────┘      └─────────┘  │  │
│  └─────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### 分层职责

| 层级 | 职责 | 核心类 |
|------|------|--------|
| **Query Layer** | 查询解析、向量搜索执行 | `LanceKnnQueryBuilder`, `LanceKnnQuery` |
| **Configuration Layer** | 字段类型定义、配置验证 | `LanceVectorFieldMapper`, `LanceStorageConfig` |
| **Storage Layer** | 数据集加载、缓存管理 | `LanceDatasetRegistry`, `VersionedDataset` |
| **Refresh Layer** | NRT 刷新调度 | `LanceRefreshScheduler`, `LanceRefreshService` |
| **Metrics Layer** | 统计指标收集 | `LanceSearchMetrics`, `LanceRefreshMetrics` |

---

## P1-P2-P3 功能特性

### P1: 查询语义与性能

#### P1.1: 过滤器支持

**设计目标**: 支持与 Elasticsearch DSL 一致的过滤器语法

**实现方案**:
- **DSL 解析**: 在 `LanceKnnQueryBuilder` 中解析 `filter` 参数
- **后过滤**: 在 `LanceKnnQuery.buildDocScores()` 中应用过滤器位集
- **策略选择**: 根据过滤器选择性自动选择预过滤或后过滤

**数据流**:
```
用户查询
  → LanceKnnQueryBuilder.doToQuery()
     解析 filter 参数
  → LanceKnnQuery.createWeight()
     构建 QueryWeight
  → LanceKnnQuery.scorerSupplier()
     ┌─────────────────────────────┐
     │  Phase 1: 评估过滤器           │
     │  - 创建 IndexSearcher        │
     │  - 构建 filter bitset        │
     └─────────────────────────────┘
     ┌─────────────────────────────┐
     │  Phase 2: 执行向量搜索       │
     │  - 加载/搜索 Lance 数据集     │
     │  - 获取 num_candidates       │
     └─────────────────────────────┘
     ┌─────────────────────────────┐
     │  Phase 3: _id 连接 + 过滤     │
     │  - 通过 _id 连接 Lucene 文档  │
     │  - 应用 filter bitset        │
     │  - 保留 top-k 结果           │
     └─────────────────────────────┘
```

#### P1.2: 参数控制

**nprobes 参数**:
- **作用**: 控制 IVF-PQ 索引探测的分区数量
- **范围**: 1-100
- **默认值**: 20
- **解析优先级**: 查询级 > 索引级 > 环境变量 > 硬编码默认值

**oversampling 参数**:
- **作用**: 有过滤器时增加候选数量
- **默认值**: 3.0
- **计算公式**: `effectiveCandidates = numCandidates * oversampleFactor`

#### P1.3: 预过滤策略

**决策逻辑** (`PreFilterHeuristic`):
```
if (filteredDocCount < k * 2):
    return PRE_FILTER  // 预过滤：推送 ID 到 Lance SDK
else:
    return POST_FILTER // 后过滤：交集过滤
```

**实现状态**:
- ✅ 后过滤策略已实现
- ✅ AUTO 模式自动选择
- ⏸️ 预过滤策略（需要 Lance SDK 支持）

### P2: NRT 近实时刷新

#### P2.1: 刷新间隔配置

**集群级别设置**:
```java
Setting<TimeValue> LANCE_REFRESH_INTERVAL = Setting.timeSetting(
    "lance.refresh.interval",    // 集群设置
    TimeValue.timeValueMinutes(5), // 默认 5 分钟
    TimeValue.MINUS_ONE,          // -1 = 禁用
    Setting.Property.Dynamic
);
```

**索引级别设置**:
```java
Setting<TimeValue> INDEX_REFRESH_INTERVAL = Setting.timeSetting(
    "index.lance.refresh.interval",
    TimeValue.MINUS_ONE,          // 继承集群设置
    TimeValue.MINUS_ONE,
    Setting.Property.IndexScope,
    Setting.Property.Dynamic
);
```

#### P2.2: Manifest 变更检测

**检测机制**:

| 存储类型 | 检测方法 | 延迟 |
|---------|----------|------|
| Local FS | 文件修改时间 (`_latest.manifest`) | ~0ms |
| OSS | HTTP HEAD → ETag/Last-Modified | ~50-200ms |
| S3 | HTTP HEAD → ETag | ~50-200ms |

**实现接口**:
```java
public interface LanceManifestChecker {
    /**
     * 检查数据集是否已更新
     * @param uri 数据集 URI
     * @param lastVersion 上次检测的版本
     * @return 新版本字符串，如果未更新则返回 null
     */
    String checkForUpdate(String uri, String lastVersion) throws IOException;
}
```

#### P2.3: 原子交换 (Atomic Swap)

**VersionedDataset 设计**:
```java
class VersionedDataset implements Closeable {
    final LanceDataset dataset;           // 实际数据集
    final String version;                 // 版本标识
    final long loadedAtMillis;           // 加载时间
    final AtomicInteger refCount;         // 活跃查询引用数

    void acquire() { refCount.incrementAndGet(); }

    void release() {
        if (refCount.decrementAndGet() == 0 && markedForClose) {
            dataset.close();
        }
    }
}
```

**交换流程**:
```
1. RefreshScheduler 检测到版本变化
2. 加载新版本数据集 newDataset
3. 创建 new VersionedDataset(newDataset, newVersion)
4. cache.put(uri, newVersionedDataset)  // 原子操作
5. oldVersionedDataset.markForClose()
6. 旧数据集在所有引用释放后自动关闭
```

**查询路径**:
```java
VersionedDataset vd = registry.acquire(uri);
try {
    List<Candidate> results = vd.dataset.search(...);
    // 构建评分
} finally {
    vd.release();  // 释放引用
}
```

#### P2.4: 手动刷新 API

**REST 端点**: `POST /_lance/refresh`

**请求格式**:
```json
{
  "indices": ["my-index"],      // 可选：指定索引
  "uris": ["oss://bucket/path"], // 可选：指定 URI
  "force": true                 // 可选：强制刷新
}
```

**响应格式**:
```json
{
  "acknowledged": true,
  "refreshed": ["oss://bucket/path/dataset.lance"],
  "skipped": [],
  "errors": {},
  "took_ms": 150
}
```

#### P2.5: 刷新指标

**指标收集** (`LanceRefreshMetrics`):
- `refresh_count`: 刷新成功次数
- `refresh_failure_count`: 刷新失败次数
- `refresh_skip_count`: 版本未变化跳过次数
- `last_refresh_duration_ms`: 最后一次刷新耗时
- `last_refresh_timestamp`: 最后一次刷新时间戳
- `active_version`: 当前活跃版本

**API 端点**: `GET /_lance/stats`

### P3: 分片感知数据集映射

#### P3.1: URI 模板解析

**模板占位符**:
- `{index}`: 索引名称
- `{shard_id}`: 分片 ID

**配置示例**:
```json
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "storage": {
          "type": "external",
          "uri_prefix": "oss://bucket/data/",
          "shard_path": "{index}/shard-{shard_id}",
          "dataset_name": "vectors.lance"
        }
      }
    }
  }
}
```

**URI 解析** (`LanceStorageConfig.resolveUri`):
```java
public String resolveUri(String indexName, int shardId) {
    if (isShardAware() == false) {
        return uri;  // 传统模式：直接使用 URI
    }
    // 分片感知模式：解析模板
    String resolvedPath = shardPath;
    if (resolvedPath != null) {
        resolvedPath = resolvedPath
            .replace("{index}", indexName)
            .replace("{shard_id}", String.valueOf(shardId));
    }
    String name = datasetName != null ? datasetName : "data.lance";
    if (resolvedPath.isEmpty()) {
        return uriPrefix + "/" + name;
    }
    return uriPrefix + "/" + resolvedPath + "/" + name;
}
```

**实际解析示例**:
- 输入: `uri_prefix="oss://bucket/"`, `shard_path="{index}/shard-{shard_id}"`
- 对于 `index="products"`, `shardId=1` → `oss://bucket/products/shard-1/data.lance`
- 对于 `index="orders"`, `shardId=2` → `oss://bucket/orders/shard-2/data.lance`

#### P3.2: 查询执行模式

**模式 1: 传统模式** (单一 URI)
```
createWeight()
  → 加载数据集一次
  → 所有分片共享候选结果
  → scorerSupplier() 应用分片本地过滤
```

**模式 2: 分片感知模式** (URI 模板)
```
createWeight()
  → 不加载数据集
scorerSupplier()
  → 解析分片特定的 URI
  → 加载分片专属数据集
  → 执行搜索
  → 连接分片本地文档
```

**向后兼容**:
- `shardId = -1` 表示传统模式
- `shardId >= 0` 表示分片感知模式
- 两种模式可以在同一集群中共存

---

## 核心组件设计

### 1. LanceKnnQuery - 向量查询执行器

**职责**:
- 解析并执行 kNN 查询
- 管理过滤器应用逻辑
- 连接 Lucene 文档与 Lance 候选结果

**关键方法**:
```java
// 创建查询权重
public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)

// 获取评分供应者
public ScorerSupplier scorerSupplier(LeafReaderContext context)

// 构建文档评分（核心搜索逻辑）
private Map<Integer, Float> buildDocScores(
    LeafReaderContext context,
    List<LanceDataset.Candidate> sharedCandidates,
    Query filter,
    int k,
    LanceTimingContext timing
)
```

**执行阶段**:
1. **过滤器评估**: 创建 filter bitset
2. **候选获取**: 从 Lance 数据集获取候选（或使用共享候选）
3. **_id 连接**: 通过 `_id` 连接 Lucene 文档
4. **过滤应用**: 应用 filter bitset
5. **Top-K 保留**: 保留评分最高的 k 个结果

### 2. LanceDatasetRegistry - 数据集注册表

**职责**:
- 管理数据集缓存（LRU 策略）
- 处理数据集加载和版本管理
- 提供线程安全的数据集访问

**核心接口**:
```java
// 获取数据集（增加引用计数）
VersionedDataset acquire(String uri) throws IOException

// 释放数据集（减少引用计数）
void release(String uri)

// 重新加载数据集
void reload(String uri) throws IOException

// 清理缓存
void invalidate(String uri)
```

**缓存配置**:
```java
Setting<Integer> LANCE_CACHE_MAX_SIZE = Setting.intSetting(
    "lance.cache.max_size",
    100,    // 默认缓存 100 个数据集
    1,
    Setting.Property.NodeScope
);
```

### 3. VersionedDataset - 版本化数据集包装器

**职责**:
- 包装 Lance 数据集，添加版本管理
- 实现引用计数，支持原子交换
- 确保进行中的查询不受刷新影响

**设计要点**:
```java
public final class VersionedDataset implements Closeable {
    private final LanceDataset dataset;
    private final String version;
    private final AtomicInteger refCount = new AtomicInteger(0);
    private volatile boolean markedForClose = false;

    // 增加引用（查询开始前调用）
    public void acquire() {
        refCount.incrementAndGet();
    }

    // 释放引用（查询结束后调用）
    public void release() {
        if (refCount.decrementAndGet() == 0 && markedForClose) {
            // 所有引用释放，且标记为关闭
            close();
        }
    }

    // 标记为关闭（新版本加载后调用）
    public void markForClose() {
        markedForClose = true;
    }

    @Override
    public void close() {
        dataset.close();
    }
}
```

**线程安全性**:
- 使用 `AtomicInteger` 实现无锁引用计数
- `dataset` 和 `version` 为 final，不可变
- 支持并发读取和写入

### 4. LanceRefreshService - 刷新服务

**职责**:
- 定期检查数据集版本变化
- 协调数据集重新加载
- 收集刷新指标

**核心逻辑**:
```java
public class LanceRefreshService implements Runnable {
    private final LanceDatasetRegistry registry;
    private final LanceManifestChecker manifestChecker;
    private final LanceRefreshMetrics metrics;

    @Override
    public void run() {
        for (Map.Entry<String, DatasetConfig> entry : datasets.entrySet()) {
            String uri = entry.getKey();
            DatasetConfig config = entry.getValue();

            try {
                String currentVersion = registry.getCurrentVersion(uri);
                String newVersion = manifestChecker.checkForUpdate(uri, currentVersion);

                if (newVersion != null) {
                    long start = System.nanoTime();
                    registry.reload(uri);
                    long duration = System.nanoTime() - start;
                    metrics.recordRefreshSuccess(newVersion, duration);
                } else {
                    metrics.recordRefreshSkip();
                }
            } catch (Exception e) {
                metrics.recordRefreshFailure();
                logger.warn("Failed to refresh dataset: {}", uri, e);
            }
        }
    }
}
```

### 5. LanceStorageConfig - 存储配置

**职责**:
- 存储连接配置（OSS 凭证、端点）
- URI 模板解析
- 分片感知模式判断

**配置字段**:
```java
public class LanceStorageConfig implements Writeable {
    private final String uri;              // 传统模式：完整 URI
    private final String uriPrefix;        // 分片模式：URI 前缀
    private final String shardPath;         // 分片模式：路径模板
    private final String datasetName;       // 数据集名称
    private final String ossEndpoint;      // OSS 端点
    private final String ossAccessKeyId;   // OSS 访问密钥
    private final String ossAccessKeySecret; // OSS 密钥
    private final String lanceIdColumn;   // Lance _id 列名
    private final String lanceVectorColumn; // Lance 向量列名
}
```

**模式判断**:
```java
public boolean isShardAware() {
    return uriPrefix != null;  // 有 uriPrefix 表示分片感知模式
}
```

---

## 数据流设计

### 查询执行数据流

```
┌─────────────────────────────────────────────────────────────────┐
│  Client Request                                                           │
│  {                                                                       │
│    "query": {                                                           │
│      "lance_knn": {                                                      │
│        "vector": [...],                                                  │
│        "k": 10,                                                        │
│        "filter": { "term": { "category": "electronics" } }                  │
│      }                                                                   │
│    }                                                                     │
│  }                                                                       │
└────────────────────────┬────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  1. Query Parsing (LanceKnnQueryBuilder)                              │
│     - Parse vector, k, nprobes                                          │
│     - Parse filter (QueryBuilder)                                       │
└────────────────────────┬────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  2. Field Mapping (LanceVectorFieldMapper)                               │
│     - Validate vector dimensions                                           │
│     - Resolve storage URI (P3: resolve per-shard)                            │
│     - Create LanceKnnQuery                                                │
└────────────────────────┬────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  3. Query Weight Creation (LanceKnnQuery.createWeight)                    │
│     - Legacy mode: Search once, share candidates                         │
│     - Shard-aware mode: Defer to scorerSupplier                           │
└────────────────────────┬────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  4. Scorer Supplier (LanceKnnQuery.scorerSupplier)                      │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ Phase 1: Filter Evaluation                                        │    │
│  │  - Create IndexSearcher for leaf reader                           │    │
│  │  - Build filterWeight                                             │    │
│  │  - Create filter bitset                                             │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ Phase 2: Candidate Search                                        │    │
│  │  IF (sharedCandidates != null):                                  │    │
│  │    → Use shared candidates (legacy mode)                        │    │
│  │  ELSE:                                                          │    │
│  │    → Resolve shard-specific URI (P3)                             │    │
│  │    → Load shard dataset                                         │    │
│  │    → Search Lance dataset                                        │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ Phase 3: _id Join + Filter Application                        │    │
│  │  - Iterate through Lance candidates                               │    │
│  │  - Lookup _id in Lucene TermsEnum                                 │    │
│  │  - Apply filter bitset (if present)                               │    │
│  │  - Build docScores Map                                            │    │
│  │  - Keep top-k results                                              │    │
│  └─────────────────────────────────────────────────────────────┘    │
└────────────────────────┬────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  5. Score Calculation (LanceScorer)                                    │
│     - Return scores for each document                                    │
│     - Apply query boost                                                   │
└────────────────────────┬────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  6. Response                                                            │
│  {                                                                       │
│    "hits": {                                                             │
│      "hits": [{ "_id": "1", "_score": 0.95 }, ...]                      │
│    }                                                                     │
│  }                                                                       │
└─────────────────────────────────────────────────────────────────┘
```

### NRT 刷新数据流

```
┌─────────────────────────────────────────────────────────────────┐
│  Background Refresh Thread (LanceRefreshService)                       │
│                                                                         │
│  1. Poll for changes (configurable interval)                             │
│     ┌───────────────────────────────────────────────────────────┐  │
│     │  OSS: HTTP HEAD manifest → ETag                             │  │
│     │  Local FS: File mtime on _latest.manifest                     │  │
│     └───────────────────────────────────────────────────────────┘  │
│                                                                         │
│  2. Detect version change                                              │
│     IF (newVersion != currentVersion):                                 │
│     ┌───────────────────────────────────────────────────────────┐  │
│     │  3. Load new dataset                                          │  │
│     │     - Open Lance dataset from OSS/FS                          │  │
│     │     - Create new VersionedDataset                             │  │
│     └───────────────────────────────────────────────────────────┘  │
│     ┌───────────────────────────────────────────────────────────┐  │
│     │  4. Atomic swap                                                │  │
│     │     registry.put(uri, newVersionedDataset)                  │  │
│     │     → Uses AtomicReference for thread-safe swap            │  │
│     └───────────────────────────────────────────────────────────┘  │
│     ┌───────────────────────────────────────────────────────────┐  │
│     │  5. Mark old version for close                               │  │
│     │     oldVersionedDataset.markForClose()                     │  │
│     │     → Will close when all queries finish                    │  │
│     └───────────────────────────────────────────────────────────┘  │
│                                                                         │
│  3. Record metrics                                                      │
│     - refresh_count, refresh_latency, active_version                    │
└─────────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  Concurrent Query (unaffected by refresh)                             │
│                                                                         │
│  1. Acquire dataset                                                     │
│     VersionedDataset vd = registry.acquire(uri);                       │
│     → Increments refCount                                              │
│                                                                         │
│  2. Execute search on old version (still available)                     │
│     List<Candidate> results = vd.dataset.search(...);                   │
│                                                                         │
│  3. Release dataset                                                    │
│     vd.release();                                                       │
│     → Decrements refCount                                              │
│     → If refCount == 0 && markedForClose: close()                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 性能优化策略

### 1. 缓存策略

**LRU 缓存**:
- 最大容量：默认 100 个数据集（可配置）
- 淘汰策略：最少使用（LRU）
- TTL 过期：60 分钟未使用（可配置）

**缓存预热**:
- 首次查询加载：2-5s（冷启动）
- 后续查询：20-100ms（缓存命中）

### 2. 过滤器优化

**自适应过采样**:
```java
int effectiveCandidates = numCandidates;
if (filter != null) {
    long totalDocs = leafReader.maxDoc();
    long filteredDocs = filterWeight.count(leafReaderContext);
    double selectivity = (double) filteredDocs / totalDocs;

    // 根据选择性调整候选数
    effectiveCandidates = (int) Math.min(
        numCandidates / selectivity,  // 反比缩放
        totalDocs * 0.1               // 上限：10% 数据集
    );
}
```

**策略选择启发式**:
- 选择性 < 5% 或过滤结果 < k×2：预过滤（如果 SDK 支持）
- 否则：后过滤 + 过采样

### 3. _id 连接优化

**当前实现** (O(M × log N)):
- 逐个候选进行 _id term lookup
- 适用于小候选集（< 1000）

**优化方案** (适用大候选集):
```java
// 批量查找：O(N + M)
Map<BytesRef, Float> candidateMap = new HashMap<>(candidates.size());
for (Candidate c : candidates) {
    candidateMap.put(new BytesRef(c.id()), c.score());
}

TermsEnum termsEnum = terms.iterator();
PostingsEnum postingsEnum = null;
while (termsEnum.next() != null) {
    Float score = candidateMap.get(termsEnum.term());
    if (score != null) {
        postingsEnum = termsEnum.postings(postingsEnum, 0);
        int docId = postingsEnum.nextDoc();
        // Apply filter and store result
    }
}
```

### 4. 并发处理

**线程安全保证**:
- `LanceDatasetRegistry`: 使用 `ConcurrentHashMap`
- `VersionedDataset`: 使用 `AtomicInteger` 引用计数
- 查询执行: 无状态设计，支持并发

**并发查询**:
- 多个线程可同时读取同一数据集
- 刷新不影响进行中的查询
- 新查询立即使用新版本数据集

---

## 安全与可靠性

### 1. OSS 凭证管理

**环境变量方式** (推荐):
```bash
export OSS_ACCESS_KEY_ID="your_key_id"
export OSS_ACCESS_KEY_SECRET="your_key_secret"
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"
./bin/elasticsearch -d -p es.pid
```

**关键点**:
- 环境变量必须在 ES 进程启动前设置
- Lance 原生代码从进程环境读取（非 Java System.getenv()）
- 不可在运行时动态更改

### 2. 错误处理

**数据集加载失败**:
- 记录错误日志
- 返回友好错误信息
- 不影响其他数据集

**OSS 访问失败**:
- 超时保护（默认 30s）
- 重试机制（可配置）
- 降级策略：使用缓存版本（如果可用）

**刷新失败**:
- 跳过本次刷新，下次继续
- 记录失败指标
- 不影响查询执行

### 3. 资源管理

**Arrow 内存管理**:
```bash
# JVM 选项
--add-opens=java.base/java.nio=ALL-UNNAMED
```

**内存分配**:
- Arrow 分配器限制：默认 ~256MB
- JVM 堆配置：4GB（生产推荐）
- 内存监控：`/_lance/stats` 端点

**资源清理**:
- `VersionedDataset` 引用计数管理
- 查询结束自动释放
- ES 节点关闭时清理所有资源

---

## 扩展性设计

### 1. 存储后端扩展

**接口抽象**:
```java
public interface LanceManifestChecker {
    String checkForUpdate(String uri, String lastVersion) throws IOException;
}

public interface OssStorageAdapter {
    InputStream open(String uri) throws IOException;
    boolean exists(String uri) throws IOException;
}
```

**实现类**:
- `LocalManifestChecker`: 本地文件系统
- `OssManifestChecker`: 阿里云 OSS
- `S3ManifestChecker`: AWS S3（未来）
- `GcsManifestChecker`: Google Cloud Storage（未来）

### 2. 过滤策略扩展

**策略接口**:
```java
public interface FilterStrategy {
    FilterDecision decide(int filteredDocCount, int k);
}

public enum FilterStrategy {
    PRE_FILTER,
    POST_FILTER,
        AUTO
}
```

**启发式实现**:
- `DefaultHeuristic`: 基于 M < K×2 阈值
- `ConfigurableHeuristic`: 可配置阈值
- `CustomHeuristic`: 用户自定义逻辑

### 3. 指标扩展

**指标收集器**:
```java
public interface MetricsCollector {
    void recordSearch(long durationMs);
    void recordRefresh(String uri, long durationMs, boolean success);
    Map<String, Object> toMap();
}
```

**可观测性集成**:
- ES 查询分析器集成
- 搜索性能分析（profiling=true）
- 指标导出（Prometheus 格式）

---

## 配置参考

### 集群级别配置

```json
PUT /_cluster/settings
{
  "persistent": {
    "lance": {
      "refresh": {
        "enabled": true,
        "interval": "5m"
      },
      "cache": {
        "max_size": 100,
        "ttl_minutes": 60
      },
      "nprobes": {
        "default": 20
      },
      "filter": {
        "oversample_factor": 3.0
      }
    }
  }
}
```

### 索引级别配置

```json
PUT /my-index/_settings
{
  "index": {
    "lance": {
      "refresh": {
        "interval": "1m"
      },
      "pre_filter": "auto"
    }
  }
}
```

### 字段级别配置

```json
PUT /my-index
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          // 传统模式
          "uri": "oss://bucket/path/dataset.lance",

          // 或分片感知模式（P3）
          "uri_prefix": "oss://bucket/data/",
          "shard_path": "{index}/shard-{shard_id}",
          "dataset_name": "vectors.lance",

          // OSS 认证（可选，优先使用环境变量）
          "oss_endpoint": "oss-ap-southeast-1.aliyuncs.com",
          "oss_access_key_id": "${OSS_ACCESS_KEY_ID}",
          "oss_access_key_secret": "${OSS_ACCESS_KEY_SECRET}",

          // 列名映射
          "lance_id_column": "_id",
          "lance_vector_column": "vector",

          "read_only": true
        }
      }
    }
  }
}
```

---

## API 参考

### 查询 API

**基础 kNN 查询**:
```json
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "field": "embedding",
      "query_vector": [0.1, 0.2, ...],
      "k": 10,
      "num_candidates": 100,
      "nprobes": 20
    }
  }
}
```

**带过滤器的查询**:
```json
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "field": "embedding",
      "query_vector": [0.1, 0.2, ...],
      "k": 10,
      "filter": {
        "term": { "category": "electronics" }
      }
    }
  }
}
```

### 统计 API

**获取统计信息**:
```bash
GET /_lance/stats?pretty
```

**响应示例**:
```json
{
  "cache": {
    "size": 5,
    "max_size": 100,
    "ttl_minutes": 60
  },
  "refresh": {
    "enabled": true,
    "interval": "5m",
    "last_refresh": "2026-02-07T10:30:00Z",
    "refresh_count": 12
  },
  "search": {
    "total_searches": 12500,
    "total_search_time_ms": 2450,
    "filtered_searches": 3200,
    "pre_filter_searches": 1800,
    "post_filter_searches": 1400
  }
}
```

### 刷新 API

**手动触发刷新**:
```bash
POST /_lance/refresh
{
  "indices": ["my-index"],
  "force": true
}
```

**集群级别刷新配置**:
```bash
PUT /_cluster/settings
{
  "persistent": {
    "lance.refresh.enabled": true,
    "lance.refresh.interval": "30s"
  }
}
```

---

## 故障排查指南

### 问题 1: 搜索结果为零分

**症状**: 所有 `hits._score = 0.0`

**原因**: 距离到评分转换缺失

**解决方案**:
```java
// 在 LanceKnnQuery.buildDocScores() 中验证
float score = 1.0f / (1.0f + distance);
docScores.put(docId, score);
```

### 问题 2: OSS 认证失败

**症状**: `Authentication failed` 错误

**原因**: OSS 环境变量未设置或不正确

**解决方案**:
```bash
# 在启动 ES 前设置环境变量
export OSS_ACCESS_KEY_ID="your_key_id"
export OSS_ACCESS_KEY_SECRET="your_key_secret"
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

# 验证环境变量
echo $OSS_ACCESS_KEY_ID
echo $OSS_ACCESS_KEY_SECRET
echo $OSS_ENDPOINT

# 启动 ES
./bin/elasticsearch -d -p es.pid
```

### 问题 3: 分片模板未解析

**症状**: URI 包含字面量 `{shard_id}`

**原因**: shardId 未传递到 createKnnQuery

**解决方案**:
```java
// 在 LanceKnnQueryBuilder.doToQuery() 中验证
return mapper.createKnnQuery(
    vectorData, k, numCandidates, similarity,
    filter, dims, nprobes, prefilterHeuristic,
    context.index().getName(),  // indexName
    context.getShardId()         // shardId
);
```

### 问题 4: NRT 刷新导致查询失败

**症状**: 刷新期间查询失败

**原因**: 数据集交换不是原子的

**解决方案**:
- 验证 `VersionedDataset` 使用 `AtomicReference`
- 确认引用计数正确实现
- 检查日志中是否有并发异常

---

## 性能基准

### 测试环境
- **向量数量**: 10,000 - 1,000,000
- **向量维度**: 128 - 768
- **索引类型**: IVF-PQ
- **硬件**: 4 核 CPU, 16GB RAM

### 性能指标

| 操作 | 本地存储 | OSS (同区域) | OSS (跨区域) |
|------|----------|-------------|-------------|
| 首次查询 | 2-3s | 3-5s | 5-10s |
| 后续查询 | 20-50ms | 50-100ms | 100-200ms |
| NRT 刷新 | <100ms | 500ms-1s | 1-2s |
| 缓存淘汰 | <10ms | <10ms | <10ms |

### 资源使用

| 资源 | 使用量 | 说明 |
|------|--------|------|
| JVM 堆内存 | 4GB | 默认配置 |
| Arrow 内存 | ~256MB | 单个数据集 |
| 每个数据集 | ~50MB | 10K 向量，128 维 |
| 缓存条目 | 100 个 | 默认配置 |
| 线程池 | 1-2 个 | 刷新调度器 |

---

## 监控与告警

### 关键指标

**搜索性能**:
- `search.total_searches`: 总搜索次数
- `search.total_search_time_ms`: 平均搜索延迟
- `search.search_errors`: 搜索错误次数

**刷新健康**:
- `refresh.enabled`: 刷新是否启用
- `refresh.refresh_count`: 刷新成功次数
- `refresh.refresh_failure_count`: 刷新失败次数

**缓存状态**:
- `cache.size`: 当前缓存数据集数量
- `cache.hit_rate`: 缓存命中率（需要计算）

**内存使用**:
- `memory.allocated_bytes`: Arrow 内存分配量
- `memory.active_datasets`: 活跃数据集数量

### 告警建议

| 告警条件 | 级别 | 操作 |
|----------|------|------|
| `search.search_errors` > 0 | WARNING | 检查日志，分析错误原因 |
| `refresh.refresh_failure_count` 连续 3 次 | WARNING | 检查 OSS 连接，验证数据集 |
| `cache.size` 接近 `cache.max_size` | INFO | 考虑增加缓存容量 |
| 搜索延迟 P99 > 500ms | WARNING | 检查网络延迟，考虑缓存预热 |

---

## 技术限制

### 已知限制

1. **只读模式**: Phase 1 仅支持读取，不支持写入向量数据
2. **过滤器依赖**: 后过滤依赖 Lucene 过滤器性能
3. **OSS 延迟**: OSS HEAD 请求增加 50-200ms 延迟
4. **内存限制**: Arrow 内存分配器限制约 256MB
5. **并发限制**: 刷新调度器使用 1-2 个线程

### 规划中的改进

1. **Phase 2**: 写入支持（通过 Lance SDK 写入 OSS）
2. **预过滤优化**: 深度集成 Lance SDK 过滤 API
3. **批量操作**: 支持批量向量插入
4. **多区域复制**: 支持跨区域数据集复制
5. **增量更新**: 支持增量向量更新

---

## 变更历史

### v9.2.4 (2026-02-07)
- ✅ 合并 P1-P2-P3 功能
- ✅ 分片感知数据集映射
- ✅ NRT 刷新基础设施
- ✅ 过滤器支持优化
- ✅ nprobes 参数控制

### v9.2.4-beta (2025-01-XX)
- 初始版本发布
- 基础向量搜索功能
- OSS 集成支持

---

## 贡献指南

### 开发环境设置

1. 克隆代码库
2. 安装 JDK 21
3. 构建插件：`./gradlew :plugins:lance-vector:assemble`
4. 运行测试：`./gradlew :plugins:lance-vector:test`

### 代码规范

- 遵循 Elasticsearch 代码规范
- 使用 Spotless 格式化：`./gradlew spotlessApply`
- 添加单元测试和集成测试
- 更新文档

### 提交流程

1. Fork 项目
2. 创建功能分支
3. 提交 Pull Request
4. 通过 CI/CD 检查
5. 代码审查
6. 合并到主分支

---

## 参考资料

- **Elasticsearch 插件开发**: [官方文档](https://www.elastic.co/guide/en/elasticsearch/plugins/master/plugins-intro.html)
- **Lance 文档**: [lance.dev](https://lance.dev/)
- **Arrow 文档**: [arrow.apache.org](https://arrow.apache.org/)
- **阿里云 OSS 文档**: [help.aliyun.com/product/oss/](https://help.aliyun.com/product/oss/)

---

**维护者**: Elasticsearch Lance Vector Plugin 团队
**联系方式**: 通过 GitHub Issues 提交问题和反馈
