# Findings & Decisions
<!-- 
  WHAT: Your knowledge base for the task. Stores everything you discover and decide.
  WHY: Context windows are limited. This file is your "external memory" - persistent and unlimited.
  WHEN: Update after ANY discovery, especially after 2 view/browser/search operations (2-Action Rule).
-->

## Requirements
<!-- 
  WHAT: What the user asked for, broken down into specific requirements.
  WHY: Keeps requirements visible so you don't forget what you're building.
  WHEN: Fill this in during Phase 1 (Requirements & Discovery).
  EXAMPLE:
    - Command-line interface
    - Add tasks
    - List all tasks
    - Delete tasks
    - Python implementation
-->
<!-- Captured from user request -->
- 深读仓库，提炼技术分享事实材料，覆盖 5 个主题（Lance 多分片、NRT 刷新、prefilter/filter pushdown、数据流、Cloud IAM）
- 输出中文要点，至少 20 条，每条给出文件路径与行号证据
- 标注“已证实事实”与“推断/建议”
- 给出 2 张可上 slides 的 ASCII 架构图草稿

## Research Findings
<!-- 
  WHAT: Key discoveries from web searches, documentation reading, or exploration.
  WHY: Multimodal content (images, browser results) doesn't persist. Write it down immediately.
  WHEN: After EVERY 2 view/browser/search operations, update this section (2-Action Rule).
  EXAMPLE:
    - Python's argparse module supports subcommands for clean CLI design
    - JSON module handles file persistence easily
    - Standard pattern: python script.py <command> [args]
