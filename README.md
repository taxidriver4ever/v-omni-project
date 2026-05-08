# VOmni — 视频全能推荐平台

> 一个基于微服务架构的短视频推荐系统，集成了用户认证、媒体处理、语义搜索与个性化推荐等核心能力。

---

## 📐 系统架构

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  vomni-auth │    │vomni-media  │    │vomni-search │    │vomni-inter  │
│  认证服务    │    │ 媒体服务    │    │  搜索服务   │    │  交互服务   │
└──────┬──────┘    └──────┬──────┘    └──────┬──────┘    └──────┬──────┘
       │                  │                  │                  │
       └──────────────────┴──────────────────┴──────────────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              │                     │                     │
         ┌────┴────┐          ┌─────┴────┐         ┌─────┴────┐
         │  Redis  │          │  Kafka   │         │   ES     │
         │  缓存   │          │  消息队列 │         │ 搜索引擎  │
         └─────────┘          └──────────┘         └──────────┘
              │
    ┌─────────┴──────────┐
    │       MinIO        │
    │    对象存储         │
    └────────────────────┘
```

---

## 📦 模块介绍

### `vomni-auth` — 用户认证服务

负责用户注册、登录与会话管理。

**核心设计：**
- **Redis 状态机**：用户认证流程（`INITIAL → PENDING → VERIFIED → REGISTERED → PENDING_LOGIN → LOGGED_IN`）全部以 Lua Function 原子性执行，防止并发竞态
- **布隆过滤器**（Redisson）：快速判断邮箱是否已注册，减少数据库压力
- **雪花 ID**（Snowflake）：分布式唯一用户 ID 生成
- **JWT**：无状态令牌鉴权
- **验证码限流**：发送 / 校验各自最多 5 次，TTL 300 秒，超限锁定

**技术栈：** Spring Boot · Spring Security · Redis · Redisson · MySQL · MyBatis

---

### `vomni-media` — 媒体处理服务

负责视频的上传、转码与向量化入库。

**核心设计：**
- **MinIO**：对象存储，预签名 URL 上传，客户端直传
- **FFmpeg**：视频转码（HLS 切片）与封面截图
- **状态机**（`UPLOADING → TRANSCODING → VECTORIZING → PUBLISHED / FAILED`）：通过 Redis 管理视频生命周期，状态流转事件通过 Kafka 驱动
- **gRPC**：调用 Embedding 服务对视频内容进行向量化
- **Elasticsearch**：向量与元数据双写，支持后续语义检索

**技术栈：** Spring Boot · Kafka · MinIO · FFmpeg · Redis · Elasticsearch · gRPC

---

### `vomni-search` — 搜索与推荐服务

负责视频的语义检索与个性化推荐排序。

**核心设计：**
- **向量检索**：将用户查询文本通过 gRPC 转为 Embedding，在 Elasticsearch 中做 kNN 近似最近邻搜索
- **用户行为建模**：监听 Kafka 行为事件，维护用户搜索历史与兴趣向量（Redis 存储）
- **三阶段排序流水线**：多路召回 → DLRM 粗排 → Transformer 精排 → RL 重排（详见推荐架构说明）
- **搜索历史落库**：异步写入 MySQL（UserSearchHistory）

**技术栈：** Spring Boot · Kafka · Elasticsearch · Redis · gRPC · MySQL · MyBatis

---

### `vomni-interact` — 用户交互服务

负责点赞、收藏、评论、关注等社交行为。

**核心设计：**
- **计数缓存**：点赞 / 收藏数量写入 Redis，定时异步刷回 MySQL
- **幂等设计**：通过 Redis Set 防止重复点赞/收藏
- **行为事件发布**：用户行为通过 Kafka 异步通知搜索 / 推荐服务更新用户画像
- **Elasticsearch**：评论全文索引与检索

**技术栈：** Spring Boot · Kafka · Redis · Elasticsearch · MySQL · MyBatis-Plus

---

### `vomni-embedding` — 向量化与推荐模型服务（Python）

负责文本与视频内容的向量化，以及推荐排序模型的训练与推理，是整个推荐链路的核心智能层。

**核心设计：**
- **gRPC Server**：暴露 Embedding 接口供各 Java 微服务调用，实现跨语言低延迟通信
- **DLRM 粗排模型**：融合稀疏特征（用户 ID、视频 ID、类别、地域等）+ 稠密特征（年龄、热度）+ 视频语义向量，对千级候选集快速打分降至百级
- **Transformer 精排模型**（规划中）：基于用户最近 N 条行为序列建模短期兴趣，对粗排结果做精细化重打分；相比 DLRM 更擅长捕捉用户"当下"的上下文偏好
- **RL 重排**：`RerankEnvironment` 将最终列表的呈现建模为序贯决策问题，DQN 智能体在每一步综合考量相关性、多样性与新颖性，面向用户长期留存而非单次点击率优化
- **模型服务化**：训练完成的权重直接加载 serve，支持实时在线推理

**技术栈：** Python · PyTorch · gRPC · NumPy

---

## 🎯 推荐系统架构：三阶段排序流水线

```
召回层（Recall）
  ├── ES kNN 向量召回（语义相似）
  ├── ES BM25 关键词召回（字面匹配）
  └── 用户历史兴趣向量召回
          │
          ▼ ~1000 候选
