# v-omni-project

## v-omni-auth 🚀

v-omni-auth 是 **v-omni** 生态系统中的核心认证微服务。它是一个基于 **JDK 21** 构建的高性能认证中心，专注于实现高可靠的注册与安全校验流程。

---

### 🛠️ 技术规格
* **核心框架**: Spring Boot 3.5.13
* **运行环境**: **JDK 21** (利用最新 LTS 版本的稳定性能)
* **持久层**: **MyBatis (Native)** - 拒绝封装，追求极致的 SQL 掌控力
* **缓存/限流**: Redis (Redisson) - 深度结合 **Lua 脚本** 保证业务原子性
* **消息驱动**: Apache Kafka - 异步解耦注册任务，实现流量削峰
* **安全机制**: 
    - 集成 **布隆过滤器** (Bloom Filter) 预校验，从根源防止缓存穿透
    - 自定义 Snowflake ID 生成器，保证用户 ID 的唯一性与有序性

---

### ✨ 当前开发进度 (V-Omni-Auth 模块)
- [x] **注册验证流程**: 实现基于邮件的验证码发送与校验。
- [x] **高并发限流**: 针对发送验证码、验证逻辑实现分布式频率控制。
- [x] **分布式校验**: 结合 Redis 与 Lua 实现注册状态的原子化检查。
- [x] **容器化编排**: 全链路容器化，支持 `docker-compose` 一键拉起基础中间件。

---

### 🚀 快速启动

### 1. 配置文件
在项目根目录新建 `.env` 文件，参考以下配置：
```bash
# 数据库 & Redis
SPRING_DATASOURCE_URL=jdbc:mysql://my-mysql:3306/v_omni_db
SPRING_DATA_REDIS_HOST=my-redis

# 邮件服务
V_OMNI_MAIL_USERNAME=your_email@163.com
V_OMNI_MAIL_PASSWORD=your_auth_code

# Kafka 配置
V_OMNI_KAFKA_TOPIC_MAIL=auth-code-topic
```
---

### 2. 注意事项!!!
- 如果springboot项目在docker启动需要去将application-dev.yml文件里面
- kafka: bootstrap-servers: my-kafka:9092
- mysql: url: jdbc:mysql://mysql-server:3306/omni_db?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=GMT%2B8
- redis: host: my-redis
- springboot项目镜像名称记得和compose.yml文件里面的保持一致
