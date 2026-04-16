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