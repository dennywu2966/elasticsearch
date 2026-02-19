# Lance Vector + Cloud IAM + IDaaS(LDAP) 技术分享

## 基于最新 PR #6 与双项目联读

- 主仓库：`es-9.2.4-plugins-rt-scale`
- 关联仓库：`../ldap-demo`
- 目标：统一说明向量检索链路与云身份链路，面向技术评审与团队宣讲

---

## 目录

### Part 1: Lance Vector

1. 多分片支持
2. NRT 刷新机制
3. Prefilter（过滤下推 + 策略）
4. 高层架构与数据流（数据湖优先）

### Part 2: Cloud IAM + IDaaS

1. Cloud IAM 支持 SSO 与 SDK 访问 ES
2. 通过 IDaaS（`../ldap-demo`）把 LDAP 账户打通到阿里云与 ES

---

## Part 1-1: 多分片支持（配置模型）

- `lance_vector.storage` 同时支持两类模式：
- Legacy 单 URI：`storage.uri`
- Shard-aware：`storage.uri_prefix + shard_path + dataset_name`
- `sharding_strategy` 支持：
- `NONE`：不做候选分片过滤
- `ES_ROUTING`：按 ES 路由算法过滤候选
- 默认行为：
- shard-aware 默认 `ES_ROUTING`
- legacy 默认 `NONE`

**价值**：同一套查询 DSL，既能兼容历史单数据集，也能平滑升级到分片级数据集。

---

## Part 1-2: 多分片支持（查询执行）

- 查询阶段会先 `resolveUri(index, shard)` 决定当前分片应访问的数据集 URI。
- 分片过滤逻辑用 ES Murmur3 路由计算候选归属分片。
- 关键正确性修复：
- 用 `Math.floorMod(hash, numRoutingShards)` 代替 `Math.abs(hash) % n`
- 避免 `Integer.MIN_VALUE` 边界下的负分片错误

**结论**：跨分片候选过滤从“可能误丢”变成“路由语义一致”。

---

## Part 1-3: NRT 刷新（自动 + 手动）

- 插件层已提供：
- 自动刷新开关：`lance.refresh.enabled`（默认 true）
- 刷新间隔：`lance.refresh.interval`（默认 30s）
- 在 `createComponents()` 中按开关启动 refresh service
- REST 手动刷新已暴露：`POST /_lance/refresh`
- 统计接口：`GET /_lance/stats`

**运维含义**：可以“定时失效重载 + 人工强制刷新”双路径保障可观测的最终一致。

---

## Part 1-4: NRT 并发安全（避免刷新误伤查询）

- 刷新本质是 `LanceDatasetRegistry.clear()/invalidate()`，会触发 dataset close。
- 当前实现引入查询-刷新读写锁：
- 查询路径：`withSearchLock()`（读锁）
- 刷新路径：`withRefreshLock()`（写锁）
- 效果：
- 刷新会等待在途查询释放读锁
- 避免“查询执行中 dataset 被关闭”的并发故障

---

## Part 1-5: Prefilter（过滤下推）机制

- `field_mapping` 支持 ES 字段到 Lance 列映射。
- `EsToLanceFilterConverter` 当前 v1 主要支持 `TermQuery -> SQL`。
- 查询执行顺序：
- 尝试把 filter 转 SQL
- 可转则 SQL 下推到 Lance (`ScanOptions.filter`)
- 不可转则回退 ES 后过滤（不牺牲正确性）
- SQL 值做单引号转义，降低注入风险。

**设计原则**：`try pushdown -> fallback`，优先正确，再追性能。

---

## Part 1-6: Prefilter 与 nprobes 的语义一致性

- `lance_knn` 的 `nprobes` 已打通到执行层。
- `LanceKnnQueryBuilder` 解析 `nprobes` 后透传到 `LanceKnnQuery`。
- 关键修复：nprobes/pushdown 路径不再强制 `cosine`，而是保持 mapping 配置的 `similarity`（`cosine/dot_product/l2`）。

**收益**：
- 参数调优真实生效
- 避免“同一索引不同查询路径评分语义漂移”

---

## Part 1-7: 高层架构数据流（当前 + 目标）

### 当前已落地（PR #6）

- 以外部 Lance 数据集只读检索为主（`read_only` 强约束）。
- 查询路径：ES -> Lance 检索候选 -> `_id` join -> 返回 ES 命中。

### 目标态（设计文档方向）

- 数据湖（Lance/S3）作为向量主存与 source-of-truth。
- 写入优先进入 Lance（含向量列），再将非向量字段回填/索引到 ES。
- ES 保持过滤、聚合、权限、生态能力。

**说明**：第二部分是“架构目标与路线图”，不是当前分支已全部产品化完成。

---

## Part 1-8: 为什么“数据湖优先 + ES 回填”值得做

- 成本：向量主数据留在对象存储，避免双份高成本存储。
- 规模：数据湖天然更适合超大规模向量资产治理。
- 语义：ES 专注检索编排、权限和非向量检索能力。
- 可演进：后续可逐步增强写入一致性、刷新协议、回填策略。

