# VOmni — 视频全能推荐平台

> 一个基于微服务架构的短视频推荐系统，集成了用户认证、媒体处理、多模态语义理解与三阶段个性化推荐等核心能力。

-----

## 📐 系统架构

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  vomni-auth │    │vomni-media  │    │vomni-search │    │vomni-inter  │
│  认证服务    │    │ 媒体服务    │    │  搜索服务   │    │  交互服务   │
└──────┬──────┘    └──────┬──────┘    └──────┬──────┘    └──────┬──────┘
       │                  │                  │                  │
       └──────────────────┴──────────────────┴──────────────────┘
                                    │
              ┌─────────────────────┼──────────────────────┐
              │                     │                      │
         ┌────┴────┐          ┌─────┴────┐          ┌─────┴────┐
         │  Redis  │          │  Kafka   │          │   ES     │
         │Stack缓存│          │  消息队列 │          │ 搜索引擎  │
         └─────────┘          └──────────┘          └──────────┘
              │                                           │
    ┌─────────┴──────────┐                    ┌──────────┴──────────┐
    │       MinIO        │                    │  vomni-embedding    │
    │    对象存储         │                    │ 多模态向量化 + 排序  │
    └────────────────────┘                    └─────────────────────┘
```

-----

## 📦 模块介绍

### `vomni-auth` — 用户认证服务

负责用户注册、登录与会话管理，所有并发安全由 Redis Lua 函数保证。

**核心设计：**

- **Redis 状态机（Lua Function）**：注册/登录全流程（`INITIAL → PENDING → VERIFIED → REGISTERED → PENDING_LOGIN → LOGGED_IN`）以 `FCALL` 原子执行，彻底消除并发竞态，无需分布式锁
- **Kafka 异步解耦**：验证码发送（`auth-code-topic`）、用户入库（`input-user-information-topic`）、基础信息更新（`auth-basic-info`）全部异步消费，主流程毫秒级响应
- **双布隆过滤器**（Redisson）：邮箱布隆 + ID 布隆，百万级数据注册判重 O(1)，3% 容错率，完全绕开数据库
- **JWT 双 Token**：`access_token` 放 Authorization Header，`refresh_token` 存 HttpOnly Cookie，登出写 Redis 黑名单实现即时失效
- **雪花 ID**：分布式唯一用户 ID 生成
- **验证码限流**：发送/校验各自最多 5 次，TTL 300 秒，超限自动锁定
- **ES 用户画像**：注册完成后同步创建文档，存储兴趣向量（512维）、人口属性、聚类向量（Base64 binary）

**API 端点：**

```
POST   /auth/register/code    发送注册验证码
POST   /auth/register/verify  验证注册码
POST   /auth/login/code       发送登录验证码
POST   /auth/login/verify     验证登录码，返回 JWT
POST   /auth/basic/info       补全用户基础信息（性别/生日/地区）
DELETE /auth/logout           登出，注销 Token
```

**技术栈：** Spring Boot · Spring Security · Redis Stack · Redisson · Kafka · MySQL · MyBatis

-----

### `vomni-media` — 媒体处理服务

负责视频的上传、转码与多模态向量化入库，通过状态机驱动全生命周期。

**核心设计：**

- **MinIO 预签名上传**：客户端直传 `raws-video` Bucket，绕过服务端，原始视频 1 天后自动过期
- **FFmpeg 转码**：视频转 HLS 切片存入 `final-video`；封面截图存入 `final-cover`（公开读权限）
- **Redis 状态机**（`UPLOADING → TRANSCODING → VECTORIZING → PUBLISHED / FAILED`）：每个状态转移由 Kafka 事件驱动，保证幂等
- **gRPC 调用 Embedding 服务**：视频 URL + 标题 → `VideoEmbedService.GetVideoEmbedding` → 512 维融合向量
- **ES 双写**：向量 + 元数据写入 Elasticsearch，供搜索召回

**技术栈：** Spring Boot · Kafka · MinIO · FFmpeg · Redis · Elasticsearch · gRPC

-----

### `vomni-search` — 搜索与推荐服务

负责视频的语义检索与三阶段个性化推荐排序，是推荐链路的 Java 侧调度中心。

**核心设计：**

- **向量检索**：用户查询文本 → gRPC `TextEmbedService` → 512 维向量，在 ES 做 kNN 近似最近邻搜索；同时支持 BM25 关键词召回，双路融合
- **用户行为建模**：监听 Kafka 行为事件，维护用户短期行为序列（Redis）+ 搜索历史（MySQL）+ 兴趣向量（ES）
- **三阶段排序调度**：Java 侧协调召回结果，gRPC 调用 Python 端 `RankServicer` 完成 DLRM → Transformer → RL 全链路排序
- **长期兴趣更新**：行为累积到阈值后，调用 `UserModelService.GetLongTermInterest` 重新聚类并回写 ES

**技术栈：** Spring Boot · Kafka · Elasticsearch · Redis · gRPC · MySQL · MyBatis

-----

### `vomni-interact` — 用户交互服务

负责点赞、收藏、评论、关注等社交行为，是用户画像数据的重要来源。

**核心设计：**

- **计数缓存**：点赞/收藏数写入 Redis，定时异步刷回 MySQL，读多写少完全走缓存
- **幂等设计**：通过 Redis Set 防止重复点赞/收藏
- **行为事件发布**：用户每次交互通过 Kafka 异步通知 search 服务更新用户短期序列与兴趣向量
- **Elasticsearch**：评论全文索引，支持关键词检索

**技术栈：** Spring Boot · Kafka · Redis · Elasticsearch · MySQL · MyBatis

-----

### `vomni-embedding` — 多模态向量化与推荐模型服务（Python）

整个系统的 AI 核心，负责视频内容理解、用户兴趣建模与三阶段排序推理，通过 gRPC 对外提供服务。

#### 多模态融合管线

视频向量化分四路并行提取，最终经 `FusionMLP` 压缩为 512 维统一语义向量：

```
视频 URL
   │
   ├─ FFmpeg 抽帧 (336×336) ──► PersonSuppressedCLIP ──────────────► 768 维视觉向量
   │                             (YOLOv8 检测人物 patch,
   │                              Attention Bias 压制人物区域,
   │                              让模型关注内容而非人物脸)
   │
   ├─ FFmpeg 提音频 (16kHz) ──► faster-whisper ASR ──► SentenceTransformer ──► 384 维音频语义向量
   │
   ├─ EasyOCR 帧内文字识别 ──► SentenceTransformer ─────────────────────────► 384 维字幕语义向量
   │
   └─ 视频标题 ────────────────► SentenceTransformer ─────────────────────────► 384 维标题语义向量
                                                                                      │
                                        concat(768 + 384 + 384 + 384) = 1920 维 ──────┘
                                                                                      │
                                                                               FusionMLP
                                                                       (Linear + LayerNorm + GELU)
                                                                                      │
                                                                         512 维 L2 归一化向量
