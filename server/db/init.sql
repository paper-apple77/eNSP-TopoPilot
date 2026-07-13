-- 创建数据库
CREATE DATABASE IF NOT EXISTS topo_assistant DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE topo_assistant;

-- 用户表
CREATE TABLE IF NOT EXISTS tb_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(100) NOT NULL UNIQUE COMMENT '邮箱',
    password VARCHAR(64) NOT NULL COMMENT 'MD5 加密密码',
    created_at DATETIME DEFAULT NOW(),
    updated_at DATETIME DEFAULT NOW() ON UPDATE NOW(),
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=正常, 1=已删除',
    INDEX idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 拓扑表
CREATE TABLE IF NOT EXISTS tb_topology (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    name VARCHAR(200) NOT NULL COMMENT '拓扑名称',
    topology_json JSON COMMENT '拓扑结构 JSON',
    source_type VARCHAR(30) DEFAULT 'manual' COMMENT '来源: ensp_topo_file/screenshot/manual',
    created_at DATETIME DEFAULT NOW(),
    updated_at DATETIME DEFAULT NOW() ON UPDATE NOW(),
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_user_id (user_id),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拓扑表';
