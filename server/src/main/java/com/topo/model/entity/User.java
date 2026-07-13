package com.topo.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体 — 对应 tb_user 表
 *
 * @TableLogic 逻辑删除：deleteById 实际执行 UPDATE SET deleted=1
 *             查询时自动过滤 deleted=1 的记录
 */
@Data
@TableName("tb_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;       // 邮箱即账号

    private String password;    // MD5 加密存储

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;    // 0=正常, 1=已删除
}
