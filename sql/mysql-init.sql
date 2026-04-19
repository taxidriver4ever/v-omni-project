-- =============================================================================
-- VOmni 数据库初始化脚本 (High Performance Version)
-- =============================================================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS `omni_db` 
DEFAULT CHARACTER SET utf8mb4 
COLLATE utf8mb4_general_ci;

USE `omni_db`;

-- -----------------------------------------------------------------------------
-- 表 1：用户基本信息表
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `u_user`;
CREATE TABLE `u_user` (
  `id` BIGINT NOT NULL COMMENT '分布式雪花ID',
  `email` VARCHAR(100) NOT NULL COMMENT '用户邮箱',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名称',
  `state` VARCHAR(50) NOT NULL COMMENT '用户状态',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基本信息表';

-- -----------------------------------------------------------------------------
-- 表 2：视频信息表
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `u_media`;
CREATE TABLE `u_media` (
  `id` BIGINT NOT NULL COMMENT '分布式雪花ID',
  `user_id` BIGINT NOT NULL COMMENT '上传用户ID',
  `title` VARCHAR(100) NOT NULL COMMENT '视频标题',
  `state` VARCHAR(50) NOT NULL COMMENT '视频状态(如: PUBLISHED, AUDITING)',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 优化：支撑个人主页视频列表查询 (过滤删除内容并按时间排序)
  KEY `idx_user_list` (`user_id`, `deleted`, `create_time`),
  -- 优化：支撑首页瀑布流召回 (过滤状态与删除，走覆盖索引)
  KEY `idx_display_flow` (`deleted`, `state`, `create_time`),
  -- 优化：支持标题搜索
  KEY `idx_title` (`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频信息表';

-- -----------------------------------------------------------------------------
-- 表 3：用户搜索历史表
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `u_user_search_history`;
CREATE TABLE `u_user_search_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `keyword` VARCHAR(100) NOT NULL COMMENT '搜索内容',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 核心：支持 ON DUPLICATE KEY UPDATE 逻辑，实现搜索词频次更新
  UNIQUE KEY `uk_user_keyword` (`user_id`, `keyword`),
  -- 优化：支持前端展示该用户最近的搜索记录
  KEY `idx_user_recent` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户搜索历史表';

-- -----------------------------------------------------------------------------
-- 表 4：用户点赞记录表
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `u_user_like`;
CREATE TABLE `u_user_like` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `media_id` BIGINT NOT NULL COMMENT '视频ID',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0-有效, 1-取消',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 核心：业务幂等性索引
  UNIQUE KEY `uk_user_media` (`user_id`, `media_id`),
  -- 优化：支撑视频详情页获取点赞总数
  KEY `idx_media_count` (`media_id`, `deleted`),
  -- 优化：支撑 Agent 增量扫描用户近期“变心”行为
  KEY `idx_agent_profiling` (`update_time`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户点赞记录表';

-- -----------------------------------------------------------------------------
-- 表 5：用户收藏记录表
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `u_user_collection`;
CREATE TABLE `u_user_collection` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `media_id` BIGINT NOT NULL,
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_media` (`user_id`, `media_id`),
  -- 优化：支持查询个人收藏夹列表
  KEY `idx_user_collect_list` (`user_id`, `deleted`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏记录表';

-- -----------------------------------------------------------------------------
-- 表 6：视频评论表
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `u_video_comment`;
CREATE TABLE `u_video_comment` (
  `id` BIGINT NOT NULL,
  `media_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `content` VARCHAR(1000) NOT NULL,
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 核心优化：支撑视频下方评论列表的分页展示 (ICP优化)
  KEY `idx_media_comment_pagination` (`media_id`, `deleted`, `create_time`),
  -- 优化：方便 Agent 分析用户的评论语料进行画像
  KEY `idx_user_comment_corpus` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频评论表';
