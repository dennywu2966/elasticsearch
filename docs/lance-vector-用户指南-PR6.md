# Lance Vector Plugin 用户指南（PR #6 最新版）

## 文档信息

- 文档版本：2.0
- 适配分支：`es-9.2.4-plugins-rt-scale`
- 对应 PR：`https://github.com/dennywu2966/elasticsearch/pull/6`
- 最后更新：2026-02-09
- 目标读者：使用方开发、运维、平台工程团队

---

## 1. 这份指南覆盖什么

本指南聚焦 PR #6 及后续修复后的可用能力，重点包括：

1. Lance 原生过滤下推（`field_mapping` + SQL pushdown）
2. 可配置分片策略（`sharding_strategy` + shard-aware URI）
3. `nprobes` 查询参数打通到执行链路
4. 近实时刷新服务（自动 + 手动）
5. 关键正确性修复（相似度保持、`floorMod` 路由、刷新并发保护）

---

## 2. 快速开始（最小可用示例）

### 2.1 创建索引（单 URI 模式）

```json
PUT /products
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "category": { "type": "keyword" },
      "brand": { "type": "keyword" },
      "embedding": {
        "type": "lance_vector",
        "dims": 768,
        "similarity": "dot_product",
        "storage": {
          "type": "external",
          "uri": "oss://my-bucket/lance/products.lance",
          "lance_id_column": "doc_id",
          "lance_vector_column": "emb",
          "field_mapping": "category=product_category,brand=brand_name",
          "read_only": true
        }
      }
    }
  }
}
```

### 2.2 标准 `knn` 查询（默认 `nprobes=20`）

```json
POST /products/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, 0.3, 0.4],
    "k": 10,
    "num_candidates": 100,
    "filter": {
      "term": { "category": "electronics" }
    }
  }
}
```

### 2.3 使用 `lance_knn` 显式调优 `nprobes`

```json
POST /products/_search
{
  "query": {
    "lance_knn": {
      "field": "embedding",
      "query_vector": [0.1, 0.2, 0.3, 0.4],
      "k": 10,
      "num_candidates": 100,
      "nprobes": 48,
      "filter": {
        "term": { "category": "electronics" }
      }
    }
  }
}
```

---

## 3. 映射与配置说明

### 3.1 `lance_vector` 核心参数

| 参数 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `dims` | 是 | - | 向量维度，必须与 Lance 数据集一致 |
| `similarity` | 否 | `cosine` | `cosine` / `dot_product` / `l2` |
| `storage.uri` | 条件 | - | 传统单 URI 模式 |
| `storage.uri_prefix` | 条件 | - | 分片感知模式入口 |
| `storage.shard_path` | 否 | 空 | 支持 `{index}`、`{shard_id}` 占位符 |
| `storage.dataset_name` | 否 | `data.lance` | 分片路径未以 `.lance` 结尾时生效 |
| `storage.lance_id_column` | 否 | `_id` | Lance 数据集中的 ID 列名 |
| `storage.lance_vector_column` | 否 | `vector` | Lance 数据集中的向量列名 |
| `storage.field_mapping` | 否 | - | `es_field=lance_col,...`，用于过滤下推 |
| `storage.sharding_strategy` | 否 | shard-aware 时 `ES_ROUTING`，legacy 时 `NONE` | `NONE` / `ES_ROUTING` |
| `storage.read_only` | 否 | `true` | 当前阶段只支持只读外部数据集 |

### 3.2 `field_mapping` 格式要求

- 格式：`es_field1=lance_col1,es_field2=lance_col2`
- 示例：`"field_mapping": "category=product_category,brand=brand_name"`
- 错误格式会在建索引时抛出 `MapperParsingException`

### 3.3 自定义 ID/向量列的注意事项

如果 Lance 数据集不是 `_id` 和 `vector`，必须显式配置：

```json
"lance_id_column": "doc_id",
"lance_vector_column": "emb"
```

查询执行阶段会按你配置的列读取，不再硬编码 `_id/vector`。

---

## 4. 分片感知模式（Shard-aware）

### 4.1 推荐配置

```json
PUT /products-sharded
{
  "settings": {
    "number_of_shards": 4,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 768,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri_prefix": "oss://my-bucket/prod",
          "shard_path": "{index}/shard-{shard_id}",
          "dataset_name": "vectors.lance",
          "sharding_strategy": "ES_ROUTING",
          "lance_id_column": "_id",
          "lance_vector_column": "vector",
          "read_only": true
        }
      }
    }
  }
}
```

### 4.2 URI 解析规则

- 仅允许占位符：`{index}`、`{shard_id}`
- `shard_path` 若以 `.lance` 结尾，则视为完整数据集路径，不能再配置 `dataset_name`
- `dataset_name` 为空时默认 `data.lance`

