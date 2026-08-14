package com.topo.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 拓扑保存请求
 *
 * id 为空 → 新建；id 有值 → 覆盖保存（仅限本人拓扑）
 */
@Data
public class TopologySaveRequest {
    /** 拓扑 ID，新建时为空 */
    private Long id;

    @NotBlank(message = "拓扑名称不能为空")
    private String name;

    @NotBlank(message = "拓扑数据不能为空")
    private String topologyJson;

    /** 来源：ensp_topo_file / screenshot / manual */
    private String sourceType;
}
