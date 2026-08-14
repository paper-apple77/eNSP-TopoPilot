package com.topo.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * AI 对话历史实体 — 对应 tb_chat_history 表
 *
 * 每条记录是一次完整问答（用户消息 + AI 回复），按 userId+mode 分组。
 * 回复内容在入库前已剥离 tool_call JSON（见 ToolRegistry.stripToolCallBlocks）。
 * 超出保留上限的最旧记录由 ConversationHistory 物理删除。
 */
@Data
@TableName("tb_chat_history")
public class ChatHistory {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;       // 所属用户

    private Long topologyId;   // 关联拓扑（0=无）

    private String mode;       // agent=配网模式, design=设计模式

    private String userMessage;      // 用户消息

    private String assistantMessage; // AI 回复

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