```

**PersonSuppressedCLIP 设计细节：**  
ViT-L/14@336px 将图像切成 576 个 patch，YOLOv8 检测到人物后，向对应 patch 列的 Attention `Q·K^T` 矩阵注入大负数 bias（-10.0），Softmax 后该列权重趋近于 0。这使视觉特征更关注场景、物体、文字等内容本身，而非人物外貌，Hook 注入在最后 3 个 Transformer Block，前向传播结束后自动移除。

#### 用户兴趣建模（LongShortUserModel）

```
用户长期历史行为 (N 条 512 维向量 + 4 维业务标签)
          │
    KMeansProcessor (k-means++)
          │
    K 个长期兴趣质心 (Long-term K/V)        用户短期行为序列 (最近 24 条)
          │                                            │
          └───────────────┬────────────────────────────┘
                          │      当前候选视频 embedding 作为 Query
                 LongShortUserModel
            短期序列：带位置偏置 Attention（近因效应）
            长期质心：纯语义 Attention（底层稳定偏好）
            融合比例：85% 短期 + 15% 长期
            后处理：Soft Residual + LayerNorm
                          │
               512 维用户实时兴趣向量
```

#### gRPC 接口（Protobuf）

|服务                 |方法                     |功能                                       |
|-------------------|-----------------------|-----------------------------------------|
|`VideoEmbedService`|`GetVideoEmbedding`    |视频 URL → 512 维多模态融合向量                    |
|`TextEmbedService` |`GetTextEmbedding`     |搜索词 → 512 维向量（CLIP + SentenceTransformer）|
|`UserModelService` |`GetLongTermInterest`  |历史行为 KMeans 聚类 → 长期兴趣质心                  |
|`UserModelService` |`GetUserInterestVector`|长短期兴趣融合 → 用户实时兴趣向量                       |

**技术栈：** Python · PyTorch · gRPC · faster-whisper · CLIP ViT-L/14@336px · SentenceTransformer · EasyOCR · YOLOv8 · scikit-learn · NumPy

-----

## 🎯 三阶段推荐排序流水线

> 设计哲学：DLRM 看透你的**过去**，Transformer 感知你的**当下**，强化学习谋划你的**未来**。

```
召回层（Recall）
  ├── ES kNN 向量召回（多模态语义相似）
  ├── ES BM25 关键词召回（字面匹配）
  └── 用户兴趣向量实时召回
             │
             ▼ ~1000 候选