-->
<!-- Key discoveries during exploration -->
- docs/lance-vector-docs-index.md 提示有“用户指南（PR6 最新版）”并覆盖 pushdown/sharding/refresh 等主题，可作为核心证据入口
- docs/lance-vector-docs-index.md 列出 P2: NRT 刷新并标出设置项名称（lance.refresh.enabled、lance.refresh.interval）
- docs/lance-vector-用户指南-PR6.md 与 docs/lance-vector-机制讲解与技术分享-PR6.md 包含 field_mapping、pushdown 行为说明与技术分享线索
- plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/EsToLanceFilterConverter.java 与 LanceKnnQuery.java 明确过滤下推逻辑与失败回退
- plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/RealLanceDataset.java 提到 SDK pre-filter 尚未实现（有 TODO）
- plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceFilterPushdownIntegrationTests.java 与 EsToLanceFilterConverterTests.java 提供过滤下推测试覆盖
- docs/lance-vector-用户指南-PR6.md/机制讲解-PR6.md 详述 shard-aware 配置字段（uri_prefix/shard_path/dataset_name/sharding_strategy）
- plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceStorageConfig.java 与 LanceVectorFieldMapper.java 含分片策略解析与校验逻辑
- Lance 刷新相关代码集中在 plugins/lance-vector/src/main/java/.../LanceRefreshService.java、RestLanceRefreshAction.java、LanceVectorPlugin.java
- docs/cloud-iam-e2e.sh 提供 Cloud IAM 验证脚本，包含 RAM AK/SK 与 X-ES-IAM-Signed 头部的调用示例
- plugins/lance-vector/docs/分片感知数据集映射指南.md 说明单数据集与分片感知两种模式，以及 shard-aware 路由一致性要求
- Cloud IAM 代码位于 plugins/security-realm-cloud-iam，包含 CloudIamRealmPlugin、OAuthTokenValidator、CloudIamRealmSettings 等
- docs/cloud-iam-e2e.sh 提供 realm 配置与 X-ES-IAM-Signed 头鉴权的 E2E 测试脚本
- LanceVectorFieldMapper 明确 uri_prefix/shard_path/dataset_name 的解析优先级与 field_mapping 解析校验
- LanceStorageConfig 文档化 ES_ROUTING 使用 Murmur3 哈希的分片候选过滤策略与 NONE 模式
- EsToLanceFilterConverter 明确 v1 仅支持 TermQuery，失败触发回退，且做 SQL 转义
- LanceKnnQuery.tryConvertFilterToSql() 基于 field_mapping 尝试 pushdown，失败回退到 ES 后过滤
- LanceRefreshService 使用 ScheduledExecutorService 周期刷新，refreshAll 清空缓存并重新调度
- LanceDatasetRegistry 使用读写锁保护 refresh 与查询并发、Cache+LRU+TTL，避免重复加载
- RestLanceRefreshAction 暴露 POST /_lance/refresh 并调用 refreshService.refreshAll()
- LanceVectorPlugin 定义 lance.refresh.enabled / lance.refresh.interval，createComponents 中按开关启动刷新服务
- LanceKnnQueryTests 包含“刷新期间查询不被关闭”的并发测试场景
- LanceRefreshServiceTests 覆盖启动/停止、refreshAll 清缓存、刷新间隔设置
- ShardAwareIntegrationTests 覆盖 shard-aware URI 解析、index/shard 占位符与 legacy 兼容行为
- LanceStorageConfigTests 验证默认分片策略（legacy=NONE，shard-aware=ES_ROUTING）
- LanceStorageConfig.resolveUri 处理 uri_prefix/shard_path/dataset_name 解析与错误校验（.lance 结尾冲突）
- LanceFilterPushdownIntegrationTests 覆盖 TermQuery 转 SQL、字段未映射回退、SQL 注入转义等
- EsToLanceFilterConverterTests 提供转换器单元测试矩阵（类型处理、转义、映射缺失）
- docs/lance-vector-架构设计.md 与 分片感知数据集映射指南.md 指出 Phase 1 只读，不支持写入
- LanceVectorFieldMapper 对 read_only 进行校验并强制 Phase 1 只读
- highlevel-design.md 多处提到 data lake / S3 Lance 作为源数据，并描述 External Mount/Phase 1 的定位
- CloudIamRealmPlugin 注册默认鉴权头 X-ES-IAM-Auth / X-ES-IAM-Signed
- CloudIamRealmSettings 提供 auth.mode、allow_assumed_role、role_mapping、IAM endpoint/region、缓存与重放保护配置
- CloudIamToken 支持 STS 签名头与 OAuth Bearer，OAuth 优先
- CloudIamRealm 从 signed header 或 Authorization 创建 token，按 OAuth/STS 路由不同 client，并支持 nonce 重放保护与 role mapping
- OAuthTokenValidator 调用 Aliyun OAuth userinfo，并构造 RAM user/role ARN
- AliyunStsClient 校验 STS GetCallerIdentity 请求参数、构造请求并解析 ARN/AccountId/UserId
- tools/aliyun_sts_sign.py 生成 X-ES-IAM-Signed 头（RAM AK/SK + STS 参数签名）
- Cloud IAM README 说明支持 RAM 用户/STS 角色、提供签名头认证示例与角色映射
- Cloud IAM Validation Guide 说明 ARN 结构与 principal type（user/role/assumed_role）映射策略
- LIMITATION.md 明确两条认证路径：STS Signature 与 OAuth Bearer（Authorization 头），并列出差异
- LIMITATION.md 说明 assumed role 仅 STS 路径支持，OAuth 路径不支持 assumed-role 类型
- CloudIamSecurityExtension 根据 auth.mode 选择 STS/OAuth 或 mock 客户端
- docs/lance-vector-架构设计.md 包含架构图与分层说明，并描述 filter 评估、刷新机制、数据集加载流程
- docs/lance-vector-机制讲解与技术分享-PR6.md 明确 pushdown/分片策略/刷新入口与回退策略、并发安全说明
- 分片感知映射指南与 SHARD-MAPPING.md 说明 legacy vs shard-aware、URI 解析模板、路由一致性不变量与只读限制
- highlevel-design.md 描述 S3 data lake 为 source of truth，并给出 query/index/external mount 流程示意（设计文档）
- docs/lance-vector-用户指南-PR6.md 记录 storage.*、field_mapping 格式、sharding_strategy 默认、read_only 与 NRT 设置/刷新流程
- docs/lance-vector-用户指南-PR6.md 说明下推生效条件与失败回退、并发安全读写锁说明
- PreFilterHeuristic 定义预/后过滤策略与 index.lance_vector.prefilter_heuristic 设置
- LanceKnnQuery 通过提取过滤后的 _id 列表（stored fields）支持预过滤路径
- LanceKnnQuery.filterCandidatesByShard 使用 Murmur3HashFunction + routingHashToShardId 按 ES 路由过滤候选
- LanceDatasetRegistry.withSearchLock/withRefreshLock 使用读写锁保障刷新与查询并发安全

