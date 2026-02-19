# Lance Vector Plugin 用户指南

## 目录

- [简介](#简介)
- [安装](#安装)
- [快速开始](#快速开始)
- [字段类型定义](#字段类型定义)
- [向量搜索](#向量搜索)
- [过滤查询](#过滤查询)
- [性能调优](#性能调优)
- [可观测性](#可观测性)
- [近实时刷新](#近实时刷新)
- [配置参考](#配置参考)
- [常见问题](#常见问题)

---

## 简介

Lance Vector Plugin 是 Elasticsearch 的向量字段类型插件，使用 Lance 格式实现外部向量存储。该插件提供：

- **高性能向量搜索**：基于 Lance 的高效向量索引（IVF-PQ）
- **混合过滤支持**：智能预过滤/后过滤自动选择
- **阿里云 OSS 集成**：支持 OSS 对象存储作为向量数据后端
- **近实时刷新**：后台自动检测数据集版本变化并热更新
- **丰富的可观测性**：搜索指标统计、健康状态监控

### 主要特性

| 特性 | 描述 |
|------|------|
| **向量索引** | IVF-PQ 倒排文件乘积量化，支持大规模向量检索 |
| **相似度度量** | Cosine（余弦）、L2（欧氏距离）、Dot（点积） |
| **混合过滤** | 自动选择预过滤或后过滤策略，优化查询性能 |
| **nprobes 调优** | 可配置的探测分区数量，平衡精度与速度 |
| **OSS 存储** | 阿里云 OSS 作为向量数据后端，支持大规模部署 |
| **NRT 刷新** | 可配置的自动刷新间隔，支持手动触发刷新 |

---

## 安装

### 前置要求

- Elasticsearch 9.2.4
- JDK 21
- 阿里云 OSS 账户（如使用 OSS 存储）

### 安装步骤

1. **构建插件**

```bash
cd /path/to/elasticsearch-9.2.4
./gradlew :plugins:lance-vector:assemble
```

2. **安装插件**

```bash
./bin/elasticsearch-plugin install file:///path/to/es-9.2.4-plugins-real-time/plugins/lance-vector/build/distributions/lance-vector-9.2.4.zip
```

3. **配置 OSS 环境变量**（如使用 OSS 存储）

```bash
export OSS_ACCESS_KEY_ID="your_access_key_id"
export OSS_ACCESS_KEY_SECRET="your_access_key_secret"
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"
```

4. **启动 Elasticsearch**

```bash
./bin/elasticsearch
```

---

## 快速开始

### 1. 创建索引

```bash
PUT /lance-demo
{
  "mappings": {
    "properties": {
      "text": { "type": "text" },
      "vector": {
        "type": "lance_vector",
        "dims": 3,
        "similarity": "cosine",
        "index_options": {
          "uri": "oss://denny-test-lance/lance-demo/vector",
          "max_cache_size": 100
        }
      }
    }
  }
}
```

### 2. 插入文档

```bash
POST /lance-demo/_doc/1
{
  "text": "第一篇文档",
  "vector": [0.1, 0.2, 0.3]
}

POST /lance-demo/_doc/2
{
  "text": "第二篇文档",
  "vector": [0.4, 0.5, 0.6]
}
```

### 3. 向量搜索

```bash
GET /lance-demo/_search
{
  "query": {
    "lance_knn": {
      "vector": [0.1, 0.2, 0.3],
      "k": 5
    }
  }
}
```

---

## 字段类型定义

### 基本语法

```json
{
  "properties": {
    "vector_field": {
      "type": "lance_vector",
      "dims": 128,                    // 向量维度
      "similarity": "cosine",          // 相似度度量：cosine/l2/dot
      "index_options": {
        "uri": "oss://bucket/path",    // Lance 数据集 URI
        "max_cache_size": 100          // 最大缓存数据集数量
      }
    }
  }
}
```

### 参数说明

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `dims` | integer | 是 | - | 向量维度 |
| `similarity` | string | 否 | cosine | 相似度度量：cosine、l2、dot |
| `index_options.uri` | string | 是 | - | Lance 数据集 URI（支持 file:// 或 oss://） |
| `index_options.max_cache_size` | integer | 否 | 100 | 数据集缓存最大数量 |

### URI 格式

```
# 本地文件存储
file:///path/to/dataset.lance

# 阿里云 OSS 存储
oss://bucket-name/path/to/dataset.lance
```

---

## 向量搜索

### 基本搜索

```json
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "vector": [0.1, 0.2, 0.3, ...],
      "k": 10
    }
  }
}
```

### 指定向量字段

```json
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "vector": [0.1, 0.2, 0.3, ...],
      "k": 10,
      "field": "my_vector_field"
    }
  }
}
```

### 使用 nprobes 参数（性能调优）

```json
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "vector": [0.1, 0.2, 0.3, ...],
      "k": 10,
      "nprobes": 20
    }
  }
}
```

**nprobes 参数说明：**
- 范围：1-100
- 默认值：20
- 作用：控制 IVF-PQ 索引探测的分区数量
- 权衡：值越大精度越高，但速度越慢

---

## 过滤查询

### 单个过滤条件

```json
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "vector": [0.1, 0.2, 0.3],
      "k": 10,
      "filter": {
        "term": { "category": "electronics" }
      }
    }
  }
}
```

### 多个过滤条件（数组格式）

```json
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "vector": [0.1, 0.2, 0.3],
      "k": 10,
      "filter": [
        { "term": { "category": "electronics" } },
        { "range": { "price": { "lte": 1000 } } }
      ]
    }
  }
}
```

### 布尔过滤

```json
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "vector": [0.1, 0.2, 0.3],
      "k": 10,
      "filter": {
        "bool": {
          "must": [
            { "term": { "status": "active" } }
          ],
          "should": [
            { "term": { "featured": true } }
          ]
        }
      }
    }
  }
}
```

### 混合策略说明

插件会自动选择最优的过滤策略：

| 策略 | 触发条件 | 说明 |
|------|----------|------|
| **预过滤** | 过滤后文档数 < k×2 | 先过滤再搜索，减少向量计算量 |
| **后过滤** | 过滤后文档数 ≥ k×2 | 先搜索再过滤，保证召回结果数 |

---

## 性能调优

### nprobes 参数调优

```json
// 高精度场景（推荐 nprobes=50）
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "vector": [0.1, 0.2, 0.3],
      "k": 10,
      "nprobes": 50
    }
  }
}

// 高性能场景（推荐 nprobes=10）
GET /my-index/_search
{
  "query": {
    "lance_knn": {
      "vector": [0.1, 0.2, 0.3],
      "k": 10,
      "nprobes": 10
    }
  }
}
```

### 缓存配置

```json
PUT /my-index/_settings
{
  "index": {
    "lance": {
      "cache": {
        "max_size": 200,
        "ttl_minutes": 120
      }
    }
  }
}
```

### 相似度度量选择

| 度量 | 适用场景 | 特点 |
|------|----------|------|
| cosine | 文本嵌入、归一化向量 | 方向相似性，不受向量长度影响 |
| l2 | 图像特征、原始向量 | 欧氏距离，考虑绝对位置 |
| dot | 预归一化向量 | 点积，计算最快 |

---

## 可观测性

### 统计信息 API

```bash
GET /_lance/stats
```

**响应示例：**

```json
{
  "cache": {
    "size": 5,
    "max_size": 100,
    "ttl_minutes": 60
  },
  "memory": {
    "allocated_bytes": 52428800,
    "allocated_mb": 50
  },
  "health": "GREEN",
  "search": {
    "total_searches": 12500,
    "total_search_time_ms": 2450,
    "filtered_searches": 3200,
    "pre_filter_searches": 1800,
    "post_filter_searches": 1400,
    "search_errors": 0
  }
}
```

### 指标说明

| 指标 | 说明 |
|------|------|
| `cache.size` | 当前缓存的数据集数量 |
| `cache.max_size` | 缓存最大容量 |
| `memory.allocated_mb` | Arrow 内存分配量（MB） |
| `health` | 健康状态：GREEN/YELLOW/RED |
| `search.total_searches` | 总搜索次数 |
| `search.filtered_searches` | 带过滤条件的搜索次数 |
| `search.pre_filter_searches` | 使用预过滤策略的次数 |
| `search.post_filter_searches` | 使用后过滤策略的次数 |
| `search.search_errors` | 搜索错误次数 |

---

## 近实时刷新

### 配置自动刷新

```json
PUT /_cluster/settings
{
  "persistent": {
    "lance.refresh.enabled": true,
    "lance.refresh.interval": "30s"
  }
}
```

### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `lance.refresh.enabled` | boolean | true | 是否启用自动刷新 |
| `lance.refresh.interval` | time | 30s | 刷新检查间隔 |

### 手动触发刷新

```bash
POST /_lance/refresh
```

**响应示例：**

```json
{
  "acknowledged": true,
  "message": "Lance dataset refresh triggered"
}
```

### 刷新机制说明

Lance Refresh Service 采用基于 manifest 的版本检测机制：

1. **版本检测**：后台服务定期检查 Lance 数据集的 manifest 文件
2. **原子替换**：检测到版本变化时，使用 `VersionedDataset` 进行原子替换
3. **无锁读取**：使用 `AtomicReference` 实现，读取过程无锁，不影响查询性能
4. **优雅关闭**：旧版本数据集在所有进行中的查询完成后自动释放

```
┌─────────────────────────────────────────────────────┐
│                   Lance Refresh Service             │
├─────────────────────────────────────────────────────┤
│  ┌─────────┐    ┌─────────┐    ┌─────────┐         │
│  │ Poll    │───▶│ Detect  │───▶│ Swap    │         │
│  │ Interval│    │ Version │    │ Dataset │         │
│  └─────────┘    └─────────┘    └─────────┘         │
│       │                                │            │
│       ▼                                ▼            │
│  configurable                  AtomicReference     │
│  (default: 30s)                  (Lock-free)       │
└─────────────────────────────────────────────────────┘
```

---

## 配置参考

### 集群级别设置

```json
PUT /_cluster/settings
{
  "persistent": {
    "lance": {
      "refresh": {
        "enabled": true,
        "interval": "30s"
      },
      "cache": {
        "max_size": 100,
        "ttl_minutes": 60
      }
    }
  }
}
```

### 索引级别设置

```json
PUT /my-index/_settings
{
  "index": {
    "lance": {
      "pre_filter": "auto"
    }
  }
}
```

### 完整配置示例

```json
PUT /_cluster/settings
{
  "persistent": {
    "lance.refresh.enabled": true,
    "lance.refresh.interval": "60s"
  }
}

PUT /my-index/_settings
{
  "index": {
    "lance.cache.max_size": 200,
    "lance.pre_filter": "always"
  }
}
```

---

## 常见问题

### Q1: 如何选择合适的 nprobes 值？

**A:** nprobes 是性能和精度的权衡参数：

- **高精度场景**（推荐）：nprobes = 50
- **平衡场景**（默认）：nprobes = 20
- **高性能场景**：nprobes = 10

建议通过实际测试找到最佳值。

### Q2: 预过滤和后过滤有什么区别？

**A:**

| 策略 | 流程 | 适用场景 |
|------|------|----------|
| 预过滤 | 先过滤文档，再在过滤结果中搜索 | 过滤后文档少（< k×2） |
| 后过滤 | 先全局搜索，再过滤结果 | 过滤后文档多（≥ k×2） |

插件会自动选择最优策略。

### Q3: 如何配置 OSS 存储？

**A:** 需要在启动 ES 前设置环境变量：

```bash
export OSS_ACCESS_KEY_ID="your_key_id"
export OSS_ACCESS_KEY_SECRET="your_key_secret"
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

./bin/elasticsearch
```

### Q4: 数据集缓存策略是什么？

**A:**

- **LRU 淘汰**：使用 LRU 策略淘汰最少使用的数据集
- **TTL 过期**：默认 60 分钟未使用则过期
- **容量限制**：默认最多缓存 100 个数据集

### Q5: 如何监控向量搜索性能？

**A:** 使用 `GET /_lance/stats` 查看：

- 总搜索次数和平均耗时
- 过滤策略分布（预过滤 vs 后过滤）
- 错误次数
- 内存使用情况

### Q6: NRT 刷新会影响查询性能吗？

**A:** 不会。刷新机制使用：

- **AtomicReference**：无锁读取，查询不受影响
- **原子替换**：新版本数据集瞬间替换
- **延迟释放**：旧数据集在所有查询完成后才释放

---

## 技术支持

如有问题或建议，请访问：
- GitHub Issues: [项目地址]
- 文档: `docs/` 目录
- 测试: `plugins/lance-vector/src/test/`

---

**版本**: 9.2.4
**更新日期**: 2026-02-07
