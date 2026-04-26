# v-omni-project

**v-omni-auth** —— **v-omni 生态核心认证微服务**

高性能、可扩展、安全可靠的分布式身份认证中心，基于 **JDK 21 + Spring Boot 3** 构建，深度融合现代 Redis 7.0 特性与领域驱动设计。

---

## 🎯 项目亮点

- **Redis 7.0 高级特性**：使用 `redis.register_function` + `FCALL` 实现**状态机引擎**，业务逻辑下沉到 Redis，保证原子性与极致性能
- **分布式状态机**：注册/登录全流程状态驱动（INITIAL → PENDING → REGISTERED → PENDING_LOGIN → LOGGED_IN）
- **策略模式 + Lua**：发送验证码、验证验证码等核心逻辑全部通过可配置的 Lua 策略函数实现
- **原子操作保障**：`atom_get_or_create_id.lua` 实现邮箱与用户ID的原子映射
- **高并发优化**：Lettuce 客户端 + Redisson + Bloom Filter 防穿透
- **异步解耦**：Kafka 异步处理邮件发送，实现流量削峰
- **全容器化**：Docker Compose 一键启动全套中间件

---

## 🛠️ 技术栈

| 类别             | 技术                                      |
|------------------|-------------------------------------------|
| **语言 & 框架**  | JDK 21、Spring Boot 3.5.x                 |
| **持久层**       | MyBatis (Native)                          |
| **缓存 & 脚本**  | Redis 7.0 + Redisson + Lua Script + FCALL |
| **消息队列**     | Apache Kafka                              |
| **ID 生成**      | 自定义 Snowflake                          |
| **容器化**       | Docker + Docker Compose                   |
| **其他**         | Bloom Filter、MapStruct、Lombok、Lettuce  |

---

## 📁 项目结构（核心）

```bash
v-omni-project/
├── sql/                                 # 数据库初始化脚本
├── v-omni-java/v-omni-auth/
│   ├── src/main/java/org/example/vomniauth/
│   │   ├── controller/                  # REST 接口层
│   │   ├── service/                     # 业务服务
│   │   ├── strategy/                    # 策略模式实现
│   │   ├── statemachine/                # 状态机核心
│   │   ├── consumer/                    # Kafka 消费者
│   │   ├── config/                      # Redis、Kafka、MyBatis 配置
│   │   ├── domain/                      # 领域模型 & PO
│   │   ├── dto/                         # 请求响应对象
│   │   ├── mapper/                      # MyBatis Mapper
│   │   └── util/                        # Snowflake、工具类
│   ├── src/main/resources/lua/          # Redis Lua 脚本（核心）
│   │   ├── send_event.lua               # 状态机主引擎（FCALL）
│   │   └── atom_get_or_create_id.lua    # 原子ID生成
│   ├── Dockerfile
│   └── pom.xml
├── compose.yml                          # 全栈启动配置
├── .env.example
└── README.md
