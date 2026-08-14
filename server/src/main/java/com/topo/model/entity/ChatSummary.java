package com.topo.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * AI 对话记忆摘要 — 对应 tb_chat_summary 表
 *
 * 内存历史超过保留上限被淘汰的最旧几轮，由 AI 压缩成要点摘要存这里，
 * 下轮对话时注入 system prompt，让 AI 记住久远的上下文。
 * 每用户每拓扑每模式一条（唯一键），新摘要合并旧摘要后覆盖。
 */
@Data
@TableName("tb_chat_summary")
public class ChatSummary {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;       // 所属用户

    private Long topologyId;   // 关联拓扑（0=通用对话）

    private String mode;       // agent=配网, design=设计

    private String summary;    // 摘要文本

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