┌─────────────────────────────────────────────┐
│  粗排 — DLRM  「掌管过去」                   │
│  · 融合用户长期画像 + 视频多域特征            │
│  · 海量离散特征 Embedding 交叉               │
│  · 目标：从历史行为中挖掘稳定偏好，快速过滤  │
└─────────────────────────────────────────────┘
          │
          ▼ ~100 候选
┌─────────────────────────────────────────────┐
│  精排 — Transformer  「掌管当下」  【规划中】 │
│  · 对用户最近 N 条行为序列建模               │
│  · Self-Attention 捕捉上下文兴趣漂移         │
│  · 目标：感知用户此刻的实时意图，精准打分    │
└─────────────────────────────────────────────┘
          │
          ▼ ~20 候选
┌─────────────────────────────────────────────┐
│  重排 — Reinforcement Learning  「掌管未来」 │
│  · 将列表呈现建模为序贯决策（MDP）           │
│  · DQN 在相关性、多样性、新颖性间动态权衡    │
│  · 目标：优化长期用户留存，而非单步点击率    │
└─────────────────────────────────────────────┘
          │
          ▼ 最终推荐列表

---

## 🛠️ 技术栈总览

| 分类 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3, Spring Security |
| ORM | MyBatis |
| 数据库 | MySQL |
| 缓存 | Redis (Lettuce + Redisson) |
| 消息队列 | Apache Kafka |
| 对象存储 | MinIO |
| 搜索引擎 | Elasticsearch 8 |
| 视频处理 | FFmpeg |
| RPC | gRPC (Protobuf) |
| ML 框架 | PyTorch |
| ID 生成 | Snowflake Algorithm |
| Lua 脚本 | Redis Function / EVALSHA |

---

## 🚀 快速启动

### 前置依赖

确保以下服务已运行：

```bash
# Redis
docker run -d -p 6379:6379 redis:7

# Kafka + Zookeeper
docker-compose up -d kafka

# Elasticsearch
docker run -d -p 19200:9200 elasticsearch:8.x

# MinIO
docker run -d -p 9000:9000 -p 9001:9001 minio/minio server /data --console-address ":9001"

# MySQL
docker run -d -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8
```

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

# Embedding 服务 (Python)
cd vomni-embedding && pip install -r requirements.txt && python server.py
```

---

## 📁 项目结构

```
VOmni/
├── vomni-auth/          # 用户认证服务 (Java)
│   ├── config/          # Redis、MinIO、ES、ID 配置
│   ├── controller/      # 注册、登录接口
│   ├── service/         # 认证业务逻辑
│   ├── domain/statemachine/  # 状态机定义
│   └── resources/lua/   # Redis Lua 函数脚本
│
├── vomni-media/         # 媒体处理服务 (Java)
│   ├── consumer/        # Kafka 消费者（转码、向量化）
│   ├── service/         # FFmpeg、MinIO、gRPC 调用
│   └── domain/statemachine/  # 视频状态机
│
├── vomni-search/        # 搜索推荐服务 (Java)
│   ├── consumer/        # 行为事件消费
│   ├── service/         # 向量检索、推荐逻辑
│   └── grpc/            # gRPC Stub
│
├── vomni-interact/      # 用户交互服务 (Java)
│   ├── controller/      # 点赞、评论、关注接口
│   └── service/         # 计数缓存、行为事件发布
│
└── vomni-embedding/     # 向量化与推荐模型 (Python)
    ├── dataset.py       # DLRM 数据集
    ├── model.py         # DLRM + 重排模型
    ├── train.py         # 训练脚本
    └── server.py        # gRPC 服务端
```

---

## 🔑 关键设计亮点

**1. Redis Lua 原子状态机**  
注册 / 登录流程的所有状态转移都在单个 Lua 函数内原子完成，彻底消除并发竞态，无需分布式锁。

**2. 事件驱动架构**  
各服务间通过 Kafka 解耦，视频上传、转码、向量化、行为记录全链路异步流转，任一节点故障不影响其他服务。

**3. 向量 + 关键词双路检索**  
Elasticsearch 同时支持 BM25 关键词匹配与 kNN 向量相似度，两路召回融合后送入精排模型。

**4. 三阶段推荐流水线：过去 × 当下 × 未来**  
- **DLRM 粗排**（掌管过去）：从用户长期历史中挖掘稳定偏好，Embedding 交叉快速过滤千级候选至百级
- **Transformer 精排**（掌管当下，规划中）：Self-Attention 对用户最近行为序列建模，捕捉实时兴趣漂移，感知此刻意图
- **RL 重排**（掌管未来）：将列表呈现建模为序贯决策（MDP），DQN 在相关性、多样性与新颖性之间动态权衡，以用户长期留存为优化目标，而非单次点击率

---

## 📄 License

MIT License