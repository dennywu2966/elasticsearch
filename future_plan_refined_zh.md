# 未来开发计划（精炼版）
# Elasticsearch 插件：Lance Vector & Cloud IAM

**状态**：Draft v2.0（Refined）  
**最后更新**：2026-02-01  
**修订来源**：基于 `../es-9.2.4-plugins/future_plan.md`（Draft v1.0）并结合代码现状（`plugins/lance-vector/`、`plugins/security-realm-cloud-iam/`）做批判性/创造性重构  
**相关文档**：`../es-9.2.4-plugins/highlevel-design.md`

---

## 修订要点（相对 v1.0）

1. **命名与现状对齐**：代码中查询实现为 `LanceKnnQuery` / `LanceKnnQueryBuilder`，不是 `LanceVectorQuery`。  
2. **范围收敛**：把“Lucene segments 上 OSS / take-over mode”等**多季度且涉及 Lucene 内核**的想法，明确为“长期研究/另立项目”，避免和插件可交付项混在一张路线图里。  
3. **把“P1 Zero-copy JNI”改为“JNI/Arrow 内存治理 + 拷贝压缩”**：当前 `RealLanceDataset` 已使用 `lance-java`（JNI + Arrow IPC），真正的瓶颈更可能在**资源生命周期、并发安全、过滤语义、跨分片一致性与可观测性**。  
4. **把“预过滤”拆成可落地的三种策略**（后文 P1.2），避免在没有数据规模与过滤选择率假设时直接承诺“ES 先算候选 docIDs 再喂给 Lance”。  
5. **把 Cloud IAM 的“返回 token”改为可实现路径**：Elasticsearch 的 `/_security/_authenticate` 是既有 API，realm 插件不应尝试“改写”它；若需要“换 token”，应走**自定义 REST API/外部服务/或 Kibana 侧能力**。

---

## 当前基线（基于代码现状的事实）

### Lance Vector 插件（`plugins/lance-vector/`）

- **Mapping 类型**：`"type": "lance_vector"`（只读，Phase 1 不写入向量）。  
- **查询 DSL**：`"lance_knn"`（`LanceKnnQueryBuilder` → `LanceVectorFieldType.createKnnQuery()` → `LanceKnnQuery`）。  
- **数据集加载**：`LanceDatasetRegistry` 带缓存；`.lance`/`oss://` 走 `RealLanceDataset`，其他（测试 JSON）走 `FakeLanceDataset`。  
- **生产数据路径**：`RealLanceDataset` 使用 `lance-java`（JNI + Arrow）做近邻搜索并读取 `_distance/distance`。  
- **OSS 相关**：`RealLanceDataset` 通过环境变量（`OSS_ENDPOINT` / `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET`）配置 object store；`OssStorageAdapter` 目前是简化占位实现。  
- **Profiling**：存在 `LanceTimingContext` + `LanceTimer`，并可把 timing 塞进 query profile 的 debug data。  

### Cloud IAM 插件（`plugins/security-realm-cloud-iam/`）

- **Realm 类型**：`cloud_iam`，支持两种认证入口：
  - 自定义 header（默认 `X-ES-IAM-Signed`）的 STS 签名路径；
  - `Authorization: Bearer ...` 的 OAuth token 路径（`OAuthTokenValidator` 调 userinfo endpoint）。  
- **缓存/防重放**：user cache、negative cache、nonce cache 均已存在（可配置 TTL 和容量）。  
- **已知差异/限制**：OAuth userinfo 的 `type` 解析缺少 assumed-role 的完整覆盖（见 `LIMITATION.md` 的分析）。  

---

## 总体目标与非目标

### 总体目标

1. **可上线**：稳定、可观测、可回滚、默认安全（尤其是凭证与 token 处理）。  
2. **语义正确**：过滤/评分/跨分片行为可解释、可测试。  
3. **可扩展**：在不破坏现有用户的前提下逐步引入更高性能与更多后端。  

### 非目标（短期明确不做）

