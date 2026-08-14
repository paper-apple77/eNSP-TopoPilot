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

-- AI 对话历史表（重启后对话上下文不丢失）
CREATE TABLE IF NOT EXISTS tb_chat_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    topology_id BIGINT DEFAULT 0 COMMENT '关联拓扑(0=无)',
    mode VARCHAR(20) NOT NULL DEFAULT 'default' COMMENT '模式: agent=配网, design=设计',
    user_message TEXT NOT NULL COMMENT '用户消息',
    assistant_message TEXT NOT NULL COMMENT 'AI 回复(已剥离 tool_call JSON)',
    created_at DATETIME DEFAULT NOW(),
    INDEX idx_user_mode (user_id, mode, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话历史表';

-- AI 对话记忆摘要表（久远对话被 AI 压缩后存这里，注入下轮 system prompt）
CREATE TABLE IF NOT EXISTS tb_chat_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    topology_id BIGINT DEFAULT 0 COMMENT '关联拓扑(0=通用)',
    mode VARCHAR(20) NOT NULL DEFAULT 'default' COMMENT '模式: agent=配网, design=设计',
    summary TEXT NOT NULL COMMENT 'AI 生成的对话摘要',
    updated_at DATETIME DEFAULT NOW() ON UPDATE NOW(),
    UNIQUE KEY uk_user_topo_mode (user_id, topology_id, mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话记忆摘要表';
