# Lance Vector Plugin 用户指南

## 文档概述

本文档是 Lance Vector Plugin 的完整用户指南，涵盖安装、配置、使用和故障排除等所有方面。

**版本**: 9.2.4
**更新日期**: 2026-02-07
**目标读者**: 系统管理员、开发工程师、运维工程师

---

## 目录

- [简介](#简介)
- [快速开始](#快速开始)
- [安装部署](#安装部署)
- [基础配置](#基础配置)
- [向量搜索](#向量搜索)
- [高级功能](#高级功能)
- [性能调优](#性能调优)
- [监控运维](#监控运维)
- [故障排除](#故障排除)
- [API 参考](#api-参考)
- [最佳实践](#最佳实践)

---

## 简介

### 什么是 Lance Vector Plugin？

Lance Vector Plugin 是 Elasticsearch 的向量字段类型插件，支持使用 Lance 格式存储和检索高维向量数据。

**核心特性**：

- **外部存储**: 向量数据存储在外部 Lance 数据集，ES 仅存储元数据
- **高性能**: 基于 IVF-PQ 索引，支持大规模向量搜索（百万级以上）
- **NRT 刷新**: 支持准实时数据集刷新（P1-P2 特性）
- **分片感知**: 支持分片级别的数据集映射（P3 特性）
- **过滤支持**: 支持与 Elasticsearch DSL 一致的过滤器语法
- **多种存储后端**: 本地文件系统、阿里云 OSS、AWS S3 等

### 应用场景

| 场景 | 描述 | 推荐配置 |
|------|------|---------|
| **图像搜索** | 以图搜图、相似图片推荐 | cosine 相似度，高 nprobes |
| **文本语义搜索** | 文档检索、问答系统 | cosine 相似度，IVF-PQ 索引 |
| **推荐系统** | 商品推荐、内容推荐 | L2 距离，高召回率配置 |
| **异常检测** | 日志异常、欺诈检测 | L2 距离，低阈值 |
| **人脸识别** | 人脸验证、聚类 | cosine 相似度，高精度 |

---

## 快速开始

### 5 分钟快速体验

```bash
# 1. 启动 Elasticsearch（4GB JVM，生产级配置）
./project_starter.sh -d

# 2. 创建索引（使用本地 Lance 数据集）
curl -k -u elastic:Summer11 -X PUT "https://localhost:9200/image-search" \
  -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "title": { "type": "text" },
      "category": { "type": "keyword" },
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri": "file:///tmp/image-vectors.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector"
        }
      }
    }
  }
}'

# 3. 索引元数据文档
curl -k -u elastic:Summer11 -X POST "https://localhost:9200/image-search/_bulk" \
  -H 'Content-Type: application/x-ndjson' -d '
{"index": {"_index": "image-search", "_id": "img_001"}}
{"title": "可爱的小猫", "category": "动物"}
{"index": {"_index": "image-search", "_id": "img_002"}}
{"title": "城市风景", "category": "风景"}
{"index": {"_index": "image-search", "_id": "img_003"}}
{"title": "美味食物", "category": "美食"}
'

# 4. 执行向量搜索
curl -k -u elastic:Summer11 -X POST "https://localhost:9200/image-search/_search" \
  -H 'Content-Type: application/json' -d '{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, 0.3, ...],
    "k": 5,
    "num_candidates": 50
  }
}'

# 5. 带过滤的搜索
curl -k -u elastic:Summer11 -X POST "https://localhost:9200/image-search/_search" \
  -H 'Content-Type: application/json' -d '{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, 0.3, ...],
    "k": 5,
    "num_candidates": 50
  },
  "post_filter": {
    "term": { "category": "动物" }
  }
}'
```

---

## 安装部署

### 系统要求

| 组件 | 最低要求 | 推荐配置 |
|------|---------|---------|
| **Elasticsearch** | 9.2.4 | 9.2.4+ |
| **JDK** | 21 | 21 (Oracle OpenJDK) |
| **内存** | 2GB | 4GB+ (生产) |
| **操作系统** | Linux/macOS/Windows | Linux (生产) |
| **存储后端** | 本地文件系统 | 阿里云 OSS / AWS S3 |

### 从源码构建

```bash
# 1. 克隆仓库
git clone https://github.com/your-org/es-9.2.4-plugins-rt-scale.git
cd es-9.2.4-plugins-rt-scale

# 2. 构建本地分发版本
./gradlew localDistro

# 3. 验证插件
ls build/distribution/local/elasticsearch-9.2.4-SNAPSHOT/plugins/lance-vector/
```

### 使用启动脚本（推荐）

```bash
# 默认启动（4GB JVM）
./project_starter.sh -d

# 自定义 JVM 堆大小
./project_starter.sh --jvm-heap 8g -d

# 查看帮助
./project_starter.sh --help
```

### 手动启动

```bash
# 1. 设置环境变量（仅 OSS 存储需要）
export OSS_ACCESS_KEY_ID=your_access_key
export OSS_ACCESS_KEY_SECRET=your_secret_key
export OSS_ENDPOINT=oss-ap-southeast-1.aliyuncs.com

# 2. 配置 JVM 选项
echo "-Xms4g" > build/distribution/local/elasticsearch-9.2.4-SNAPSHOT/config/jvm.options.d/lance-heap.options
echo "-Xmx4g" >> build/distribution/local/elasticsearch-9.2.4-SNAPSHOT/config/jvm.options.d/lance-heap.options

# 3. 启动 Elasticsearch
cd build/distribution/local/elasticsearch-9.2.4-SNAPSHOT
./bin/elasticsearch -d -p elasticsearch.pid

# 4. 验证启动
tail -f logs/elasticsearch.log
```

### 配置阿里云 OSS（可选）

**OSS 凭证配置**：

```json
// ~/.oss/credentials.json
{
  "access_key_id": "your_access_key_id",
  "access_key_secret": "your_access_key_secret",
  "endpoint": "oss-ap-southeast-1.aliyuncs.com",
  "bucket": "your-bucket-name",
  "region": "oss-ap-southeast-1"
}
```

**环境变量设置**：

```bash
# 方式 1: 手动设置
export OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

# 方式 2: 使用启动脚本（自动加载）
./project_starter.sh -d
```

---

## 基础配置

### 字段类型定义

#### 基本配置

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
          "uri": "file:///tmp/vectors.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector"
        }
      }
    }
  }
}
```

#### 参数说明

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| **type** | string | 是 | - | 固定值 `lance_vector` |
| **dims** | integer | 是 | - | 向量维度（必须与数据集一致） |
| **similarity** | string | 否 | cosine | 相似度算法：cosine, l2, dot |
| **storage** | object | 是 | - | 存储配置（见下表） |

#### 存储配置参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| **storage.type** | string | 是 | - | 固定值 `external` |
| **storage.uri** | string | 条件 | - | 单 URI 模式（传统模式） |
| **storage.uri_prefix** | string | 条件 | - | URI 前缀（分片感知模式） |
| **storage.shard_path** | string | 否 | - | 分片路径模板（P3 特性） |
| **storage.dataset_name** | string | 否 | data.lance | 数据集名称 |
| **storage.lance_id_column** | string | 是 | _id | Lance 数据集中的 ID 列名 |
| **storage.lance_vector_column** | string | 是 | vector | Lance 数据集中的向量列名 |
| **storage.read_only** | boolean | 否 | true | 是否只读模式 |

### 相似度算法

| 算法 | 适用场景 | 分值范围 | 注意事项 |
|------|---------|---------|---------|
| **cosine** | 文本、图像等归一化向量 | 0-1 | 最常用，适合高维数据 |
| **l2** | 需要精确距离的场景 | 0-∞ | 欧几里得距离，越小越相似 |
| **dot** | 已归一化的向量 | -∞-∞ | 点积，速度快但范围不稳定 |

**选择建议**：
- 文本语义、图像特征 → **cosine**
- 推荐系统、异常检测 → **l2**
- 已归一化向量 → **dot**

### 存储模式

#### 模式 1: 单 URI 模式（传统模式）

```json
{
  "embedding": {
    "type": "lance_vector",
    "storage": {
      "type": "external",
      "uri": "file:///tmp/vectors.lance"
    }
  }
}
```

**适用场景**：
- 单分片索引
- 开发测试环境
- 向量数据集较小（< 1GB）

#### 模式 2: 分片感知模式（P3 特性）

```json
{
  "embedding": {
    "type": "lance_vector",
    "storage": {
      "type": "external",
      "uri_prefix": "oss://my-bucket/vectors/",
      "shard_path": "my-index/shard-{shard_id}",
      "dataset_name": "vectors.lance",
      "lance_id_column": "_id",
      "lance_vector_column": "vector"
    }
  }
}
```

**URI 模板占位符**：

| 占位符 | 替换内容 | 示例 |
|--------|---------|------|
| `{index}` | 索引名称 | `my-index` |
| `{shard_id}` | 分片 ID | `0`, `1`, `2` |

**解析示例**：
- 索引: `my-index`, 分片: `0`
- URI: `oss://my-bucket/vectors/my-index/shard-0/vectors.lance`

**适用场景**：
- 多分片索引
- 生产环境
- 大规模向量数据（> 1GB）

---

## 向量搜索

### 基础 kNN 搜索

```json
GET /my-index/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, 0.3, ...],
    "k": 10,
    "num_candidates": 100
  }
}
```

**参数说明**：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| **field** | string | 是 | - | 向量字段名 |
| **query_vector** | array | 是 | - | 查询向量（维度必须匹配） |
| **k** | integer | 否 | 10 | 返回结果数量 |
| **num_candidates** | integer | 否 | 100 | 候选数量（影响召回率） |

**性能调优**：

| 场景 | k | num_candidates | 说明 |
|------|---|----------------|------|
| 快速探索 | 10 | 50 | 低召回率，高速度 |
| 标准搜索 | 10 | 100 | 平衡速度与召回率 |
| 高精度搜索 | 10 | 200+ | 高召回率，较低速度 |
| 大规模数据 | 10 | 数量的 10 倍 | 根据数据集大小调整 |

### 带过滤的搜索

```json
GET /my-index/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, 0.3, ...],
    "k": 10,
    "num_candidates": 100
  },
  "post_filter": {
    "term": { "category": "电子产品" }
  }
}
```

**支持的过滤器类型**：

- **term**: 精确匹配
- **terms**: 多值匹配
- **range**: 范围查询
- **bool**: 布尔组合（must, should, must_not）
- **nested**: 嵌套对象查询

**过滤策略**：

插件会根据过滤器选择性自动选择策略：

- **小过滤器**（选择性 < 10%）: Pre-filter，先过滤再搜索
- **大过滤器**（选择性 ≥ 10%）: Post-filter，先搜索再过滤

```json
// 多条件过滤
{
  "knn": { ... },
  "post_filter": {
    "bool": {
      "must": [
        { "term": { "category": "电子产品" } },
        { "range": { "price": { "gte": 100, "lte": 1000 } } }
      ],
      "must_not": [
        { "term": { "status": "deleted" } }
      ]
    }
  }
}
```

### nprobes 参数控制

```json
GET /my-index/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, 0.3, ...],
    "k": 10,
    "num_candidates": 100,
    "nprobes": 20
  }
}
```

**nprobes 与性能权衡**：

| nprobes | 召回率 | 速度 | 适用场景 |
|---------|-------|------|---------|
| 10-20 | 低 | 快 | 粗略搜索 |
| 50-100 | 中 | 中 | 标准搜索 |
| 200+ | 高 | 慢 | 精确搜索 |

**推荐配置**：

```bash
# 高精度配置（召回率优先）
nprobes = sqrt(num_vectors) * 2

# 高性能配置（速度优先）
nprobes = sqrt(num_vectors) / 2

# 平衡配置
nprobes = sqrt(num_vectors)
```

### 混合搜索（RRF）

结合向量搜索和文本搜索：

```json
GET /my-index/_search
{
  "query": {
    "match": {
      "title": "智能手机"
    }
  },
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, ...],
    "k": 10
  },
  "rank": {
    "type": "rrf",
    "rrf": {
      "constant": 50
    }
  }
}
```

**RRF 参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| **constant** | integer | 50 | RRF 常数（值越大，向量搜索权重越高） |

**RRF 公式**：

```
RRF_score = 1 / (k + rank_text) + 1 / (k + rank_vector)
```

---

## 高级功能

### NRT 刷新（P1-P2 特性）

#### 启用 NRT 刷新

```bash
# 集群级别启用
PUT /_cluster/settings
{
  "persistent": {
    "lance.refresh.enabled": true,
    "lance.refresh.interval": "30s"
  }
}
```

#### 集群设置

| 设置 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| **lance.refresh.enabled** | boolean | false | 是否启用 NRT 刷新 |
| **lance.refresh.interval** | time | 60s | 刷新间隔 |

#### 索引级别覆盖

```json
PUT /my-index/_settings
{
  "settings": {
    "lance.refresh.interval": "10s"
  }
}
```

#### 刷新指标监控

```bash
# 查看刷新统计
GET /_lance/stats

# 响应示例
{
  "datasets": {
    "total": 5,
    "cached": 5,
    "size_bytes": 1342177280
  },
  "refresh": {
    "enabled": true,
    "interval": "30s",
    "last_refresh": "2026-02-07T10:30:00Z",
    "total_refreshes": 120,
    "failed_refreshes": 0
  }
}
```

### 分片感知存储（P3 特性）

#### 创建分片感知索引

```json
PUT /shard-index
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "storage": {
          "type": "external",
          "uri_prefix": "oss://my-bucket/data/",
          "shard_path": "shard-index/shard-{shard_id}",
          "dataset_name": "vectors.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector"
        }
      }
    }
  }
}
```

#### 分片 URI 解析示例

| 分片 ID | shard_path 模板 | 解析后 URI |
|---------|----------------|-----------|
| 0 | shard-index/shard-{shard_id} | oss://my-bucket/data/shard-index/shard-0/vectors.lance |
| 1 | shard-index/shard-{shard_id} | oss://my-bucket/data/shard-index/shard-1/vectors.lance |
| 2 | shard-index/shard-{shard_id} | oss://my-bucket/data/shard-index/shard-2/vectors.lance |

#### 索引名称占位符

```json
{
  "storage": {
    "uri_prefix": "oss://my-bucket/data/",
    "shard_path": "{index}/shard-{shard_id}",
    "dataset_name": "vectors.lance"
  }
}
```

**解析结果**：
- 索引 `product-v1`, 分片 `0` → `oss://my-bucket/data/product-v1/shard-0/vectors.lance`
- 索引 `product-v2`, 分片 `1` → `oss://my-bucket/data/product-v2/shard-1/vectors.lance`

---

## 性能调优

### JVM 堆内存配置

| 场景 | 推荐堆大小 | JVM 选项 |
|------|-----------|---------|
| 开发测试 | 2GB | -Xms2g -Xmx2g |
| 小规模生产 | 4GB | -Xms4g -Xmx4g |
| 中规模生产 | 8GB | -Xms8g -Xmx8g |
| 大规模生产 | 16GB+ | -Xms16g -Xmx16g |

```bash
# 使用启动脚本设置
./project_starter.sh --jvm-heap 8g -d
```

### 向量索引参数调优

#### IVF-PQ 索引参数

创建数据集时设置：

```python
import lance
import pyarrow as pa

# 创建 IVF-PQ 索引
dataset = lance.create_dataset(
    "/tmp/vectors.lance",
    data=table,
    mode="overwrite"
)

# 配置 IVF-PQ 索引
dataset.create_index(
    column="vector",
    index_type="IVF_PQ",
    metric="cosine",
    num_partitions=256,    # IVF 参数
    num_sub_vectors=64      # PQ 参数
)
```

**参数说明**：

| 参数 | 影响 | 推荐值 |
|------|------|--------|
| **num_partitions** | 召回率 vs 速度 | sqrt(num_vectors) |
| **num_sub_vectors** | 精度 vs 压缩 | dims / 8 ~ dims / 4 |

**调优建议**：

- **高召回率**: num_partitions = sqrt(N) * 2
- **高性能**: num_partitions = sqrt(N) / 2
- **高精度**: num_sub_vectors = dims / 8
- **高压缩**: num_sub_vectors = dims / 4

### 查询性能优化

#### 并发查询

```json
// 使用 max_concurrent_shard_requests 控制并发
GET /my-index/_search?max_concurrent_shard_requests=4
{
  "knn": { ... }
}
```

#### 结果缓存

插件自动缓存加载的数据集，缓存策略：

- **LRU 淘汰**: 最多缓存 100 个数据集
- **TTL 过期**: 数据集缓存 1 小时后失效
- **手动刷新**: 调用 `/_lance/refresh` API

#### 预热数据集

```bash
# 首次搜索会加载数据集（较慢）
# 可以通过预热脚本提前加载

for i in {1..10}; do
  curl -X POST "https://localhost:9200/my-index/_search" \
    -H 'Content-Type: application/json' -d '{
    "knn": {
      "field": "embedding",
      "query_vector": [0.1, 0.2, ...],
      "k": 1
    }
  }'
done
```

### 存储性能优化

#### OSS 配置优化

```bash
# 使用 OSS 内网 endpoint（生产环境）
export OSS_ENDPOINT="oss-cn-hangzhou-internal.aliyuncs.com"

# 调整连接池（通过 Lance SDK）
export LANCE_MAX_CONNECTIONS=100
export LANCE_TIMEOUT=30
```

#### 数据集分片

- **推荐**: 每个分片对应 1-4GB 向量数据
- **计算公式**:
  ```
  分片数 = 总向量大小 / 2GB
  ```

---

## 监控运维

### 统计指标 API

```bash
# 获取 Lance 插件统计
GET /_lance/stats

# 响应示例
{
  "datasets": {
    "total": 10,
    "cached": 8,
    "evicted": 2,
    "size_bytes": 5368709120
  },
  "search": {
    "total_queries": 100000,
    "avg_latency_ms": 45,
    "p95_latency_ms": 120,
    "p99_latency_ms": 250
  },
  "refresh": {
    "enabled": true,
    "interval": "30s",
    "last_refresh": "2026-02-07T10:30:00Z",
    "total_refreshes": 120,
    "failed_refreshes": 0
  }
}
```

### 关键指标

| 指标 | 含义 | 告警阈值 |
|------|------|---------|
| **datasets.cached** | 缓存的数据集数量 | < 总数的 80% |
| **search.avg_latency_ms** | 平均搜索延迟 | > 100ms |
| **search.p99_latency_ms** | P99 搜索延迟 | > 500ms |
| **refresh.failed_refreshes** | 刷新失败次数 | > 0 |
| **datasets.size_bytes** | 缓存总大小 | > JVM 堆的 50% |

### 日志配置

```bash
# 启用 DEBUG 日志（调试用）
echo "logger.org.elasticsearch.plugin.lance.name = DEBUG" \
  >> config/log4j2.properties

# 启用 TRACE 日志（深度调试）
echo "logger.org.elasticsearch.plugin.lance.name = TRACE" \
  >> config/log4j2.properties
```

### 监控集成

#### Prometheus 指标导出

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'elasticsearch'
    metrics_path: '/_prometheus/metrics'
    static_configs:
      - targets: ['localhost:9200']
```

#### Grafana 仪表板

推荐监控面板：

- **Lance 数据集缓存**: 缓存命中率、缓存大小
- **搜索性能**: QPS、延迟分布
- **刷新状态**: 刷新次数、失败率
- **资源使用**: JVM 堆、线程池

---

## 故障排除

### 常见问题

#### 问题 1: 所有结果分数为 0

**症状**: kNN 搜索返回结果，但所有 `_score = 0.0`

**原因**: 距离到分数的转换公式缺失

**解决方案**:
1. 确认使用最新版本插件
2. 检查日志中是否有 `LanceKnnQuery` 相关错误
3. 验证相似度算法配置是否正确

```bash
# 检查插件版本
GET /_cat/plugins?v
```

#### 问题 2: OSS 认证失败

**症状**: `Authentication failed` 或 `Access denied`

**原因**: OSS 环境变量未在 ES 启动前设置

**解决方案**:
```bash
# 1. 确认环境变量已设置
echo $OSS_ACCESS_KEY_ID
echo $OSS_ACCESS_KEY_SECRET
echo $OSS_ENDPOINT

# 2. 重启 ES
pkill -f elasticsearch
export OSS_ACCESS_KEY_ID=...
export OSS_ACCESS_KEY_SECRET=...
export OSS_ENDPOINT=...
./bin/elasticsearch -d -p elasticsearch.pid

# 3. 验证 ES 进程环境变量
ES_PID=$(cat elasticsearch.pid)
cat /proc/$ES_PID/environ | tr '\0' '\n' | grep OSS
```

#### 问题 3: 首次搜索非常慢

**症状**: 首次 kNN 搜索耗时 > 10 秒

**原因**: Lance 需要加载数据集到内存

**预期行为**:
- 本地存储: 1-3 秒
- OSS 存储: 2-10 秒
- 后续搜索: 20-100ms

**优化方案**:
1. 预热数据集（见性能调优章节）
2. 增加 JVM 堆内存
3. 使用本地存储缓存

#### 问题 4: ClassCastException: LargeVarCharVector

**症状**: `ClassCastException: org.apache.arrow.vector.LargeVarCharVector`

**原因**: Lance 数据集使用了 `pa.large_string()` 而非 `pa.string()`

**解决方案**:
```python
# 错误
schema = pa.schema([
    pa.field("_id", pa.large_string()),  # ❌
    ...
])

# 正确
schema = pa.schema([
    pa.field("_id", pa.string()),  # ✅
    ...
])
```

#### 问题 5: 路径不存在错误

**症状**: `Path not found: tmp/demo-vectors.lance`

**原因**: file:// URI 格式错误

**解决方案**:
```json
// 错误
{
  "storage": {
    "uri": "file://tmp/demo-vectors.lance"  // ❌
  }
}

// 正确
{
  "storage": {
    "uri": "file:///tmp/demo-vectors.lance"  // ✅
  }
}
```

### 错误代码参考

| HTTP 错误 | 错误信息 | 原因 | 解决方案 |
|----------|---------|------|---------|
| 400 | `dimension_mismatch` | 查询向量维度不匹配 | 检查 dims 配置和 query_vector 长度 |
| 400 | `invalid_uri` | URI 格式错误 | 使用正确的 file:/// 或 oss:// 格式 |
| 401 | `authentication_failed` | OSS 认证失败 | 检查 OSS 环境变量配置 |
| 404 | `dataset_not_found` | Lance 数据集不存在 | 创建数据集或检查 URI 路径 |
| 500 | `arrow_memory_error` | Arrow 内存不足 | 增加 JVM 堆内存或减少数据集大小 |
| 503 | `dataset_load_timeout` | 数据集加载超时 | 检查存储后端网络或增加超时时间 |

---

## API 参考

### 索引管理 API

#### 创建索引

```json
PUT /my-index
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri": "file:///tmp/vectors.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector"
        }
      }
    }
  }
}
```

#### 更新索引设置

```json
PUT /my-index/_settings
{
  "settings": {
    "lance.refresh.interval": "10s"
  }
}
```

### 搜索 API

#### 基础搜索

```json
GET /my-index/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, 0.3, ...],
    "k": 10,
    "num_candidates": 100
  }
}
```

#### 带过滤的搜索

```json
GET /my-index/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, 0.3, ...],
    "k": 10,
    "num_candidates": 100
  },
  "post_filter": {
    "term": { "category": "电子产品" }
  }
}
```

#### 混合搜索（RRF）

```json
GET /my-index/_search
{
  "query": {
    "match": {
      "title": "智能手机"
    }
  },
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, ...],
    "k": 10
  },
  "rank": {
    "type": "rrf",
    "rrf": {
      "constant": 50
    }
  }
}
```

### Lance 插件 API

#### 统计信息

```bash
GET /_lance/stats
```

#### 手动刷新

```bash
POST /_lance/refresh
{
  "indices": ["my-index"]
}
```

#### 集群设置

```json
PUT /_cluster/settings
{
  "persistent": {
    "lance.refresh.enabled": true,
    "lance.refresh.interval": "30s"
  }
}
```

---

## 最佳实践

### 索引设计

#### 分片策略

| 数据规模 | 推荐分片数 | 分片大小 |
|---------|-----------|---------|
| < 100 万向量 | 1 | 小于 1GB |
| 100-1000 万 | 3-5 | 1-2GB |
| 1000 万+ | 5-10 | 2-4GB |

#### 字段设计

```json
{
  "mappings": {
    "properties": {
      // 必填：向量字段
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "similarity": "cosine"
      },
      // 推荐：业务字段（用于过滤）
      "category": { "type": "keyword" },
      "tags": { "type": "keyword" },
      "created_at": { "type": "date" },
      "price": { "type": "double" },
      // 可选：全文检索字段（混合搜索）
      "title": { "type": "text" },
      "description": { "type": "text" }
    }
  }
}
```

### 数据集管理

#### 数据集创建

```python
# 推荐的数据集结构
import lance
import pyarrow as pa
import numpy as np

