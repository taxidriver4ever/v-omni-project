-- 1. 如果数据库不存在则创建，指定字符集为 utf8mb4 (支持表情包和特殊字符)
CREATE DATABASE IF NOT EXISTS `omni_db` 
DEFAULT CHARACTER SET utf8mb4 
COLLATE utf8mb4_general_ci;

-- 2. 切换到该数据库
USE `omni_db`;

-- 3. 创建用户表 (遵循你确认的 3NF 范式)
DROP TABLE IF EXISTS `u_user`;
CREATE TABLE `u_user` (
  `id` BIGINT NOT NULL COMMENT '分布式雪花ID',
  `email` VARCHAR(100) NOT NULL COMMENT '用户邮箱',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名称',
  `state` VARCHAR(50) NOT NULL COMMENT '用户状态',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email` (`email`) -- 核心：保证邮箱唯一，支撑你的 Lua 逻辑
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基本信息表';


DROP TABLE IF EXISTS `u_media`;
CREATE TABLE `u_media` (
  `id` BIGINT NOT NULL COMMENT '分布式雪花ID（视频主键）',
  `state` VARCHAR(50) NOT NULL COMMENT '视频状态',
  `title` VARCHAR(50) NOT NULL COMMENT '视频标题',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `user_id` BIGINT NOT NULL COMMENT '上传用户ID',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_state` (`state`),
  KEY `idx_title` (`title`),
  KEY `idx_create_time` (`create_time`),
  CONSTRAINT `fk_media_user` FOREIGN KEY (`user_id`) REFERENCES `u_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频信息表';


CREATE TABLE `u_user_search_history` (
  `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `keyword` varchar(100) NOT NULL COMMENT '搜索内容',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'CT',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'UT',
  -- 唯一索引：保证同一个用户搜同一个词只占一行，方便做 Upsert
  UNIQUE KEY `uk_user_keyword` (`user_id`, `keyword`),
  -- 检索索引：方便查询该用户最近搜索记录
  KEY `idx_user_ut` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