┌───────────────────────────────────────────────────┐
│  Stage 1  DLRM 粗排  「掌管过去」                  │
│                                                   │
│  · 7 路离散特征 Embedding（用户/视频/聚类/性别/地区）│
│  · 稠密特征 Bottom MLP（年龄、热度）               │
│  · 512 维多模态视频向量投影                        │
│  · 全特征两两内积交叉（9 向量 → 36 个交叉项）      │
│  · Top MLP 打分，快速从千级候选筛至 TopK           │
│                                                   │
│  擅长：从用户长期稳定行为模式中挖掘深层偏好        │
└───────────────────────────────────────────────────┘
             │
             ▼ ~100 候选
┌───────────────────────────────────────────────────┐
│  Stage 2  Transformer 精排  「掌管当下」            │
│                                                   │
│  · 以候选视频 embedding 为 Query                  │
│  · TransformerEncoder 对短期行为序列建模           │
│  · Mean Pooling 聚合上下文，与 DLRM 分 0.5:0.5 融合│
│                                                   │
│  擅长：捕捉用户"此刻"的上下文兴趣漂移             │
│  你刚刷完一小时旅行 vlog，此刻最想看的             │
│  未必是你长期数据里的最爱                          │
└───────────────────────────────────────────────────┘
             │
             ▼ ~20 候选
┌───────────────────────────────────────────────────┐
│  Stage 3  RL 重排  「掌管未来」                    │
│                                                   │
│  · 将列表呈现建模为序贯决策问题（MDP）             │
│  · DQN（RerankQNetwork）每步预测选择的长期 Q 值   │
│  · 奖励 = 完播率得分 - 多样性惩罚                 │
│    （余弦相似度度量已选项的同质化程度）            │
│  · 贪心序列生成，每步选 Q 值最大的候选            │
│                                                   │
│  目标：优化长期用户留存，而非单次点击率            │
│  在相关性、多样性、新颖性之间动态权衡              │
└───────────────────────────────────────────────────┘
             │
             ▼ 最终推荐列表
