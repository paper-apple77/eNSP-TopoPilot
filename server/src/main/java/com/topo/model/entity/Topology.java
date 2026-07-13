package com.topo.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 拓扑实体 — 对应 tb_topology 表
 *
 * topologyJson 存完整拓扑结构（MySQL JSON 类型）：
 *   { devices: [...], connections: [...] }
 *
 * 用 JSON 字段而不是拆表的原因：
 *   拓扑是图结构，设备和连线数量不固定，拆表会导致大量 join
 */
@Data
@TableName("tb_topology")
public class Topology {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;          // 归属用户

    private String name;          // 拓扑名称

    private String topologyJson;  // 拓扑结构 JSON 字符串

    /**
     * 来源：ensp_topo_file（.topo 文件导入）
     *       screenshot（截图识别）
     *       manual（手动绘制）
     */
    private String sourceType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
