package com.topo.model.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 拓扑视图对象
 *
 * 列表接口不含 topologyJson（省流量），详情接口才返回。
 */
@Data
public class TopologyVO {
    private Long id;
    private String name;
    private String sourceType;
    private int deviceCount;      // 设备数量（列表展示用）
    private LocalDateTime updatedAt;
    /** 仅详情接口返回 */
    private String topologyJson;
}
