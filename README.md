<div align="center">

# Skyearch

**轻量级全文搜索引擎** — Netty REST API · Lucene 全文索引 · 中文分词 · 本地持久化

用 **Java + Netty + Lucene** 构建,对外提供极简 REST API,只返回 Document ID,不保存原始文本。

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Netty](https://img.shields.io/badge/Netty-4.1.115.Final-brightgreen.svg)](https://netty.io/)
[![Lucene](https://img.shields.io/badge/Lucene-9.12.3-red.svg)](https://lucene.apache.org/)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/license-MIT-lightgrey.svg)](LICENSE)

</div>

---

## 目录

- [为什么选择 Skyearch](#为什么选择-skyearch)
- [核心特性](#核心特性)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [架构设计](#架构设计)
- [数据模型](#数据模型)
- [REST API 文档](#rest-api-文档)
- [配置说明](#配置说明)
- [中文分词选型](#中文分词选型)
- [测试](#测试)
- [项目结构](#项目结构)
- [设计决策](#设计决策)
- [MVP 边界](#mvp-边界)
- [License](#license)

---

## 为什么选择 Skyearch

当你的业务系统**只需要「写入文本 → 搜索时拿到 Document ID」**这一件事时,一个重型搜索集群往往是过度设计。Skyearch 的核心理念:

> **业务系统保存完整业务数据,搜索服务只保存 Document ID + 文本索引。搜索结果只返回 ID,由业务系统自行回查原始数据。**

这带来三个好处:

1. **职责清晰** — 搜索服务不碰业务数据,不承担数据一致性责任
2. **存储精简** — 不保存原始文本,只建倒排索引,磁盘占用极小
3. **架构解耦** — 搜索服务可独立部署、独立扩缩容、独立重启,不影响业务数据库

---

## 核心特性

- **极简 API** — 只有 Collection / Bucket / Document 三层模型,一个 `text` 字段,无 Schema
- **分层数据模型** — `Collection → Bucket → Document ID → Text`,概念直观
- **Lucene 全权负责索引** — 倒排索引、Posting List、Segment、Merge、持久化全部委托给 Lucene,不自研
- **中文分词开箱即用** — 内置 SmartChineseAnalyzer(HMM 词性标注),中英混排一次处理
- **近实时搜索(NRT)** — 写入后按 `refresh-interval-ms` 刷新,默认 1 秒内可搜到
- **并发安全** — IndexWriter 长期复用,SearcherManager 保证读写并发安全
- **Netty I/O 与业务隔离** — HTTP 编解码在 I/O 事件循环,阻塞的 Lucene 调用在独立线程组
- **优雅关闭** — 停止接收请求 → commit IndexWriter → 关闭索引 → 释放线程组
- **重启自动加载** — 启动时扫描数据目录,已有 Collection / Bucket / 索引直接可用
- **零外部依赖** — 不依赖 Web 框架 / 数据库 / 第三方分词框架,只需 JDK

---

## 技术栈

| 层 | 选型 | 版本 | 职责 |
|---|---|---|---|
| 运行时 | JDK | 25 | 虚拟线程、record、switch 模式匹配 |
| HTTP I/O | Netty | 4.1.115.Final | 事件驱动 HTTP 服务器 |
| 全文索引 | Apache Lucene | 9.12.3 | 倒排索引 / Posting List / Segment / Merge / 持久化 |
| 中文分词 | lucene-analysis-smartcn | 9.12.3 | SmartChineseAnalyzer(HMM) |
| 序列化 | Jackson | 2.18.2 | JSON 响应 + YAML 配置解析 |
| 构建 | Maven | 3.8+ | 依赖管理与打包 |
| 测试 | JUnit 5 | 5.11.4 | 单元测试 + 集成测试 |

**不自研:** 倒排索引、Posting List、Segment、Merge —— 全部委托给 Lucene。

---

## 快速开始

### 环境要求

- **JDK 25+**
- **Maven 3.8+**

### 构建与启动

```bash
# 构建(含测试)
mvn package

# 启动(读取 classpath 中的 application.yml)
java -jar target/skyearch-1.0-SNAPSHOT.jar

# 或指定配置文件
java -cp "target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && cat /tmp/cp.txt)" \
     org.eathink.skyearch.Skyearch /path/to/application.yml
```

> **Windows 用户:** classpath 分隔符使用 `;` 而非 `:`。

看到以下日志即启动成功:

```
http server started on 0.0.0.0:8080
```

`Ctrl+C` 触发优雅关闭(停止接收请求 → commit 索引 → 关闭资源)。

### 30 秒上手

```bash
BASE=http://127.0.0.1:8080/api/v1

# 写入文档(Collection / Bucket 不存在会自动创建)
curl -s -X POST "$BASE/collections/articles/buckets/default/documents/10001" \
     -H "Content-Type: application/json" \
     -d '{"text": "Java Netty 搜索引擎"}'

# 等待 1 秒(refresh-interval-ms)后搜索
sleep 1
curl -s -X POST "$BASE/collections/articles/buckets/default/search" \
     -H "Content-Type: application/json" \
     -d '{"query": "搜索引擎"}'
# → {"ids":["10001"],"total":1}
```

完整示例见 [curl-examples.sh](curl-examples.sh)。

---

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                      Netty Server                        │
│  ┌───────────┐    ┌───────────────┐    ┌─────────────┐  │
│  │ BossGroup │───▶│ WorkerGroup   │───▶│ Business    │  │
│  │ (accept)  │    │ (HTTP codec)  │    │ ExecutorGroup│ │
│  └───────────┘    └───────────────┘    └──────┬──────┘  │
└───────────────────────────────────────────────┼─────────┘
                                                │
                                         HttpRouter │
                                                │
                    ┌───────────────────────────┼───────────────────────────┐
                    ▼                           ▼                           ▼
           CollectionHandler           BucketHandler              DocumentHandler
                                                                         │
                                                                    SearchHandler
                                                                         │
                    ┌───────────────────────────┴───────────────────────────┐
                    ▼                                                        
              CollectionManager ────▶ BucketManager ────▶ IndexManager
                                                         │
                                                  ┌──────┴──────┐
                                                  ▼              ▼
                                            LuceneIndex    LuceneIndex
                                          (Bucket: default)  (Bucket: ...)
                                                  │
                                          ┌───────┴───────┐
                                          ▼               ▼
                                    IndexWriter     SearcherManager
                                    (写,复用)        (NRT 读,并发安全)
                                          │               │
                                          ▼               ▼
                                        Directory (每个 Bucket 独立 Lucene 索引目录)
```

### 关键设计

**每个 Bucket 一个独立 Lucene 索引**

```
data/
└── collections/
    └── articles/                    # Collection
        └── default/                 # Bucket
            └── index/              # Lucene 索引目录(完全隔离)
        └── archive/
            └── index/
```

不同 Bucket 的索引完全隔离,删除 Bucket 即删除整个索引目录,无碎片残留。

**IndexWriter 长期复用**

每个 Bucket 的 `IndexWriter` 在首次访问时创建,长期复用直到服务关闭或 Bucket 被删除。**绝不每请求创建 IndexWriter**。Lucene 自身保证单 IndexWriter 的并发写安全。

**SearcherManager(NRT 近实时)**

写入后由后台守护线程按 `refresh-interval-ms` 调用 `maybeRefresh`,刷新 SearcherManager 的 NRT reader。新写入在刷新后即可被搜索到(默认 1 秒延迟)。搜索并发安全,多个请求可同时使用同一 IndexSearcher。

**Netty I/O 与业务隔离**

- `bossGroup`(1 线程)— 接受连接
- `workerGroup`(CPU 核数)— HTTP 编解码,纯 I/O,不阻塞
- `businessGroup`(CPU × 2,最小 4)— 执行业务 Handler,阻塞的 Lucene 调用在此进行,不占用 I/O 事件循环

**优雅关闭顺序**

```
停止接受新连接(关闭 server channel)
    ↓
commit 全部 IndexWriter(持久化未刷盘的索引)
    ↓
关闭 IndexWriter / Directory
    ↓
释放 Netty 线程组(boss / worker / business)
```

**重启自动加载**

启动时 `CollectionManager` 扫描 `data/collections` 目录,已存在的 Collection / Bucket / Lucene 索引直接打开复用,无需重建。

---

## 数据模型

`Collection → Bucket → Document ID → Text`。

```
Collection: articles
    └── Bucket: default
            ├── Document ID: 10001  →  Text: "Java Netty 搜索引擎"
            ├── Document ID: 10005  →  Text: "Python 数据分析"
            └── Document ID: 10008  →  Text: "Netty 网络编程实战"
```

文档只有一个字段(无 Schema,不做 keyword / numeric / date / filter / aggregation):

```json
{ "text": "Java Netty 搜索引擎" }
```

**Lucene Document 结构:**

| 字段 | 类型 | 存储 | 说明 |
|---|---|---|---|
| `id` | `StringField` | **Store.YES** | Document ID,搜索服务最重要的数据,命中后返回给调用方 |
| `text` | `TextField` | **Store.NO** | 只建全文索引,**不保存原始文本** |

---

## REST API 文档

统一前缀 `/api/v1`。所有错误返回统一 JSON:

```json
{ "code": 400, "message": "..." }
```

### 写入文档(upsert)

```
POST /api/v1/collections/{collection}/buckets/{bucket}/documents/{id}
```

**请求体:**

```json
{ "text": "Java Netty 搜索引擎" }
```

**行为:** 自动创建 Collection / Bucket → 更新 Lucene Document(`id` 存储,`text` 建索引不存储)→ 返回 ID。同一 ID 重复写入覆盖旧文档。

**响应:** `200 OK`

```json
{ "id": "10001" }
```

### 搜索

```
POST /api/v1/collections/{collection}/buckets/{bucket}/search
```

**请求体:**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `query` | string | 是 | — | 搜索关键词,支持单关键词 / 多关键词 |
| `operator` | string | 否 | `AND` | 多关键词逻辑运算,`AND` / `OR` |
| `limit` | int | 否 | `search.default-limit` | 返回数量,上限 `search.max-limit` |
| `offset` | int | 否 | `0` | 分页偏移量 |

```json
{
  "query": "Java Netty",
  "operator": "AND",
  "limit": 20,
  "offset": 0
}
```

**响应:** `200 OK`

```json
{
  "ids": ["10001", "10005", "10008"],
  "total": 3
}
```

> 只返回 Document ID。写入后需等待 `refresh-interval-ms` 才能搜到(近实时)。

### 删除文档

```
DELETE /api/v1/collections/{collection}/buckets/{bucket}/documents/{id}
```

| 响应 | 说明 |
|---|---|
| `204 No Content` | 删除成功 |
| `404 Not Found` | 文档不存在 |

### Collection 管理

| 方法 | 路径 | 说明 | 成功 | 已存在 / 不存在 |
|---|---|---|---|---|
| `POST` | `/api/v1/collections/{collection}` | 创建 | `201` | `409` |
| `GET` | `/api/v1/collections/{collection}` | 详情(含 buckets 及文档数) | `200` | `404` |
| `DELETE` | `/api/v1/collections/{collection}` | 删除(级联删除全部 Bucket) | `204` | `404` |

### Bucket 管理

| 方法 | 路径 | 说明 | 成功 | 已存在 / 不存在 |
|---|---|---|---|---|
| `POST` | `/api/v1/collections/{collection}/buckets/{bucket}` | 创建 | `201` | `409` |
| `GET` | `/api/v1/collections/{collection}/buckets/{bucket}` | 详情(文档数) | `200` | `404` |
| `DELETE` | `/api/v1/collections/{collection}/buckets/{bucket}` | 删除(级联删除索引) | `204` | `404` |

### 错误码

| 状态码 | 场景 |
|---|---|
| `400` | 参数错误(非法 JSON / 缺 `text` / 非法名称或 ID / 非法 operator / 负 offset) |
| `404` | Collection / Bucket / Document / 路由不存在 |
| `405` | 路径存在但方法不支持 |
| `409` | Collection / Bucket 已存在 |
| `500` | 内部错误 |

**名称约束**(同时防止路径穿越):

- Collection / Bucket: `[A-Za-z0-9_-]{1,128}`
- Document ID: `[A-Za-z0-9_.:-]{1,256}`

### curl 示例

<details>
<summary>点击展开完整 curl 示例</summary>

```bash
B=http://127.0.0.1:8080/api/v1

# 1. 创建 Collection(201;重复创建返回 409)
curl -s -X POST $B/collections/articles

# 2. 创建 Bucket
curl -s -X POST $B/collections/articles/buckets/default

# 3. 写入文档(Collection / Bucket 不存在会自动创建)
curl -s -X POST $B/collections/articles/buckets/default/documents/10001 \
     -H "Content-Type: application/json" \
     -d '{"text": "Java Netty 搜索引擎"}'
# → {"id":"10001"}

curl -s -X POST $B/collections/articles/buckets/default/documents/10005 \
     -H "Content-Type: application/json" \
     -d '{"text": "Python 数据分析"}'

curl -s -X POST $B/collections/articles/buckets/default/documents/10008 \
     -H "Content-Type: application/json" \
     -d '{"text": "Netty 网络编程实战"}'

# 4. 搜索(等待 refresh-interval-ms 后生效)
curl -s -X POST $B/collections/articles/buckets/default/search \
     -H "Content-Type: application/json" \
     -d '{"query": "搜索引擎"}'
# → {"ids":["10001"],"total":1}

# AND: 同时包含 Netty 和 Java
curl -s -X POST $B/collections/articles/buckets/default/search \
     -H "Content-Type: application/json" \
     -d '{"query": "Netty Java", "operator": "AND"}'
# → {"ids":["10001"],"total":1}

# OR + 分页
curl -s -X POST $B/collections/articles/buckets/default/search \
     -H "Content-Type: application/json" \
     -d '{"query": "Netty Python", "operator": "OR", "limit": 2, "offset": 0}'
# → {"ids":["10005","10001"],"total":3}

# 5. 查看 Bucket / Collection
curl -s $B/collections/articles/buckets/default
# → {"collection":"articles","name":"default","documents":3}
curl -s $B/collections/articles
# → {"name":"articles","buckets":[{"name":"default","documents":3}],"documents":3}

# 6. 删除文档
curl -s -X DELETE $B/collections/articles/buckets/default/documents/10005
# → 204; 再删一次返回 404

# 7. 删除 Bucket / Collection
curl -s -X DELETE $B/collections/articles/buckets/default
curl -s -X DELETE $B/collections/articles

# 8. 错误示例
curl -s -X POST $B/collections/articles/buckets/default/documents/1 -d '{bad'
# → {"code":400,"message":"invalid json body"}
curl -s $B/collections/missing
# → {"code":404,"message":"collection not found: missing"}
```

> Windows cmd 下中文 body 建议用 `--data-binary @file.json` + UTF-8 文件,避免终端编码问题。

</details>

---

## 配置说明

所有关键参数均通过 [Config.java](src/main/java/org/eathink/skyearch/config/Config.java) 读取 YAML,代码中不硬编码。启动时自动校验,非法配置直接拒绝启动。

```yaml
server:
  host: 0.0.0.0        # 监听地址
  port: 8080           # 监听端口

storage:
  data-path: ./data    # 数据根目录(索引在 data/collections/{c}/{b}/index)

lucene:
  analyzer: smart-chinese   # smart-chinese 或 standard
  ram-buffer-mb: 64         # IndexWriter RAM buffer(MB),越大写入吞吐越高
  refresh-interval-ms: 1000 # 新写入可搜索的延迟,越小越近实时但刷新越频繁

search:
  default-limit: 20   # 未传 limit 时的默认值
  max-limit: 100      # limit 上限,防止超大结果集拖垮服务
```

| 配置项 | 类型 | 校验规则 | 说明 |
|---|---|---|---|
| `server.host` | string | 非空 | 监听地址 |
| `server.port` | int | `[0, 65536)` | 监听端口,`0` 为随机(测试用) |
| `storage.data-path` | string | 非空 | 数据根目录 |
| `lucene.analyzer` | string | `smart-chinese` / `standard` | 中文分词器 |
| `lucene.ram-buffer-mb` | int | `> 0` | IndexWriter RAM 缓冲区 |
| `lucene.refresh-interval-ms` | long | `> 0` | NRT 刷新间隔 |
| `search.default-limit` | int | `> 0` | 默认返回条数 |
| `search.max-limit` | int | `>= default-limit` | 最大返回条数 |

---

## 中文分词选型

选用 Lucene 自带的 **SmartChineseAnalyzer**(Maven artifact `lucene-analysis-smartcn`):

### 选择理由

| 对比项 | SmartChineseAnalyzer | StandardAnalyzer |
|---|---|---|
| 分词方式 | 基于 HMM(隐马尔可夫模型)的中文分词 + 词性标注 | CJK 二元切分(每两个汉字一组) |
| 召回精度 | 高,词典 + 统计模型,切分准确 | 低,产生大量噪声词 |
| 索引膨胀 | 小,精确分词 | 大,二元组合爆炸 |
| 英文处理 | 按空白/标点切分并小写 | 同左 |
| 依赖 | 纯 Java,无第三方依赖 | 同左 |
| 适用场景 | 通用中文全文检索 | 零词典依赖的简单场景 |

SmartChineseAnalyzer 内置基于隐马尔可夫模型的中文分词与词性标注,对通用中文全文检索效果好于 StandardAnalyzer 的 CJK 二元切分。纯 Java、零外部依赖、与 Lucene 版本天然匹配。英文与数字按空白/标点切分并统一小写,中英混排文本("Java Netty 搜索引擎")可一次处理。

**写入与查询使用同一 Analyzer 分词**,保证分词一致性。也可通过配置切换为 `standard`。

---

## 测试

```bash
mvn test
```

| 测试类 | 覆盖范围 |
|---|---|
| `ConfigTest` | YAML 加载、配置校验、非法配置拒绝启动 |
| `HttpRouterTest` | 路径参数匹配、405 Method Not Allowed、未知路由 |
| `LuceneIndexTest` | upsert 覆盖、搜索、删除、分页、重启后数据持久化 |
| `SkyearchHttpTest` | 全链路集成测试(真实 Netty + Lucene):Collection / Bucket / Document 生命周期、AND / OR / 分页、400 / 404 / 409 错误、重启数据保留 |

---

## 项目结构

```
src/main/java/org/eathink/skyearch/
├── Skyearch.java                  # 组装入口: Config → Managers → Handlers → Router → NettyServer
├── config/
│   └── Config.java                # YAML 配置(record)与启动校验
├── core/
│   ├── LuceneIndex.java           # 单 Bucket 索引: upsert / delete / search, IndexWriter + SearcherManager
│   ├── IndexManager.java          # Bucket→索引映射, Analyzer 工厂, 定时 refresh, 统一关闭
│   ├── CollectionManager.java     # Collection 生命周期, 启动时加载已有数据
│   ├── BucketManager.java         # Bucket 生命周期(目录 + 索引)
│   └── SearchResult.java          # ids + total
├── handler/
│   ├── CollectionHandler.java     # /collections/{collection}
│   ├── BucketHandler.java         # /collections/{collection}/buckets/{bucket}
│   ├── DocumentHandler.java       # 文档 upsert / delete
│   ├── SearchHandler.java         # 搜索入口
│   └── SearchService.java         # query 分词 + AND/OR BooleanQuery
├── http/
│   ├── HttpRouter.java            # 路径模板匹配
│   ├── HttpApiHandler.java        # Netty channel handler, 统一 JSON 错误
│   ├── ApiHandler.java            # Handler 接口
│   ├── ApiRequest.java            # 请求封装
│   ├── ApiResponse.java           # 响应封装
│   └── HttpException.java         # HTTP 异常(带状态码)
├── server/
│   └── NettyServer.java           # Netty bootstrap 与生命周期
└── util/
    ├── Json.java                  # Jackson JSON 序列化
    └── Names.java                # 名称校验 / 防路径穿越
```

---

## 设计决策

### 为什么选择 REST 而非 TCP 协议?

REST API + JSON 是事实标准,curl / Postman / 任意 HTTP 客户端可直接使用,零学习成本,调试和对接都简单。

### 为什么不保存原始文本?

搜索服务的职责是"告诉调用方哪些文档匹配",而不是"返回文档内容"。业务系统自己保存完整业务数据,搜索结果只返回 Document ID,由业务系统回查原始数据。这样搜索服务的磁盘占用最小(只存倒排索引),且不承担数据一致性责任。

### 为什么不用 QueryParser?

Lucene 的 QueryParser 支持 `AND/OR/NOT`、通配符、模糊匹配等复杂语法,但也引入了语法注入风险和解析开销。Skyearch 的 MVP 只需要"多关键词 AND/OR",直接用 Analyzer 分词后构造 `BooleanQuery` 更简单、更安全。

### 为什么每个 Bucket 一个独立索引?

不同 Bucket 的数据完全隔离(如 `articles/default` 和 `articles/archive`)。独立索引意味着:
- 删除 Bucket = 删除目录,无碎片残留
- 不同 Bucket 可并行写入,无锁竞争
- 搜索范围天然限定在单个 Bucket 内

### 为什么用 SmartChineseAnalyzer?

SmartChineseAnalyzer 是 Lucene 官方自带,纯 Java、零外部依赖、与 Lucene 版本天然匹配。引入第三方分词框架会增加额外依赖、版本不一定与 Lucene 同步、且有词典维护成本,不符合 MVP 的轻量定位。

---

## MVP 边界

Skyearch 是 MVP,**明确不实现**以下功能:

<details>
<summary>点击查看不实现清单</summary>

- 分布式、Sharding、Replica、Raft、集群
- WAL(Write-Ahead Log)
- 自研倒排索引 / Segment / Posting List / Merge
- keyword / numeric / date / boolean 字段
- Filter、Facet、Aggregation
- Vector Search、Fuzzy Search、Phrase Search
- Highlight(高亮)
- 权限系统、用户系统、管理后台
- SDK

</details>

这些功能在需求出现时再扩展。MVP 的目标是:**用最少的代码,实现一个可以直接运行的、Lucene 驱动的全文搜索服务。**

---

## License

MIT
