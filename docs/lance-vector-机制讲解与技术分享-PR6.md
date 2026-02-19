# Lance Vector 机制讲解与技术分享（PR #6）

## 文档信息

- 分享主题：Lance 原生过滤下推 + 分片策略 + 刷新机制
- 对应 PR：`https://github.com/dennywu2966/elasticsearch/pull/6`
- 代码基线：`es-9.2.4-plugins-rt-scale`（含后续修复提交）
- 受众：搜索平台工程师、向量检索开发、SRE
- 最后更新：2026-02-09

---

## Slide 1：背景与目标

### 背景问题

1. 过滤与向量检索割裂，导致候选过大、延迟不稳定
2. 多分片场景下，Lance 数据集与 ES 路由关系不清晰
3. 外部数据更新后，查询侧刷新链路不完整

### 本次交付目标

1. 支持 Lance 侧 SQL 过滤下推（v1）
2. 引入可配置分片策略（`NONE` / `ES_ROUTING`）
3. 补齐 NRT 刷新入口与服务生命周期
4. 修复若干正确性问题（相似度、路由边界、并发刷新）

---

## Slide 2：PR #6 能力总览

### 新增能力

1. `field_mapping` 驱动 ES 过滤器到 Lance SQL 转换
2. `uri_prefix + shard_path + dataset_name` 分片感知 URI 解析
3. `lance_knn.nprobes` 打通到执行层
4. 新增 `POST /_lance/refresh`（手动刷新）
5. 保留 `GET /_lance/stats`（可观测）

### 关键类

- 映射层：`LanceVectorFieldMapper`、`LanceStorageConfig`
- 查询层：`LanceKnnQueryBuilder`、`LanceKnnQuery`
- 存储层：`RealLanceDataset`、`LanceDatasetRegistry`
- 刷新层：`LanceRefreshService`、`RestLanceRefreshAction`

---

## Slide 3：总体架构图（逻辑）

```text
Search Request
   |
   v
LanceKnnQueryBuilder (parse field/query_vector/k/num_candidates/filter/nprobes)
   |
   v
LanceKnnQuery.createWeight()
   |-- resolveUri(index, shard)
   |-- tryConvertFilterToSql(filter, field_mapping)
   |-- registry.getOrLoad(uri)
   |-- dataset.search(..., nprobes, sqlFilter, similarity)
   |
   v
buildDocScores()
   |-- Lucene _id join
   |-- ES filter post-check (fallback/兜底)
   v
Top-K hits
```

---

## Slide 4：过滤下推机制（v1）

### 能做什么

1. 支持 `TermQuery -> SQL` 转换
2. 支持字段映射：`es_field -> lance_column`
3. 支持 SQL 字符串转义（防注入）

### 不能做什么（当前）

1. 复杂 bool/range 组合不保证可下推
2. 未映射字段不下推

### 设计策略

`try -> fallback`

1. 能转换：走 Lance SQL 下推
2. 不能转换：自动回退到 ES 后过滤
3. 正确性优先，不因为下推失败而丢结果

---

## Slide 5：分片感知与路由策略

### 两种模式

1. Legacy 单 URI：所有分片共享同一个数据集
2. Shard-aware：每个分片解析并访问自己的数据集 URI

### `sharding_strategy`

1. `NONE`：不做候选分片过滤
2. `ES_ROUTING`：按 ES 路由算法过滤候选

### 路由正确性修复

- 由 `Math.abs(hash) % n` 改为 `Math.floorMod(hash, n)`
- 修复 `Integer.MIN_VALUE` 边界，避免候选误丢失

---

## Slide 6：`nprobes` 与相似度语义

### 旧风险

在部分路径中，`nprobes` / pushdown 查询会把相似度硬编码成 `cosine`，导致 `dot_product/l2` 排序语义被破坏。

### 现状

1. `nprobes` 从 DSL 解析后透传到执行
2. 执行路径携带 mapping 配置的 `similarity`
3. 相似度语义在不同查询路径保持一致

### 结果

- 用户调优 `nprobes` 时不会引入隐式相关性回归

---

## Slide 7：刷新机制与生命周期

### 刷新入口

1. 自动刷新：`lance.refresh.enabled=true` 时插件启动即拉起定时服务
2. 手动刷新：`POST /_lance/refresh`

### 刷新行为

- 当前实现为缓存失效（invalidate）策略
- 下一次查询按 URI 重新加载数据集

### 关键修复

1. Refresh handler 已注册，接口可用
2. Refresh service 在启用时会实际 `start()`

---

## Slide 8：刷新并发安全修复

### 问题

刷新时如果直接关闭缓存数据集，可能与正在执行的查询并发冲突，导致查询期间对象被关闭。

### 方案

在 `LanceDatasetRegistry` 引入查询-刷新读写锁：

1. 查询路径：`withSearchLock`（读锁）
2. 刷新路径：`clear/invalidate`（写锁）

### 效果

- 刷新会等待在途查询完成
- 避免“查询中 dataset 被关闭”的间歇性故障

---

## Slide 9：可观测性与运维抓手

### `/_lance/stats` 提供

1. 缓存规模与 TTL 视角
2. Arrow/native 内存视角
3. 查询计数、过滤计数、错误计数

### 运维建议

1. 观察 `total_searches` 与 `search_errors` 趋势
2. 配合刷新操作观察缓存变化与查询延迟
3. 高频刷新场景重点关注并发与内存指标

---

## Slide 10：兼容性与回退策略

### 兼容原则

1. 不配置 `field_mapping` 时，行为与历史版本一致
2. 继续支持 legacy `storage.uri`
3. 下推失败自动回退，不影响查询可用性

### 回退手段

1. 关闭下推：删除 `field_mapping`
2. 关闭候选分片过滤：`sharding_strategy=NONE`
3. 关闭自动刷新：`lance.refresh.enabled=false`

---

## Slide 11：建议演示脚本（Tech Share Demo）

1. 创建带 `field_mapping` 的索引
2. 执行 `term` 过滤查询，展示结果正确
3. 使用 `lance_knn` 对比 `nprobes=10` 与 `nprobes=50`
4. 调用 `POST /_lance/refresh` 强制刷新
5. 查看 `GET /_lance/stats` 观察指标变化
6. 分片模式切换 `ES_ROUTING` 与 `NONE` 对比候选行为

---

## Slide 12：Q&A 备答（常见问题）

1. 为什么下推只先做 Term？
- 先保证可控与可回退，避免一次性扩展导致语义风险。

2. 为什么刷新是失效重载，不是增量更新？
- 当前优先保证稳定性与正确性，增量刷新需要更强的数据版本/并发协议。

3. 什么时候用 `ES_ROUTING`？
- 仅当离线构建分片规则与 ES 路由一致时使用；否则 `NONE` 更稳妥。

4. `knn` 和 `lance_knn` 怎么选？
- 日常场景用 `knn`；需显式控制 `nprobes` 时用 `lance_knn`。

---

## 附录：一次分享可直接复用的提纲

1. 问题定义：过滤、分片、刷新三类痛点
2. 方案概览：pushdown + shard strategy + refresh lifecycle
3. 正确性修复：相似度、路由、并发
4. 运维与回退：stats、refresh、配置开关
5. 后续演进：更多过滤类型下推、版本化平滑 reload