schema = pa.schema([
    pa.field("_id", pa.string()),           # ✅ 使用 pa.string()
    pa.field("vector", pa.list_(pa.float32(), 128)),  # 向量
    pa.field("category", pa.string()),       # 可选：分类
    pa.field("metadata", pa.string())        # 可选：元数据
])

# 创建 IVF-PQ 索引
dataset = lance.create_dataset(
    "oss://bucket/vectors.lance",
    data=table,
    mode="overwrite"
)

# 配置索引
dataset.create_index(
    column="vector",
    index_type="IVF_PQ",
    metric="cosine",
    num_partitions=256,
    num_sub_vectors=64
)
```

#### 数据集版本管理

```bash
# 使用 VersionedDataset 实现原子切换
# 1. 上传新版本到临时路径
oss://bucket/vectors/v2.lance

# 2. 更新索引映射指向新路径
PUT /my-index/_mapping
{
  "properties": {
    "embedding": {
      "storage": {
        "uri": "oss://bucket/vectors/v2.lance"
      }
    }
  }
}

# 3. 等待 NRT 刷新或手动触发
POST /_lance/refresh
```

### 查询优化

#### 批量查询

```bash
# 使用 msearch 批量查询
GET /my-index/_msearch
{ "index": "my-index" }
{ "knn": { "field": "embedding", "query_vector": [...], "k": 10 } }
{ "index": "my-index" }
{ "knn": { "field": "embedding", "query_vector": [...], "k": 10 } }
```

#### 分页优化

```json
// 使用 search_after 而非 from/size
{
  "knn": { ... },
  "size": 20,
  "search_after": [last_score, last_doc_id]
}
```

### 安全配置

#### 网络隔离

```bash
# ES HTTP 层仅监听本地
network.host: 127.0.0.1

