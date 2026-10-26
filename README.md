# hybrid-rag-spring-ai

教学版最小可复现工程：**语义向量检索 + 全文检索 + RRF（倒数排名融合）+ 可选重排**，演示混合检索的链路结构、融合公式与工程取舍。

> 本工程为公开学习样例，代码与样例语料均为独立编写的合成内容；不包含任何公司内网系统、项目代号、人员账号或业务数据。

---

## 1. 这个工程演示什么

RAG 检索阶段最常见的两类失效，单路检索都救不了：

| 查询类型 | 典型例子 | 向量路表现 | 全文路表现 |
| --- | --- | --- | --- |
| 专有编号 / 精确术语 | `EQ-2041`、`§3.2`、型号代号 | 语义近似但命不中精确编号 | 词形匹配直接命中 |
| 同义 / 复述 / 语义改写 | 问"开关误动作"，原文写"跳闸" | 语义相近可召回 | 词面不重合容易漏 |

两路并行召回、各自给出排名，再用 **RRF** 把"名次"融合成一个可比分数——它只看排名、不要求两路原始分数量纲一致，避免向量相似度与 BM25 分数直接加权时的量纲问题。

## 2. 技术栈

- JDK 17、Spring Boot 3.3.5
- H2 内存库（教学版零外部服务即可跑通）
- Caffeine（查询结果缓存，可开关）
- JUnit 5 + Spring Boot Test（单元测试 + 集成测试）

## 3. 目录结构

```
hybrid-rag-spring-ai
├── pom.xml
├── README.md
├── LICENSE
├── src/main/java/com/example/hybridrag
│   ├── HybridRagApplication.java
│   ├── config          # 配置属性绑定、启动样例入库
│   ├── ingest          # 分块、文档入库编排
│   ├── model           # 嵌入 / 重排接口与本地默认实现
│   ├── retrieval      # 向量路、全文路、RRF、混合编排
│   │   └── dto         # 融合候选结果对象
│   └── web             # REST 接口
├── src/main/resources
│   ├── application.yml
│   ├── db/schema.sql              # H2 教学版 schema
│   ├── db/postgres-pgvector.sql   # 生产 PostgreSQL/pgvector 草案（对照用）
│   └── samples/                   # 合成样例语料（虚构）
└── src/test/java/...              # 分块 / RRF / 编排测试
```

## 4. 快速开始

要求：JDK 17、Maven 3.6+。

```bash
# 跑全部测试
mvn test

# 启动服务（默认 8080）
mvn spring-boot:run
```

启动时自动加载 `samples/` 三份合成语料。接口示例：

```bash
# 健康检查（返回已入库片段数）
curl http://localhost:8080/api/health

# 手动重新入库样例
curl http://localhost:8080/api/ingest

# 混合检索
curl -X POST http://localhost:8080/api/search ^
  -H "Content-Type: application/json" ^
  -d "{\"query\":\"EQ-2041 进线断路器\",\"topK\":3}"
```

返回结构：`query`、`count`、`results[]`（`rank`、`content`、`source`、`chunkIndex`、`rrfScore`、`rerankScore`）。

## 5. 检索链路

```
文档 ──► 结构感知分块 ──► 本地嵌入 ──► 写入 documents / chunks
查询 ──►┬─ 向量路：余弦相似度排序
        ├─ 全文路：简化 BM25 排序（无命中降级子串包含）
        └─ 两路 CompletableFuture 并行；任一路异常降级为单路，不整体失败
              ──► RRF 融合（标准 / 加权，去重保序）
                    ──► 重排（默认直通；开启时先按 topK × 候选倍数扩大候选）
                          ──► Caffeine 缓存（可关，可配 TTL）
```

## 6. RRF 融合规则

- 标准 RRF：`score(d) = Σ_i 1 / (K + rank_i(d))`，常数 `K = 60`。
- 加权 RRF：`score(d) = Σ_i w_i / (K + rank_i(d))`，默认向量 `0.7`、全文 `0.3`。
- **权重前提**：仅当两路候选质量稳定、权重有线下评测依据时才固定加权；路数与权重数量不一致时自动回退等权，避免配置错位。
- **K = 60** 是 RRF 经验常数：压低头部名次差、避免第 1 名与第 2 名分数悬殊；调整 K 会改变头部区分度，属于需要回归评测的参数。
- **重排位置**：重排在融合之后、取最终 topK 之前；开启重排时先按 `topK × candidateMultiplier` 扩大候选，避免融合阶段把相关片段提前裁死。

## 7. 教学版 ↔ 生产形态对照

| 环节 | 教学版（本工程，零外部依赖） | 生产替换点（见 `db/postgres-pgvector.sql`） |
| --- | --- | --- |
| 存储 | H2 内存库，向量以 JSON 存 CLOB | PostgreSQL 16 + pgvector，`vector(1024)` 列 |
| 向量路 | 全表扫描 + 内存余弦排序 | HNSW 索引（`vector_cosine_ops`），`ef_search` 按时延调优 |
| 全文路 | 内存倒排 + 简化 BM25 | `tsvector` 生成列 + GIN 索引；中文接分词器 |
| 嵌入 | 本地哈希桶向量（确定性、可离线复现，仅演示链路） | 真实嵌入模型 / OpenAI 兼容端点，实现 `EmbeddingClient` 即可替换 |
| 重排 | `PassThroughRerankClient` 直通 | 外部重排模型端点，实现 `RerankClient` 并开启 |
| 缓存 | Caffeine，可开关、可配 TTL | 生产可叠加分布式缓存与旁路缓存 |

替换步骤：保持 `EmbeddingClient` / `RerankClient` 接口不变，替换实现；把召回 SQL 切到 `postgres-pgvector.sql` 草案；融合层与编排层无需改动。

## 8. 参数配置（`application.yml`）

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `app.embedding.dimension` | 1024 | 向量维度，嵌入实现与表结构需对齐 |
| `app.chunk.max-chars` | 1000 | 分块字符上限 |
| `app.chunk.overlap-chars` | 150 | 相邻窗口重叠字符数 |
| `app.retrieval.rrf-k` | 60 | RRF 常数 K |
| `app.retrieval.vector-weight` | 0.7 | 向量路权重 |
| `app.retrieval.text-weight` | 0.3 | 全文路权重 |
| `app.retrieval.candidate-multiplier` | 3 | 重排开启时的候选扩大倍数 |
| `app.retrieval.cache-enabled` | true | 是否开启结果缓存 |
| `app.retrieval.cache-ttl-seconds` | 300 | 缓存 TTL（秒） |
| `app.retrieval.thread-pool-size` | 4 | 两路并行线程池大小 |

## 9. 边界声明（请勿超出）

- 本地哈希嵌入只演示接口与链路，**不代表真实语义质量**；不要用本工程的命中率去论证生产模型效果。
- 工程内**没有**任何召回率、准确率、时延、QPS 的效果榜单；参数均为教学默认值，生产取值必须基于真实语料与评测集回归。
- `samples/` 三份语料为虚构合成内容，不对应任何真实行业台账、制度或站点。
- 本工程不涉及模型网关、鉴权、计费、多租户路由等能力；这些不在 RAG 检索阶段范围内。

## 10. 测试覆盖

- `ChunkingServiceTest`：标题分段、窗口上限、空正文兜底。
- `RankFusionServiceTest`：多路累加、topK 截断去重、权重数量不符回退等权、加权排序倾向。
- `HybridRetrievalServiceTest`：Spring 上下文 + H2 样例入库，专有编号命中、排名连续、异常输入结构合法。
