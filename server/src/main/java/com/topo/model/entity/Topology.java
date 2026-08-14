package com.topo.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 拓扑实体 — 对应 tb_topology 表
 *
 * 画布拓扑（设备+连线）的 MySQL 持久化存储：
 * topologyJson 直接存 TopologyJson 序列化字符串（MySQL JSON 列），
 * 每个用户可保存多个拓扑，删除走逻辑删除。
 */
@Data
@TableName("tb_topology")
public class Topology {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;         // 所属用户

    private String name;         // 拓扑名称

    private String topologyJson; // 画布结构 JSON（设备/连线/接口）

    private String sourceType;   // 来源: ensp_topo_file / screenshot / manual

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;     // 0=正常, 1=已删除
}
