-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS `omni_db` 
DEFAULT CHARACTER SET utf8mb4 
COLLATE utf8mb4_general_ci;

USE `omni_db`;

-- 1. 用户表
DROP TABLE IF EXISTS `u_user`;
CREATE TABLE `u_user` (
  `id` BIGINT NOT NULL COMMENT '分布式雪花ID',
  `email` VARCHAR(100) NOT NULL COMMENT '用户邮箱',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名称',
  `state` VARCHAR(50) NOT NULL COMMENT '状态',
  `create_time` DATETIME(3) NOT NULL COMMENT '毫秒级时间',
  `update_time` DATETIME(3) NOT NULL COMMENT '毫秒级时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基本信息表';

-- 2. 视频表
DROP TABLE IF EXISTS `u_media`;
CREATE TABLE `u_media` (
  `id` BIGINT NOT NULL COMMENT '分布式雪花ID',
  `user_id` BIGINT NOT NULL COMMENT '上传者ID',
  `title` VARCHAR(100) NOT NULL,
  `state` VARCHAR(50) NOT NULL,
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  `create_time` DATETIME(3) NOT NULL,
  `update_time` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_list` (`user_id`, `deleted`, `create_time`),
  KEY `idx_display_flow` (`deleted`, `state`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频信息表';

-- 3. 搜索历史表
DROP TABLE IF EXISTS `u_search_history`;
CREATE TABLE `u_search_history` (
  `id` BIGINT NOT NULL COMMENT '分布式雪花ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `keyword` VARCHAR(100) NOT NULL COMMENT '搜索内容',
  `create_time` DATETIME(3) NOT NULL,
  `update_time` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_keyword` (`user_id`, `keyword`),
  KEY `idx_user_recent` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户搜索历史表';

-- 4. 视频点赞表 (物理删除)
DROP TABLE IF EXISTS `u_like`;
CREATE TABLE `u_like` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `media_id` BIGINT NOT NULL COMMENT '视频ID',
  `create_time` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_media` (`user_id`, `media_id`),
  KEY `idx_media_id` (`media_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户点赞记录表';

-- 5. 视频收藏表 (物理删除)
DROP TABLE IF EXISTS `u_collection`;
CREATE TABLE `u_collection` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `media_id` BIGINT NOT NULL,
  `create_time` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_media` (`user_id`, `media_id`),
  KEY `idx_user_recent` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏记录表';

-- 6. 视频评论表 (逻辑删除)
DROP TABLE IF EXISTS `u_comment`;
CREATE TABLE `u_comment` (
  `id` BIGINT NOT NULL COMMENT '评论雪花ID',
  `media_id` BIGINT NOT NULL COMMENT '视频ID',
  `user_id` BIGINT NOT NULL COMMENT '发评论的人',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父评论ID',
  `root_id` BIGINT NOT NULL DEFAULT 0 COMMENT '根评论ID',
  `content` VARCHAR(1000) NOT NULL,
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  `create_time` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  -- 索引中包含 create_time(3) 保证分页排序性能
  KEY `idx_media_parent` (`media_id`, `parent_id`, `deleted`, `create_time`),
  KEY `idx_root_flow` (`root_id`, `deleted`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频评论表';

-- 7. 评论点赞表 (物理删除)
DROP TABLE IF EXISTS `u_comment_like`;
CREATE TABLE `u_comment_like` (
  `id` BIGINT NOT NULL,
  `comment_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `create_time` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_user` (`comment_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论点赞关联表';