## Technical Decisions
<!-- 
  WHAT: Architecture and implementation choices you've made, with reasoning.
  WHY: You'll forget why you chose a technology or approach. This table preserves that knowledge.
  WHEN: Update whenever you make a significant technical choice.
  EXAMPLE:
    | Use JSON for storage | Simple, human-readable, built-in Python support |
    | argparse with subcommands | Clean CLI: python todo.py add "task" |
-->
<!-- Decisions made with rationale -->
| Decision | Rationale |
|----------|-----------|
|          |           |

## Issues Encountered
<!-- 
  WHAT: Problems you ran into and how you solved them.
  WHY: Similar to errors in task_plan.md, but focused on broader issues (not just code errors).
  WHEN: Document when you encounter blockers or unexpected challenges.
  EXAMPLE:
    | Empty file causes JSONDecodeError | Added explicit empty file check before json.load() |
-->
<!-- Errors and how they were resolved -->
| Issue | Resolution |
|-------|------------|
|       |            |

## Resources
<!-- 
  WHAT: URLs, file paths, API references, documentation links you've found useful.
  WHY: Easy reference for later. Don't lose important links in context.
  WHEN: Add as you discover useful resources.
  EXAMPLE:
    - Python argparse docs: https://docs.python.org/3/library/argparse.html
    - Project structure: src/main.py, src/utils.py
-->
<!-- URLs, file paths, API references -->
-

---

## Session 2026-02-10 Findings (Opus Review Actions)

### Requirements (Current Session)
- Implement all action-plan items in `docs/opus_review_feedback_0209_actions.md` in severity order.
- Use test-first workflow per behavior change; avoid non-meaningful tests.
- Ensure no regressions across `plugins/lance-vector` and `plugins/security-realm-cloud-iam`.

### Verified Baseline Facts
- `plugins/lance-vector/build.gradle` pins `jackson-databind:2.17.2` and Arrow `15.0.0`.
- `x-pack/plugin/esql/arrow/build.gradle` uses Arrow `18.3.0` and `${versions.jackson}`.
- `LanceDatasetRegistry.getOrLoad()` currently falls back to `FakeLanceDataset` for non-Lance URIs.
- `RealLanceDataset.extractCandidates()` logs missing distance column but continues with fallback `0f`.
- `RealLanceDataset.search(..., VarCharVector idFilter)` warns and returns unfiltered search.
- `LanceDatasetRegistry` currently uses `synchronized(uri.intern())` + `Thread.sleep(10)` spin-wait.
- `LanceDatasetRegistry.invalidate()` closes directly and then invalidates cache (risking double close via removal listener).
- Security realm/token currently include `System.err.println()` diagnostics.
- `EsToLanceFilterConverter` does not validate mapped column identifiers and accepts non-finite numerics.
- `OssStorageAdapter.loadCredentials()` opens InputStream without try-with-resources.

### Design Direction Chosen
- Fail fast over silent degradation in production paths.
- Keep behavior explicit when features are unsupported (throw with clear message).
- Prefer minimal, targeted changes with broad regression coverage via existing plugin test suites.

## Visual/Browser Findings
<!-- 
  WHAT: Information you learned from viewing images, PDFs, or browser results.
  WHY: CRITICAL - Visual/multimodal content doesn't persist in context. Must be captured as text.
  WHEN: IMMEDIATELY after viewing images or browser results. Don't wait!
  EXAMPLE:
    - Screenshot shows login form has email and password fields
    - Browser shows API returns JSON with "status" and "data" keys
-->
<!-- CRITICAL: Update after every 2 view/browser operations -->
<!-- Multimodal content must be captured as text immediately -->
-

---
<!-- 
  REMINDER: The 2-Action Rule
  After every 2 view/browser/search operations, you MUST update this file.
  This prevents visual information from being lost when context resets.
-->
*Update this file after every 2 view/browser/search operations*
*This prevents visual information from being lost*
