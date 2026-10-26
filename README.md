# hybrid-rag-spring-ai

**语义向量检索 + 全文检索两路并行召回，RRF（倒数排名融合）融合，可选重排** 的教学版最小可复现工程：演示混合检索的链路结构、融合公式与工程取舍。Spring Boot 3.3.5 + JDK 17，H2 内存库，零外部服务即可跑通。

> 本工程为公开学习样例，代码与样例语料均为独立编写的合成内容；不包含任何公司内网系统、项目代号、人员账号或业务数据。`samples/` 三份语料为虚构合成，不对应任何真实行业台账、制度或站点。

---

## 1. 这个工程解决什么问题

做 RAG 知识库时，查询大致分成两类，单路检索各自都有救不了的失效场景：

| 查询类型 | 典型例子 | 向量路表现 | 全文路表现 |
| --- | --- | --- | --- |
| 专有编号 / 精确术语 | `EQ-2041`、`§3.2`、型号代号 | 语义近似但命不中精确编号 | 词形匹配直接命中 |
| 同义 / 复述 / 语义改写 | 问"开关误动作"，原文写"跳闸" | 语义相近可召回 | 词面不重合容易漏 |

- **纯向量**：抓不住精确编号与条款号；同义改写能召回，专有词容易被语义近似项稀释。
- **纯全文**：读不懂同义词和复述；缺一个关键词、分词切错，就可能整条漏召回。

两路并行召回、各自给出排名，再用 **RRF** 把"名次"融合成一个可比分数——它只看排名、不要求两路原始分数量纲一致，避免向量相似度与 BM25 分数直接加权时的量纲问题。两类查询互相补盲，正是混合检索要解决的问题。

## 2. 快速开始

要求：JDK 17、Maven 3.6+。

```bash
# 跑全部测试
mvn test

# 启动服务（默认 8080），启动时自动加载 samples/ 三份合成语料
mvn spring-boot:run
```

接口示例：

```bash
# 健康检查（返回已入库片段数）
curl http://localhost:8080/api/health

# 手动重新入库样例
curl http://localhost:8080/api/ingest

# 混合检索
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"EQ-2041 进线断路器 更换周期","topK":3}'
```

返回结构：`query`、`count`、`results[]`，每条结果含 `rank`（从 1 起）、`content`、`source`、`chunkIndex`、`rrfScore`、`rerankScore`（直通重排时为 `null`）。

## 3. 检索链路与代码对照

```
文档 ──► 结构感知分块 ──► 本地嵌入 ──► 写入 documents / chunks
查询 ──►┬─ 向量路：余弦相似度排序
        ├─ 全文路：简化 BM25 排序（无命中降级子串包含）
        └─ 两路 CompletableFuture 并行；任一路异常降级为单路，不整体失败
              ──► RRF 融合（标准 / 加权，去重保序）
                    ──► 重排（默认直通；开启时先按 topK × 候选倍数扩大候选）
                          ──► Caffeine 缓存（可关，可配 TTL）
```

每一步对应仓库里的类，方便对照代码：

| 链路环节 | 仓库实现 |
| --- | --- |
| 分块 | `ingest/ChunkingService.java` |
| 嵌入 | `model/EmbeddingClient.java` + `model/HashEmbeddingClient.java`（本地确定性默认实现，可替换真实模型） |
| 向量路 | `retrieval/VectorSearchService.java` |
| 全文路 | `retrieval/FullTextSearchService.java` |
| RRF 融合 | `retrieval/RankFusionService.java` |
| 并行编排 / 降级 / 缓存 | `retrieval/HybridRetrievalService.java` |
| 重排 | `model/RerankClient.java` + `model/PassThroughRerankClient.java`（默认直通、关闭） |
| 接口 | `web/RetrievalController.java` |

## 4. 两路召回怎么做

### 4.1 向量路：语义召回

嵌入模型做成可替换接口：默认本地哈希桶实现只用于演示链路（确定性、可离线复现，**不代表真实语义质量**）；生产替换为真实嵌入模型或 OpenAI 兼容端点时，只换实现，检索与融合层不动。

向量路用余弦相似度衡量语义相近度。教学版为零外部依赖，采用全表扫描 + 内存排序；生产形态在 pgvector 上用 HNSW 索引（`vector_cosine_ops`），`m`、`ef_search` 是需要按语料规模与时延压测调优的参数——这里只讲配置位置，不声称某套索引在现场已经全部生效。

分块策略上，**结构边界优先于固定字符切分**：先按标题行、空行段落切段，再按字符窗口滑动并保留重叠，避免一条完整语义被窗口硬切断。窗口大小与重叠都走配置，不照搬任何固定阈值。