- 在插件路线图中承诺“Lucene 段文件直接上 OSS 的 take-over mode”。该方向需要 Lucene/ES 内核改造，建议另起“长期研究”项目。  
- 在 realm 插件内“改写”或“替代”ES 内置安全 API（例如 `/_security/_authenticate` 的语义与返回结构）。  

---

## 路线图总览（建议）

| 阶段 | 范围 | 目标 | 交付门槛（DoD） | 优先级 |
|------|------|------|------------------|--------|
| P0 | Lance | 生产化加固（阻塞） | 7 天 soak + 资源泄漏基线清零/可解释 | Critical |
| P1 | Lance | 查询语义与性能 | filter 可用 + 参数可控 + 性能基线 | High |
| P2 | Lance | NRT/刷新 | 可配置刷新 + 平滑 reload + 指标 | Medium |
| P3 | Lance | 分片一致性与规模化 | shard↔dataset 规则明确 + 压测报告 | High |
| P4 | Lance | 通用后端框架 | SPI + 参考实现 + 文档 | Low |
| C0 | IAM | 生产化加固 | 无敏感日志 + token 内存/缓存策略 + 指标 | Critical |
| C1 | IAM | 能力补齐 | OAuth/STS 一致性（assumed-role 等）+ 集成方案 | Medium |

> 注：同一季度内可以并行推进，但 P0/C0 是所有后续功能的前置“质量闸门”。

---

## Lance Vector 插件计划

### P0：生产化加固（BLOCKER）

目标：在扩展功能前，把“能跑”提升到“可长期跑、可定位问题、可控风险”。

#### P0.1 资源与内存治理（native/JNI/Arrow）

- [ ] **缓存驱逐必关闭资源**：`LanceDatasetRegistry` 当前的自动过期/驱逐需确保调用 `LanceDataset.close()`（避免 native dataset 句柄泄漏）。  
- [ ] **Arrow allocator 策略固化**：明确 `RootAllocator` 限额、child allocator 的使用边界、以及在压力下的失败模式（OOM vs graceful degrade）。  
- [ ] **避免不可预测的环境变量反射修改**：`RealLanceDataset` 通过反射写 `System.getenv()` 风险较高；优先寻找 lance-java/object store 的显式配置入口；若短期必须保留，至少补齐线程安全/生效时机/回滚策略说明。  
- [ ] **敏感信息保护**：严禁在日志/异常中输出 endpoint 之外的明文凭证；统一使用 secure settings/keystore 读取。  

#### P0.2 并发与线程安全

- [ ] 明确 `com.lancedb.lance.Dataset` / `LanceScanner` 的并发语义；必要时引入池化或同步策略。  
- [ ] 增加并发压力测试：并发查询 + 缓存命中/驱逐 + dataset reload（为 P2 铺路）。  

#### P0.3 正确性（尤其是跨分片）

- [ ] **明确数据模型**：一个 index 对应一个 Lance dataset，还是每 shard 一个 dataset 分区？  
  - 若是“一个 index 一个 dataset”，需要解释并验证：每个 shard 用同一候选集做 `_id` join 会造成的 recall/ranking 偏差。  
  - 若是“每 shard 一个 dataset”，需要定义 shard↔dataset URI 的映射规则（见 P3）。  
- [ ] 将 `_id` join 的复杂度与限制写入文档（候选数、段数、`_id` 词典成本），并提供默认参数建议。  

#### P0.4 可观测性与日志

- [ ] 统一日志级别：禁止 per-candidate 的 `INFO` 日志，默认只输出聚合指标与异常关键信息。  
- [ ] 指标体系：至少包含 query 计数、p50/p95/p99 延迟、dataset open/scan 的耗时、缓存命中率、native 内存占用。  
- [ ] 健康检查：给出“dataset 可读 / 权限可用 / schema 符合预期”的检测手段（可先从日志与指标开始）。  

#### P0.5 测试与发布门槛

- [ ] 长稳测试：24h→7 天 soak，指标包含 native 内存、FD、CPU、GC、延迟漂移。  
- [ ] 兼容性测试：不同 Lance 版本的 distance 字段差异（`_distance` vs `distance`）必须可检测、可降级、可告警。  