# 通过 Nginx 反向代理暴露
```

#### OSS 权限最小化

```json
// OSS 策略示例：仅允许读取特定 bucket
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["oss:GetObject"],
      "Resource": ["acs:oss:*:*:your-bucket/*"]
    }
  ]
}
```

---

## 附录

### 支持的存储后端

| 后端 | URI 格式 | 状态 | 注意事项 |
|------|---------|------|---------|
| 本地文件系统 | `file:///path/to/dataset.lance` | ✅ 完全支持 | 需要绝对路径 |
| 阿里云 OSS | `oss://bucket/path/dataset.lance` | ✅ 完全支持 | 需要环境变量 |
| AWS S3 | `s3://bucket/path/dataset.lance` | 🚧 实验性 | 需要测试 |

### 性能基准

**测试环境**: 300 向量，128 维度，IVF-PQ 索引

| 操作 | 延迟 | 说明 |
|------|------|------|
| 数据集创建 | 5-10s | IVF-PQ 索引构建 |
| 首次搜索（本地） | 1-3s | 数据集加载 |
| 首次搜索（OSS） | 2-10s | 从 OSS 加载 |
| 后续搜索 | 20-100ms | 数据集已缓存 |
| 内存开销 | ~256MB | Arrow 分配器限制 |

### 版本历史

| 版本 | 日期 | 主要特性 |
|------|------|---------|
| 9.2.4 | 2026-02-07 | P1-P2-P3 合并完成 |
| 9.2.4-beta | 2026-01-15 | P3 分片感知功能 |
| 9.2.4-alpha | 2025-12-20 | P1-P2 NRT 刷新功能 |

### 相关文档

- **架构设计文档**: [lance-vector-架构设计.md](./lance-vector-架构设计.md)
- **回归验证指南**: [reg_validation_guide.md](../reg_validation_guide.md)
- **生产验证报告**: [PRODUCTION-VALIDATION-REPORT.md](../PRODUCTION-VALIDATION-REPORT.md)
- **实现计划**: [P1-P2-IMPLEMENTATION-PLAN.md](../P1-P2-IMPLEMENTATION-PLAN.md)

### 支持与反馈

- **问题报告**: GitHub Issues
- **功能请求**: GitHub Discussions
- **文档改进**: 欢迎提交 PR

---

**文档维护**: Lance Vector Plugin Team
**最后更新**: 2026-02-07
**文档版本**: 1.0.0
