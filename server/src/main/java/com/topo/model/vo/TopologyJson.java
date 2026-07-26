package com.topo.model.vo;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 拓扑结构化数据 — 前端画布和后端统一使用这个格式
 *
 * 从 .topo 文件解析出的结果、用户手动编辑的画布数据、
 * AI 对话时注入 System Prompt 的上下文，都用这个结构。
 */
@Data
public class TopologyJson {
    /** 设备列表 */
    private List<Device> devices;
    /** 连线列表 */
    private List<Connection> connections;
    /** 设备名 → 已配置命令（来自 .cfg 文件，可选） */
    private Map<String, String> deviceConfigs;

    /**
     * 设备节点
     */
    @Data
    public static class Device {
        private String id;
        private String name;
        private String model;
        private String type;
        private double x;
        private double y;
        private int comPort;       // Console 端口号（来自 .topo com_port 属性）
        private String settings;    // PC/Client IP配置(-simpc_ip/-simpc_mask/-simpc_gateway)
        private List<String> interfaces;
    }

    /**
     * 连线（设备间连接关系）
     */
    @Data
    public static class Connection {
        private String fromDevice;    // 源设备名
        private String fromInterface; // 源接口名（如 GE1/0/2）
        private String toDevice;      // 目标设备名
        private String toInterface;   // 目标接口名
        private String label;         // 可选：连线标注（如 "GRE Tunnel"）
    }
}