```java
// ChunkingService 节选：标题 / 空行先分段，再按窗口滑动并保留重叠
private List<String> slideWindows(String text) {
    List<String> windows = new ArrayList<>();
    int start = 0;
    int length = text.length();
    while (start < length) {
        int end = Math.min(start + maxChars, length);
        windows.add(text.substring(start, end).trim());
        if (end == length) {
            break;
        }
        start = Math.max(0, end - overlapChars);
    }
    return windows;
}
```

教学版默认 `max-chars=1000`、`overlap-chars=150`，均可在 `application.yml` 调整。

### 4.2 全文路：全文索引 + 降级模糊匹配

教学版在应用层实现内存倒排 + 简化 BM25；生产形态优先考虑 PostgreSQL `tsvector` 生成列 + GIN 索引，而不是一上来引入 ES：单库、部署简单、私有化交付友好。代价是中文分词能力偏弱，这一点如实写进第 7 节的生产替换清单。

- 全文路先按词元打分召回；**无结果时降级到子串包含匹配**，避免一路空返回直接拖垮整体结果；
- 短子串枚举匹配噪声大，默认不开启；
- 中文分词在生产环境替换为专用分词器或外部检索方案，教学版不把简化实现描述成生产方案。

## 5. 为什么用 RRF，而不是把两路分数加权求和

这是混合检索里最容易被问住的取舍点。

### 两路分数量纲不可比

向量路是余弦相似度，全文路是 `ts_rank` / BM25 类分数，两者分布区间、含义、离散程度都不同。直接把两路原始分数线性加权，需要反复标定权重；换一批语料、换一个嵌入模型，标定值就失效。

### RRF 只用排名

RRF（Reciprocal Rank Fusion，倒数排名融合）只使用每路给出的**名次**，不要求原始分数可比：

```
标准 RRF：score(d) = Σ_i  1 / (K + rank_i(d))      K = 60
```

同一文档在多路命中，分数累加；排名越靠前贡献越大。因为不依赖原始分数，换语料、换模型时融合层不需要重新标定量纲。

```java
// RankFusionService 节选：标准与加权共用一套累加逻辑
boolean weighted = weights != null && weights.size() == resultLists.size();
Map<String, Double> scores = new HashMap<>();
for (int i = 0; i < resultLists.size(); i++) {
    List<String> results = resultLists.get(i);
    double weight = weighted ? weights.get(i) : 1.0;
    for (int rank = 0; rank < results.size(); rank++) {
        scores.merge(results.get(rank), weight / (k + rank + 1), Double::sum);
    }
}
```

### 加权变体与回退

需要偏向某一路时，用加权 RRF：

```
加权 RRF：score(d) = Σ_i  w_i / (K + rank_i(d))
```

权重作用在**排名贡献**上，不是对相似度分数做线性缩放。工程默认向量 `0.7`、全文 `0.3`，**仅作示例默认值**，按知识库构成调整：专有词、编号占比高的语料可上调全文路权重；通用问答库以向量路为主。

**健壮性规则**：当路数与权重数量不一致时，自动回退等权，避免配置错位导致某一路被静默置零。

### K = 60 是什么意思

K 是 RRF 的经验常数。它抑制头部名次的垄断：第 1 名与第 2 名的分数差被分母 `K + rank` 压平，给中排名文档留出空间。调大 K，各路贡献更平均、排序更平；调小 K，头部名次的话语权更重。K 属于需要回归评测的参数，不是越大越好。

## 6. 工程细节

### 并行召回与异常降级

两路用线程池并行召回；任一路抛异常时降级为单路结果，不整体失败——检索服务不能因为全文路故障就把整个查询拖垮。

```java
// HybridRetrievalService 节选：两路并行，异常返回空列表由融合层兜底
CompletableFuture<List<String>> vectorFuture = CompletableFuture.supplyAsync(
        () -> safeCall(() -> vectorSearch.search(query, candidateK)), pool);
CompletableFuture<List<String>> textFuture = CompletableFuture.supplyAsync(
        () -> safeCall(() -> fullTextSearch.search(query, candidateK)), pool);
```

### 候选扩大与重排位置

重排开启时，先按 `topK × candidateMultiplier` 扩大候选倍数，融合阶段不把候选裁死，给重排留出二次筛选空间。**重排放在融合之后、取最终 topK 之前**：它是融合结果的二次筛选，不替代召回。重排接口可替换为外部模型端点，默认直通实现按 RRF 顺序返回、`isEnabled()` 返回 `false`。

### 缓存与删除一致性

- 查询结果用 Caffeine 缓存，可开关、可配 TTL；这里只演示缓存 key 构成与失效机制，不声称命中率；
- 文档删除时，`chunks` 记录由外键 `ON DELETE CASCADE` 级联清理；生产形态向量与全文索引同步回收，演示的是这套一致性思路。