**P0 交付物（DoD）**：一份“生产基线报告”（资源泄漏、并发稳定性、性能基线、回滚步骤），并通过 7 天 soak。

---

### P1：查询语义与性能

目标：让功能“可用且可控”，并在关键路径上做可验证的性能改进。

#### P1.1 DSL 对齐与参数化

- [ ] `lance_knn` 支持 `filter` 子句（当前 `LanceVectorFieldType.createKnnQuery(...)` 已接收 filter，但 `LanceKnnQueryBuilder` 传 `null`）。  
- [ ] 参数策略统一：`k`、`num_candidates`、`similarity`（是否允许 query 覆盖 mapping）给出明确优先级与校验。  
- [ ] 暴露/配置 `nprobes` 等 ANN 参数（至少 index 级别可调，后续可扩展到 query 级别）。  

#### P1.2 预过滤（Pre-filtering）三种可落地路径

> 这里的“预过滤”目标是：在带过滤条件时仍能稳定返回 top-k，并且避免盲目把 `num_candidates` 拉得很大。

1. **策略 A：后过滤 + 自适应过采样（优先落地）**  
   - Lance 先取候选；Lucene filter 再筛；若不足 k，按上限分段增大 `num_candidates` 重试。  
2. **策略 B：过滤选择率驱动的过采样**  
   - 估算 filter 选择率（segment doc count / matched doc count），动态设定过采样倍率。  
3. **策略 C：数据侧 filter pushdown（中长期）**  
   - 将部分可过滤字段冗余到 Lance dataset，允许 Lance 侧先过滤再做 kNN（需要数据链路配合）。  

#### P1.3 关键路径优化（以数据证明）

- [ ] `_id` join 优化：减少 per-candidate `postings(term)` 的随机查找成本（可考虑批量 terms enum、缓存热点映射等）。  
- [ ] 评分与排序稳定性：明确 distance→score 的转换公式与单测覆盖；保证不同 similarity 下的排序一致性可解释。  
- [ ] 建立基准：固定数据集（不同规模/维度）+ 固定查询集，给出吞吐与延迟对比（before/after）。  

**P1 交付物（DoD）**：filter 可用（语义文档+测试），参数可控（含默认值策略），性能基准可复现。

---

### P2：NRT/数据集刷新

目标：外部数据集更新后，ES 能以可控方式感知并刷新（不要求强一致，但要“最终一致 + 可观测”）。

- [ ] 增加 `refresh_interval`（默认 30s 或关闭），并实现 manifest/元数据的变更检测（ETag/版本号）。  
- [ ] 平滑 reload：新 dataset 就绪后切换引用，避免正在进行的查询被中断。  
- [ ] 提供手动失效机制（例如管理 API / 运维脚本调用 `invalidate(uri)` 的能力）。  
- [ ] 指标：刷新延迟、刷新次数、失败次数、当前活跃版本。  

**P2 交付物（DoD）**：可配置刷新 + reload 不影响线上查询 + 指标可观测。

---

### P3：分片一致性与规模化

目标：明确“ES shard 与 Lance dataset/partition 的关系”，并在大规模下行为稳定。

- [ ] **映射规则**：定义 `storage.uri` 的 shard-aware 规则（例如支持模板化：`.../index=${index}/shard=${shardId}`），并在建索引时校验。  
- [ ] **不支持动态 split/merge（短期决策）**：在 P3 前明确为“不支持”，需要通过 reindex/重建 dataset 完成扩容或重分片。  
- [ ] 扩展压力测试：100+ shards、海量向量、热点查询、冷启动缓存策略。  

**P3 交付物（DoD）**：一份“分片一致性设计+压测报告”，并明确不支持的场景与替代方案。

---

### P4：通用后端框架（低优先级）

目标：把“外部向量存储”抽象成可插拔后端，降低未来接入成本。

- [ ] 定义 `StorageBackend`/SPI（包含 open/scan/health/metrics 等最小面）。  
- [ ] Lance 作为参考实现；选择 1 个候选后端做 PoC（例如 Paimon/某 OLAP）。  
- [ ] 第三方接入文档与示例工程。  

