package com.topo.service;

import com.topo.model.vo.TopologyJson;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class PromptBuilder {

    private final VectorSearchService vectorSearch;
    private final CommandKnowledgeService knowledgeService;
    private final ToolRegistry toolRegistry;

    public PromptBuilder(VectorSearchService vectorSearch,
                        CommandKnowledgeService knowledgeService,
                        ToolRegistry toolRegistry) {
        this.vectorSearch = vectorSearch;
        this.knowledgeService = knowledgeService;
        this.toolRegistry = toolRegistry;
    }

    public String buildSystemPrompt(TopologyJson topo, String userMessage, String mode) {
        if ("design".equals(mode)) {
            return buildDesignPrompt(topo, userMessage);
        }
        return buildConnectPrompt(topo, userMessage);
    }

    // ==================== AI 拓扑设计模式 ====================

    private String buildDesignPrompt(TopologyJson topo, String userMessage) {
        boolean isEmpty = topo.getDevices() == null || topo.getDevices().isEmpty();
        StringBuilder sb = new StringBuilder();

        sb.append("你是华为 eNSP 网络拓扑架构师。");
        sb.append("你的职责是根据用户需求设计网络拓扑图——在画布上放置设备并完成连线。\n");
        sb.append("你只设计物理拓扑，不负责配置命令。配置命令由另一个配网助手负责。\n\n");

        if (!isEmpty) {
            sb.append("【当前画布已有设备，请在现有基础上增量添加，不要重复已有设备】\n");
            for (TopologyJson.Device d : topo.getDevices()) {
                sb.append(String.format("- %s [%s] 坐标(%d,%d) 接口:%s\n",
                    d.getName(), d.getModel(), (int)d.getX(), (int)d.getY(),
                    d.getInterfaces() != null ? d.getInterfaces() : "无"));
            }
            if (topo.getConnections() != null && !topo.getConnections().isEmpty()) {
                sb.append("已有连线:\n");
                for (TopologyJson.Connection c : topo.getConnections()) {
                    sb.append(String.format("  %s(%s) ↔ %s(%s)\n",
                        c.getFromDevice(), c.getFromInterface(), c.getToDevice(), c.getToInterface()));
                }
            }
            sb.append("\n");
        }

        sb.append("【设备选型规范】\n");
        sb.append("- 防火墙: USG6000V，接口 GigabitEthernet1/0/0 ~ GigabitEthernet1/0/6\n");
        sb.append("- 交换机: S5700，接口 GigabitEthernet0/0/0 ~ GigabitEthernet0/0/23\n");
        sb.append("- 路由器: AR2220，接口 GigabitEthernet0/0/0 ~ GigabitEthernet0/0/3\n");
        sb.append("- PC: 接口 Ethernet0/0/0 和 GigabitEthernet0/0/1\n");
        sb.append("- Server/Client: 接口 Ethernet0/0/0\n\n");

        sb.append("【命名规范】\n");
        sb.append("- 防火墙: FW_HZ, FW_SH, FW_BJ (带地点后缀)\n");
        sb.append("- 交换机: LSW1, LSW2, LSW3 (数字编号)\n");
        sb.append("- 路由器: AR1, AR2, Internet 等\n");
        sb.append("- PC/Server: PC1, PC2, Server1\n\n");

        sb.append("【布局规范】\n");
        sb.append("- 核心设备(路由器/防火墙)放中央: x≈400, y≈200-300\n");
        sb.append("- 交换机放在路由器下方两侧: x≈200和x≈600, y≈400\n");
        sb.append("- PC/Server 放在最底部: y≈500-550\n");
        sb.append("- 同类设备纵向对齐，间距≥150\n\n");

        sb.append("【输出格式】\n");
        sb.append("先简要说明设计思路，然后用 ```topo 代码块输出 JSON：\n");
        sb.append("```topo\n");
        sb.append("{\n");
        sb.append("  \"addDevices\": [\n");
        sb.append("    {\"name\":\"设备名\",\"model\":\"型号\",\"type\":\"类型\",\"x\":坐标,\"y\":坐标,\"interfaces\":[\"接口列表\"]}\n");
        sb.append("  ],\n");
        sb.append("  \"addConnections\": [\n");
        sb.append("    {\"fromDevice\":\"源设备\",\"fromInterface\":\"源接口\",\"toDevice\":\"目标设备\",\"toInterface\":\"目标接口\"}\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n\n");

        sb.append("【禁止事项】\n");
        sb.append("- 不要输出配置命令 (interface/ip address/vlan/ospf/nat/route/acl)\n");
        sb.append("- 不要用 Markdown 格式（不要 ** # *）\n");
        sb.append("- 每次最多添加 6 台设备\n");
        sb.append("- 确保设备间连线两端接口都存在\n\n");

        sb.append("用户需求: ").append(userMessage);
        return sb.toString();
    }

    // ==================== eNSP 连接配网模式 ====================

    private String buildConnectPrompt(TopologyJson topo, String userMessage) {
        boolean isEmpty = topo.getDevices() == null || topo.getDevices().isEmpty();
        if (isEmpty) {
            return "当前没有连接任何设备。请先点击「连接 eNSP」扫描并连接设备，或点击「导入 .topo」上传拓扑文件。";
        }

        Set<String> deviceModels = new LinkedHashSet<>();
        for (TopologyJson.Device d : topo.getDevices()) {
            if (d.getModel() != null) deviceModels.add(d.getModel());
        }

        StringBuilder sb = new StringBuilder();

        sb.append("你是华为 eNSP 网络配置分析师。");
        sb.append("你的职责是分析设备当前配置、回答用户问题、协助诊断网络故障。\n");
        sb.append("所有已连接设备的当前配置已经自动查询并附在对话末尾，你无需再查询。\n\n");

        sb.append("【回答规范】\n");
        sb.append("- 用户要求配网时，直接生成配置命令推送，不要反复确认\n");
        sb.append("- PC 的 IP 配置已在拓扑信息中提供，不需要再问用户\n");
        sb.append("- PC 没有 Telnet 无法远程配置，不要在 ```config 块里包含 PC\n");
        sb.append("- 用自然语言回答，像网络工程师和同事交流一样\n");
        sb.append("- 用户问某设备配置时，基于提供的配置数据回答。如果配置显示为空或出厂默认，直接说该设备尚未配置。不要编造查询错误。\n");
        sb.append("- 设备不支持的功能要明确提醒（如交换机不能配 GRE）\n");
        sb.append("- 不要输出 JSON、不要用 Markdown（不要用 ** * # `）\n\n");

        sb.append("【配置推送格式】（重要！用户要求配网时使用此格式）\n");
        sb.append("当用户要求配置设备时，用 ```config 设备名 代码块输出要推送的命令：\n");
        sb.append("```config FW_HZ\n");
        sb.append("sysname FW_HZ\n");
        sb.append("interface GigabitEthernet1/0/1\n");
        sb.append(" ip address 192.168.1.1 255.255.255.0\n");
        sb.append("```\n");
        sb.append("系统不会自动推送。输出配置块后，在末尾说: 配置已生成，回复[推送]即可执行。\n");
        sb.append("重要：只为用户明确提到的设备生成配置块，不要给无关设备配命令。\n");
        sb.append("不要写 system-view/quit/return，系统自动处理。\n");
        sb.append("【格式警告】命令和参数之间必须有空格！sysname FW_HZ 不能写成 sysnameFW_HZ。\n");
        sb.append("IP 和掩码之间必须有空格！192.168.1.1 255.255.255.0 不能写成 192.168.1.1255.255.255.0。\n\n");

        sb.append("【当前网络拓扑】（用户可能用简称称呼设备，请智能匹配）\n");
        for (TopologyJson.Device d : topo.getDevices()) {
            String desc = getTypeLabel(d.getType());
            sb.append(String.format("- %s [%s %s]", d.getName(), d.getModel(), desc));
            if (d.getComPort() > 0) sb.append(" Console端口:" + d.getComPort());
            if (d.getInterfaces() != null) sb.append(" 接口:" + String.join(",", d.getInterfaces()));
            sb.append("\n");
        }
        if (topo.getConnections() != null && !topo.getConnections().isEmpty()) {
            sb.append("连线关系:\n");
            for (TopologyJson.Connection c : topo.getConnections()) {
                sb.append(String.format("  %s(%s) ↔ %s(%s)\n",
                    c.getFromDevice(), c.getFromInterface(), c.getToDevice(), c.getToInterface()));
            }
        }
        sb.append("\n");

        sb.append("【设备能力边界】\n");
        for (TopologyJson.Device d : topo.getDevices()) {
            String caps = knowledgeService.getCapabilityText(d.getModel());
            if (caps != null) sb.append("- ").append(caps).append("\n");
        }
        sb.append("\n");

        List<String> knowledge = vectorSearch.search(userMessage, 3, deviceModels);
        if (!knowledge.isEmpty()) {
            sb.append("【华为命令参考】\n");
            for (String k : knowledge) sb.append(k).append("\n");
            sb.append("\n");
        }

        sb.append("【华为 CLI 格式提醒】\n");
        sb.append("- 接口名写全: GigabitEthernet1/0/1（不是 GE1/0/1）\n");
        sb.append("- IP 格式: 192.168.1.1 255.255.255.0（不是 /24）\n");
        sb.append("- OSPF 用反掩码: network 192.168.1.0 0.0.0.255\n");
        sb.append("- 防火墙 NAT 用 nat-policy，路由器用 acl+nat outbound\n");

        return sb.toString();
    }

    private String getTypeLabel(String type) {
        if (type == null) return "";
        return switch (type) {
            case "firewall" -> "防火墙";
            case "switch" -> "交换机";
            case "router" -> "路由器";
            case "pc" -> "PC";
            case "server" -> "服务器";
            default -> type;
        };
    }
}