```

-----

## 🛠️ 技术栈总览

|分类    |技术                                |
|------|----------------------------------|
|后端框架  |Spring Boot 3 · Spring Security   |
|ORM   |MyBatis                           |
|数据库   |MySQL 8.0                         |
|缓存    |Redis Stack（Lettuce + Redisson）   |
|消息队列  |Apache Kafka（KRaft 模式，无 Zookeeper）|
|对象存储  |MinIO                             |
|搜索引擎  |Elasticsearch 8.12.2 + IK 分词      |
|视频处理  |FFmpeg                            |
|RPC   |gRPC + Protobuf                   |
|ML 框架 |PyTorch                           |
|视觉模型  |CLIP ViT-L/14@336px               |
|语音识别  |faster-whisper                    |
|文本编码  |SentenceTransformer               |
|OCR   |EasyOCR                           |
|目标检测  |YOLOv8n（人物 patch 压制）              |
|聚类算法  |scikit-learn KMeans++             |
|ID 生成 |Snowflake Algorithm               |
|Lua 脚本|Redis Function (FCALL)            |

-----

## 🚀 快速启动

### 基础设施（Docker Compose）

```bash
docker compose up -d
```

`docker-compose.yml` 包含以下服务：

|容器                |对外端口                       |说明                             |
|------------------|---------------------------|-------------------------------|
|`mysql-server`    |`13306:3306`               |MySQL 8.0，utf8mb4              |
|`my-redis`        |`16379:6379` · `18001:8001`|Redis Stack（含 RedisInsight 可视化）|
|`my-kafka`        |`19092:19092`              |Kafka KRaft 模式（无 Zookeeper）    |
|`my-minio`        |`9000:9000` · `9001:9001`  |MinIO 对象存储                     |
|`minio-init`      |—                          |自动初始化 Bucket 及 ILM 规则          |
|`my-elasticsearch`|`19200:9200`               |Elasticsearch 8.12.2           |
|`my-kibana`       |`15601:5601`               |Kibana 可视化                     |


> **IK 分词插件**：ES 容器启动后需手动安装一次
> 
> ```bash
> docker exec -it my-elasticsearch bash
> ./bin/elasticsearch-plugin install --batch \
>   https://get.infini.cloud/elasticsearch/analysis-ik/8.12.2
> exit
> docker restart my-elasticsearch
> ```

所有容器挂载在 `v-omni-network` bridge 网络，服务间通过容器名互访。

-----

### 启动各微服务

```bash
# 认证服务
cd vomni-auth && mvn spring-boot:run

# 媒体服务
cd vomni-media && mvn spring-boot:run

# 搜索服务
cd vomni-search && mvn spring-boot:run

# 交互服务
cd vomni-interact && mvn spring-boot:run