---

## Cloud IAM 插件计划

### C0：生产化加固（BLOCKER）

- [ ] **禁止敏感日志**：清理 `System.err.println`、token 截断输出等调试代码；统一使用安全日志策略。  
- [ ] **token 内存与清理**：`CloudIamToken` 的 OAuth token 当前无法在 `clearCredentials()` 中清理（字段为 `final`）；改为可清理结构（例如 `SecureString`/可变引用）并确保不被缓存键泄漏。  
- [ ] **缓存键脱敏**：OAuth token 的 cacheKey 不能使用明文前缀；改为哈希（带 salt）或由服务端生成的稳定标识。  
- [ ] **指标**：认证耗时（STS/OAuth 分开）、错误率、缓存命中、nonce 重放次数、下游 IAM 调用失败分布。  
- [ ] **回归测试**：覆盖 STS/OAuth 两条路径的边界条件（时钟偏移、nonce、role mapping 空集合、下游超时）。  

**C0 交付物（DoD）**：默认安全（无敏感输出）、可观测（指标/日志）、回归测试覆盖关键风险点。

---

### C1：能力补齐与一致性（OAuth/STS parity）

- [ ] **assumed-role 支持补齐**：OAuth userinfo 的 `type/sub` 解析补齐 assumed-role，并尽量复用 ARN 解析逻辑，避免两条路径语义分叉。  
- [ ] **认证优先级与模式**：明确 `Authorization` vs `X-ES-IAM-Signed` 同时存在时的决策，并提供配置开关（例如强制 STS/强制 OAuth）。  
- [ ] **“返回 token”需求落地方案**：  
  - 方案 1（推荐）：在 ES 外部提供 token exchange 服务；ES 仅做鉴权（realm）。  
  - 方案 2：插件提供自定义 REST API（例如 `/_security/cloud_iam/_token`），但需明确与 ES Token Service/Kibana 的集成边界。  

**C1 交付物（DoD）**：OAuth/STS 行为一致（principalType、role mapping），并给出可运行的 token 集成方案（不侵入 ES 内置 API）。

---

## 执行节奏（建议，需按资源调整）

| 时间 | 重点 | 备注 |
|------|------|------|
| 2026 Q1 | P0 + C0 | 先把上线门槛补齐 |
| 2026 Q2 | P1 + C1 | filter/parity + 性能基线 |
| 2026 Q2–Q3 | P2 | 视业务是否需要 NRT |
| 2026 Q3 | P3 | 分片与规模化压测 |
| 2026 Q4 | P4 | 低优先级，可后置 |

---

## 风险清单（更新版）

| 风险 | 影响 | 缓解 |
|------|------|------|
| 缓存驱逐不关闭 dataset | High | 驱逐回调/显式 close + soak 验证 |
| JNI/Arrow native 内存不可控 | High | allocator 限额、指标、压测、故障模式说明 |
| 通过反射改环境变量导致不可预测行为 | Medium | 替换为显式配置；最小化作用域 |
| 过滤 + top-k 不足导致结果不稳定 | Medium | 自适应过采样/迭代策略 + 语义文档 |
| 跨分片语义不清导致 recall/ranking 偏差 | High | 明确 shard↔dataset 规则 + 压测验证 |
| OAuth token 泄漏（日志/缓存键/内存不可清理） | High | C0 作为阻塞项处理 |

---

## 开放问题（待定稿前确认）

1. **Lance dataset 的“分片粒度”**：一份 dataset 对应一个 index 还是一个 shard？是否需要 URI 模板化？  
2. **NRT 刷新触发方式**：轮询（ETag/manifest） vs 事件驱动（EventBridge 等）；先用轮询是否足够？  
3. **过滤策略选择**：默认用策略 A（后过滤 + 自适应过采样）是否能覆盖主要场景？哪些场景必须做数据侧 pushdown？  
4. **Cloud IAM 的 token 需求边界**：是“让 ES 返回 token”还是“让客户端拿到 token 后再访问 ES”？如果是后者，realm 插件只需鉴权即可。  