### 4.3 `sharding_strategy` 选择建议

1. `ES_ROUTING`（推荐）
- 适用于你的离线分片规则与 ES 路由一致时
- 查询后会基于 Murmur3 + `floorMod` 做候选归属过滤

2. `NONE`
- 不做候选分片过滤
- 适合单数据集或无法保证路由一致的场景

---

## 5. 过滤下推（Native Filter Pushdown）

### 5.1 生效条件

同时满足以下条件才会走 Lance SQL 下推：

1. 映射配置了 `storage.field_mapping`
2. 过滤器可转换（当前 v1 仅保证 `TermQuery`）
3. 字段存在映射（ES 字段名 -> Lance 列名）

### 5.2 下推失败的行为

以下情况会自动回退到 ES 侧后过滤，不影响结果正确性：

- 过滤器类型不支持（例如复杂 bool/range 组合）
- 字段未配置映射
- SQL 转换异常

### 5.3 安全性

- 字符串值会做 SQL 单引号转义（`'` -> `''`）
- 例如 `O'Reilly` 会转换为 `O''Reilly`

---

## 6. `nprobes` 与相似度行为

### 6.1 `nprobes` 生效路径

- `nprobes` 由 `LanceKnnQueryBuilder` 解析
- 透传到 `LanceKnnQuery`，最终传入数据集执行接口
- 用于控制 IVF 分区探测数量

### 6.2 调优建议

1. 低延迟优先：`nprobes` 取 8~20
2. 召回优先：`nprobes` 取 32~100
3. 先固定 `k/num_candidates`，再单独调 `nprobes`

### 6.3 正确性保证

在 `nprobes`/pushdown 路径上会保持字段映射中配置的 `similarity`，不会被强制改为 `cosine`。

---

## 7. 近实时刷新（NRT）

### 7.1 相关设置

| 设置项 | 默认值 | 说明 |
|---|---|---|
| `lance.refresh.enabled` | `true` | 是否启动自动刷新服务 |
| `lance.refresh.interval` | `30s` | 自动刷新周期，最小 `1s` |

### 7.2 自动刷新机制

1. 插件启动时（`createComponents`）根据 `lance.refresh.enabled` 决定是否启动服务
2. 定时任务触发 `refreshAll()`
3. `refreshAll()` 使缓存失效；下一次查询会按 URI 重新加载数据集

### 7.3 手动刷新接口

```bash
curl -k -u elastic:*** -X POST "https://localhost:9200/_lance/refresh"
```

成功返回示例：

```json
{
  "acknowledged": true,
  "message": "Lance dataset refresh triggered"
}
```

### 7.4 并发安全

刷新失效与查询执行之间有读写锁保护：

- 查询路径持有读锁
- 刷新失效持有写锁

这样可以避免查询中途数据集被关闭导致的并发错误。

---

## 8. 可观测性与运维

### 8.1 统计接口

```bash
curl -k -u elastic:*** "https://localhost:9200/_lance/stats"
```

包含：

- 缓存信息：`cache.size/max_size/ttl_minutes`
- 内存信息：`memory.allocated_bytes/allocated_mb`
- 健康状态：`health`
- 查询指标：`total_searches`、`filtered_searches`、`post_filter_searches`、`search_errors` 等

### 8.2 排障建议

1. 查询为空
- 检查 ES 文档 `_id` 是否与 Lance ID 列一致
- 检查 `lance_id_column` 配置是否正确

2. 过滤未下推
- 检查 `field_mapping` 是否配置
- 检查过滤器是否为可转换类型（优先用 `term` 验证）

3. 分片模式召回异常
- 检查离线构建时分片规则是否与 ES 路由一致
- 不能保证一致时改用 `sharding_strategy: NONE`

4. 刷新后结果未变化
- 先手动调用 `POST /_lance/refresh`
- 再观察 `_lance/stats` 中缓存与查询指标变化

---

## 9. 兼容性与升级说明

1. 向后兼容
- 现有 `storage.uri` 模式可继续使用
- 不配置 `field_mapping` 时保持旧行为（ES 后过滤）

2. 迁移到 shard-aware 建议路径
- 先准备按分片组织的 Lance 数据
- 新建索引并配置 `uri_prefix/shard_path`
- 对比召回与延迟后再切流

3. 上线检查清单
- 映射中的 `dims/similarity/id/vector` 与数据集一致
- `/_lance/stats` 可访问
- `/_lance/refresh` 可触发
- 至少覆盖 1 组带 filter + `nprobes` 的回归查询

---

## 10. 参考文档

- `docs/lance-vector-机制讲解与技术分享-PR6.md`
- `docs/lance-vector-架构设计.md`
- `docs/lance-vector-docs-index.md`