# Embedding / 推荐排序服务 (Python)
cd vomni-embedding
pip install -r requirements.txt
python server.py   # gRPC 服务启动，预热所有模型
```

-----

## 📁 项目结构

```
VOmni/
├── docker-compose.yml       # 基础设施一键启动
│
├── vomni-auth/              # 用户认证服务 (Java)
│   ├── config/              # Redis / Redisson / MinIO / ES / Security 配置
│   ├── controller/          # 注册、登录、登出接口
│   ├── consumer/            # Kafka 消费者（发码、入库、基础信息）
│   ├── domain/statemachine/ # AuthState / AuthEvent / AuthTransitionService
│   ├── filter/              # JwtAuthenticationFilter
│   ├── mapper/              # UserMapper (MyBatis)
│   └── resources/lua/       # Redis Lua Function 脚本
│
├── vomni-media/             # 媒体处理服务 (Java)
│   ├── consumer/            # Kafka 消费者（转码、向量化、发布）
│   ├── service/             # FFmpeg / MinIO / gRPC 调用
│   └── domain/statemachine/ # MediaState / MediaTransitionService
│
├── vomni-search/            # 搜索推荐服务 (Java)
│   ├── consumer/            # 行为事件消费，更新用户画像
│   ├── service/             # kNN 向量检索 / 三阶段排序调度
│   └── grpc/                # gRPC Stub（TextEmbed / UserModel）
│
├── vomni-interact/          # 用户交互服务 (Java)
│   ├── controller/          # 点赞、收藏、评论、关注接口
│   └── service/             # 计数缓存 / 行为事件发布
│
└── vomni-embedding/         # 多模态向量化 + 推荐排序 (Python)
    ├── encoders/
    │   ├── clip_encoder.py  # PersonSuppressedCLIP（YOLOv8 + Attention Bias Hook）
    │   ├── asr_encoder.py   # faster-whisper → SentenceTransformer
    │   ├── ocr_encoder.py   # EasyOCR → SentenceTransformer
    │   └── text_encoder.py  # 标题编码 + CLIP 文本侧编码
    ├── models/
    │   ├── fusion_mlp.py          # FusionMLP (1920 → 512)
    │   ├── dlrm.py                # VOmni_DLRM（粗排）
    │   ├── transformer_ranker.py  # TransformerRanker（精排）
    │   ├── reranker.py            # RerankQNetwork + Reranker（RL 重排）
    │   ├── long_short_model.py    # LongShortUserModel（用户兴趣融合）
    │   ├── kmeans_processor.py    # KMeansProcessor（长期兴趣聚类）
    │   └── registry.py            # 全局懒加载模型单例
    ├── pipeline/
    │   └── fusion.py        # fuse_to_512 四路融合管线
    ├── servicer/
    │   ├── video_embed_servicer.py  # VideoEmbedServicer
    │   ├── text_embed_servicer.py   # TextEmbedServicer
    │   ├── user_model_servicer.py   # UserModelServicer
    │   └── rank_servicer.py         # RankServicer（三阶段全流水线）
    └── server.py            # gRPC Server 入口，启动时预热所有模型
```

-----

## 🔑 关键设计亮点

**1. Redis Lua Function 原子状态机**  
注册/登录所有状态转移（含限流计数）在单次 `FCALL` 内原子完成，无并发窗口，无需 Redisson 锁，比 Spring State Machine 节省一半网络往返。

**2. Kafka 驱动的事件溯源**  
验证码发送、视频转码、向量化入库、用户行为更新，全部异步事件化。任一节点故障只影响消费进度，不阻塞主流程，天然支持重试与补偿。

**3. PersonSuppressedCLIP — 内容优先的视觉理解**  
在 CLIP ViT-L/14 最后三层注入 Attention Bias Hook，通过 YOLOv8 定位人物 Patch 后将其注意力权重压制为近零。模型真正理解视频”讲了什么”，而非”谁长得好看”，大幅提升内容语义的精准度与检索质量。

**4. 四路多模态融合**  
视觉（CLIP 768维）+ 音频语义（ASR→文本 384维）+ 字幕（OCR 384维）+ 标题（384维）四路特征经 FusionMLP 统一压缩至 512 维 L2 归一化向量，一个向量携带视频”看”“听”“读”全部语义。

**5. 三阶段推荐：过去 × 当下 × 未来**

- **DLRM 粗排**（掌管过去）：7 路离散 Embedding 两两内积交叉 36 项，从千级候选中快速挖掘用户长期稳定偏好
- **Transformer 精排**（掌管当下）：Self-Attention 对用户最近行为序列建模，捕捉此刻的上下文兴趣漂移，与 DLRM 分 0.5:0.5 融合
- **RL 重排**（掌管未来）：DQN 将列表呈现建模为序贯决策（MDP），奖励函数同时考量完播率与多样性惩罚，以用户长期留存为优化目标，而非贪婪的单步点击率

**6. 长短期用户兴趣双轨**  
短期：最近 24 条行为序列 + 可学习位置偏置 Attention，强调近因效应；长期：历史行为经 KMeans++ 聚类为 K 个兴趣质心，纯语义 Attention 匹配底层稳定偏好。二者以 85:15 软融合后经 Soft Residual + LayerNorm 输出用户实时兴趣向量。

-----

## 📄 License

MIT License