## 7. 教学版 ↔ 生产形态对照

| 环节 | 教学版（本工程，零外部依赖） | 生产替换点（见 `db/postgres-pgvector.sql`） |
| --- | --- | --- |
| 存储 | H2 内存库，向量以 JSON 存 CLOB | PostgreSQL 16 + pgvector，`vector(1024)` 列 |
| 向量路 | 全表扫描 + 内存余弦排序 | HNSW 索引（`vector_cosine_ops`），`ef_search` 按时延调优 |
| 全文路 | 内存倒排 + 简化 BM25 | `tsvector` 生成列 + GIN 索引；中文接分词器 |
| 嵌入 | 本地哈希桶向量（确定性、可离线复现，仅演示链路） | 真实嵌入模型 / OpenAI 兼容端点，实现 `EmbeddingClient` 即可替换 |
| 重排 | `PassThroughRerankClient` 直通、关闭 | 外部重排模型端点，实现 `RerankClient` 并开启 |
| 缓存 | Caffeine，可开关、可配 TTL | 生产可叠加分布式缓存与旁路缓存 |

替换步骤：保持 `EmbeddingClient` / `RerankClient` 接口不变，替换实现；把召回 SQL 切到 `postgres-pgvector.sql` 草案；融合层与编排层无需改动。

## 8. 参数配置（`application.yml`）

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `app.embedding.dimension` | 1024 | 向量维度，嵌入实现与表结构需对齐 |
| `app.chunk.max-chars` | 1000 | 分块字符上限 |
| `app.chunk.overlap-chars` | 150 | 相邻窗口重叠字符数 |
| `app.retrieval.rrf-k` | 60 | RRF 常数 K |
| `app.retrieval.vector-weight` | 0.7 | 向量路权重（示例默认值，可调） |
| `app.retrieval.text-weight` | 0.3 | 全文路权重（示例默认值，可调） |
| `app.retrieval.candidate-multiplier` | 3 | 重排开启时的候选扩大倍数 |
| `app.retrieval.cache-enabled` | true | 是否开启结果缓存 |
| `app.retrieval.cache-ttl-seconds` | 300 | 缓存 TTL（秒） |
| `app.retrieval.thread-pool-size` | 4 | 两路并行线程池大小 |

## 9. 取舍清单

- **什么时候混合检索不值得做**：语料量很小、查询高度模式化，单路向量或单路全文就够，不必为架构而架构。
- **中文分词是现实短板**：教学版简化处理，生产必须用中文分词器或专用检索方案替换，不要把演示实现当成生产能力。
- **嵌入模型选型比堆索引参数更影响语义路质量**；换嵌入模型需要重建向量索引。
- **混合检索不是效果银弹**：分块质量、元数据过滤、必要的查询改写才是效果大头；本工程不评测具体榜单分数，也不把查询扩展写进主链路必经步骤。

## 10. 边界声明（请勿超出）

- 本地哈希嵌入只演示接口与链路，**不代表真实语义质量**；不要用本工程的命中率去论证生产模型效果。
- 工程内**没有**任何召回率、准确率、时延、QPS 的效果榜单；参数均为教学默认值，生产取值必须基于真实语料与评测集回归。
- `samples/` 三份语料为虚构合成内容，不对应任何真实行业台账、制度或站点。
- 本工程不涉及模型网关、鉴权、计费、多租户路由等能力；这些不在 RAG 检索阶段范围内。
- 建议的自评方式：人工标注几十条查询，分别记录向量单路、全文单路、融合结果中目标片段的排名位置，观察补盲效果；结论只对自己的语料负责，不在公开材料里给结论性百分比。

## 11. 测试覆盖

- `ChunkingServiceTest`：标题分段、窗口上限、空正文兜底。
- `RankFusionServiceTest`：多路累加、topK 截断去重、权重数量不符回退等权、加权排序倾向。
- `HybridRetrievalServiceTest`：Spring 上下文 + H2 样例入库，专有编号命中、排名连续、异常输入结构合法。

## 12. 目录结构

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
│   ├── retrieval       # 向量路、全文路、RRF、混合编排
│   │   └── dto         # 融合候选结果对象
│   └── web             # REST 接口
├── src/main/resources
│   ├── application.yml
│   ├── db/schema.sql              # H2 教学版 schema
│   ├── db/postgres-pgvector.sql   # 生产 PostgreSQL/pgvector 草案（对照用）
│   └── samples/                   # 合成样例语料（虚构）
└── src/test/java/...              # 分块 / RRF / 编排测试
```