**一句话**：把“向量存储”与“检索编排”解耦，才能兼顾规模、成本和功能。

---

## Part 2-1: Cloud IAM 对 ES 的能力总览

- Realm 类型：`cloud_iam`
- 两条认证入口：
- STS 签名头：`X-ES-IAM-Signed`
- OAuth Bearer：`Authorization: Bearer <token>`
- 角色映射基于 `UserRoleMapper`，可按 ARN/账号/主体类型映射 ES 角色。
- 元数据透出 `cloud_arn/cloud_account/cloud_principal_type`，便于统一授权策略。

---

## Part 2-2: SDK 访问 ES（RAM 用户/角色）

- SDK 使用 RAM AK/SK（或 STS 临时凭证）生成签名参数。
- ES 侧 `AliyunStsClient` 调用 `GetCallerIdentity` 验证身份。
- 解析 ARN -> PrincipalType（`USER/ROLE/ASSUMED_ROLE`）-> 角色映射 -> 发放 ES User。

**特点**：
- 适合服务间访问、自动化任务、脚本与平台 SDK。
- 与阿里云 STS 语义对齐，角色会话可控。

---

## Part 2-3: SSO 访问 ES（经 IDaaS）

推荐链路（企业统一身份）：

1. LDAP 用户在 IDaaS 登录（SAML Role SSO）
2. IDaaS 将用户映射到 RAM 角色
3. 用户/网关拿到该角色临时凭证
4. 使用签名请求访问 ES（`X-ES-IAM-Signed`）
5. Cloud IAM Realm 完成鉴权与 ES 角色映射

**结果**：同一身份源（LDAP）可同时访问阿里云资源与 ES。

---

## Part 2-4: `../ldap-demo` 的关键价值

- 解决“LDAP 在隔离内网、IDaaS 无法入站直连”的现实问题。
- 通过 `idaas-ldap-connector` 实现：
- Agent 主动读取 LDAP
- 通过出站 HTTPS（SCIM 2.0）同步到 IDaaS
- 支持定时同步、属性映射、用户/组同步

**架构优势**：只需内网到公网出站能力，不要求 IDaaS 对内网发起连接。

---

## Part 2-5: 单一 RAM 角色映射模型

- 在 IDaaS 应用账户中，为 LDAP 用户配置统一的 RAM 角色标识：
- `role-arn,provider-arn`
- 示例模式：多个 LDAP 用户 -> 同一个 RAM 角色
- 这样可实现：
- 控制台 SSO 统一权限面
- SDK 访问策略一致
- ES 侧继续用 Cloud IAM realm 做精细角色映射（按 ARN 或 metadata）

**组织收益**：身份收敛、权限模型收敛、审计路径收敛。

---

## Part 2-6: 现实限制与演进建议

### 已识别限制

- Cloud IAM OAuth 路径对 assumed-role 识别仍弱于 STS 路径（文档已标注）。
- `../ldap-demo` 文档存在 OIDC 与 SAML 并存历史，当前应以 SAML Role SSO 为主线。

### 建议

- 生产上优先 STS 签名路径承载 ES SDK 访问。
- 将 OIDC 路径定位为补充能力，补齐 assumed-role parity 后再主推。
- 统一文档口径，避免实施团队混用 OIDC 与 SAML 配置模板。

---

## 落地演示建议（可直接 Tech Share Live Demo）

1. 演示 shard-aware 索引配置与 `sharding_strategy` 切换。
2. 演示 `lance_knn` 的 `nprobes` 与 term filter 下推。
3. 调用 `POST /_lance/refresh` + `GET /_lance/stats` 观察刷新与缓存行为。
4. 演示 Cloud IAM STS 签名访问 `/_security/_authenticate`。
5. 演示 IDaaS 应用账户把 LDAP 用户映射到统一 RAM 角色后的登录链路。

---

## 证据索引（建议讲解时备用）

### Lance Vector（主仓库）

- 多分片/路由：`plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java`
- 存储配置：`plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceStorageConfig.java`
- refresh 启停与 REST 注册：`plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/LanceVectorPlugin.java`
- refresh 服务：`plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/LanceRefreshService.java`
- 并发锁：`plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/LanceDatasetRegistry.java`
- filter 转换：`plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/EsToLanceFilterConverter.java`

### Cloud IAM（主仓库）

- Realm 主逻辑：`plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/CloudIamRealm.java`
- STS 校验：`plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/AliyunStsClient.java`
- OAuth 校验：`plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/OAuthTokenValidator.java`
- 限制说明：`plugins/security-realm-cloud-iam/LIMITATION.md`

### IDaaS/LDAP（`../ldap-demo`）

- 总体说明：`../ldap-demo/README.md`
- SAML 架构说明：`../ldap-demo/docs/2026-02-07-SAML_架构说明.md`
- SAML 配置指南：`../ldap-demo/docs/IDAAS_SAML_RoleSSO_设置指南.md`
- 连接器实现：`../ldap-demo/idaas-ldap-connector/README.md`
- 连接器主程序：`../ldap-demo/idaas-ldap-connector/agent.py`
- E2E 校验：`../ldap-demo/validate-sso-e2e.py`

