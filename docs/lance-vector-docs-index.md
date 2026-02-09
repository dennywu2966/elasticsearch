# Lance Vector Plugin 文档目录

## 概述

Lance Vector Plugin 是 Elasticsearch 9.2.4 的向量字段类型插件，支持使用 Lance 格式存储和检索高维向量数据。

**版本**: 9.2.4
**状态**: 生产就绪 (P1-P2-P3 已合并完成)

---

## 文档导航

### 核心文档

| 文档 | 描述 | 目标读者 |
|------|------|---------|
| **[架构设计](./lance-vector-架构设计.md)** | 系统架构、核心组件设计、数据流设计 | 架构师、开发工程师 |
| **[用户指南](./lance-vector-用户指南.md)** | 安装配置、使用方法、API 参考、故障排除 | 系统管理员、开发工程师 |

### 项目文档

| 文档 | 描述 |
|------|------|
| **[回归验证指南](../reg_validation_guide.md)** | 完整的回归测试套件（103 个测试用例） |
| **[生产验证报告](../PRODUCTION-VALIDATION-REPORT.md)** | 生产环境验证结果 |
| **[实施计划](../P1-P2-IMPLEMENTATION-PLAN.md)** | P1-P2-P3 实施计划和设计决策 |

---

## 快速开始

### 1. 启动 Elasticsearch

```bash
# 使用启动脚本（推荐，4GB JVM）
./project_starter.sh -d
```

### 2. 创建索引

```bash
curl -k -u elastic:Summer11 -X PUT "https://localhost:9200/my-index" \
  -H 'Content-Type: application/json' -d '{
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
}'
```

### 3. 执行搜索

```bash
curl -k -u elastic:Summer11 -X POST "https://localhost:9200/my-index/_search" \
  -H 'Content-Type: application/json' -d '{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, 0.3, ...],
    "k": 10,
    "num_candidates": 100
  }
}'
```

---

## 功能特性

### P1: 查询语义与性能
- **过滤器支持**: Pre-filter / Post-filter / AUTO 策略
- **nprobes 控制**: 精度与性能权衡
- **混合搜索**: RRF (Reciprocal Rank Fusion)

### P2: NRT 刷新
- **VersionedDataset**: 原子数据集切换
- **集群设置**: `lance.refresh.enabled`, `lance.refresh.interval`
- **刷新服务**: 后台轮询检测数据集更新
- **统计指标**: `/_lance/stats` 端点

### P3: 分片感知存储
- **URI 模板**: `{index}`, `{shard_id}` 占位符
- **分片级别解析**: 每个分片读取自己的 Lance 数据集
- **向后兼容**: 传统单 URI 模式继续工作

---

## 技术栈

| 组件 | 技术 |
|------|------|
| **向量存储** | Lance (lancedb/lance-core:1.0.0-beta.2) |
| **列式存储** | Apache Arrow 15.0.0 |
| **向量索引** | IVF-PQ (Inverted File + Product Quantization) |
| **存储后端** | 本地文件系统、阿里云 OSS、AWS S3 |

---

## 性能基准

基于 300 向量、128 维度、IVF-PQ 索引：

| 操作 | 延迟 | 说明 |
|------|------|------|
| 数据集创建 | 5-10s | IVF-PQ 索引构建 |
| 首次搜索（本地） | 1-3s | 数据集加载 |
| 首次搜索（OSS） | 2-10s | 从 OSS 加载 |
| 后续搜索 | 20-100ms | 数据集已缓存 |
| 内存开销 | ~256MB | Arrow 分配器限制 |

---

## 测试验证

### 单元测试
- **总测试数**: 278
- **通过**: 275
- **失败**: 0
- **忽略**: 3 (OSS 集成测试需要真实 OSS 环境)
- **成功率**: 100%

### 回归测试
- **总测试数**: 103
- **P0 测试**: 27 (关键功能，每次提交都运行)
- **P1 测试**: 45 (高优先级，每天运行)
- **P2 测试**: 31 (中优先级，每周运行)

### 集成测试
- **外部挂载测试**: 8/8 通过
- **过滤器测试**: 全部通过
- **分片感知测试**: 全部通过
- **NRT 刷新测试**: 全部通过

---

## 常见问题

### 所有结果分数为 0

**原因**: 距离到分数的转换公式缺失

**解决方案**: 确认使用最新版本插件，验证相似度算法配置

### OSS 认证失败

**原因**: OSS 环境变量未在 ES 启动前设置

**解决方案**:
```bash
export OSS_ACCESS_KEY_ID=...
export OSS_ACCESS_KEY_SECRET=...
export OSS_ENDPOINT=...
./bin/elasticsearch -d -p elasticsearch.pid
```

### 首次搜索很慢

**原因**: Lance 需要加载数据集到内存

**预期延迟**:
- 本地存储: 1-3 秒
- OSS 存储: 2-10 秒
- 后续搜索: 20-100ms

---

## 相关链接

- **Elasticsearch 官方文档**: https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html
- **Lance 文档**: https://lancedb.github.io/lance/
- **Apache Arrow 文档**: https://arrow.apache.org/

---

## 支持与反馈

- **问题报告**: 通过项目 Issue 跟踪器
- **功能请求**: 通过项目 Discussions
- **文档改进**: 欢迎提交 Pull Request

---

**文档维护**: Lance Vector Plugin Team
**最后更新**: 2026-02-07
**文档版本**: 1.0.0
