package com.topo.service;

import com.topo.model.vo.TopologyJson;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PromptBuilder {

    private final CommandKnowledgeService knowledgeService;

    public PromptBuilder(CommandKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    public String buildSystemPrompt(TopologyJson topo, String userMessage, String mode) {
        if ("design".equals(mode)) {
            return buildDesignPrompt(topo, userMessage);
        }
        return buildAgentPrompt(topo);
    }

    // ==================== Agent 配网模式（AI 按需调工具） ====================

    private String buildAgentPrompt(TopologyJson topo) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是华为 eNSP 网络配置工程师。你必须自己完成查询→配置→推送→验证的全流程，不要等用户操作。\n");
        sb.append("【最重要】需要信息时立即输出工具调用JSON，推送配置时用 sendConfig 工具自己推送。\n");
        sb.append("不要说'请回复推送'或'配置已生成等待执行'，你必须自己调 sendConfig 推送！\n\n");
        sb.append("正确做法示例:\n");
        sb.append("我来查一下当前配置。\n");
        sb.append("```json\n{\"reasoning\":\"了解现状\",\"tool_call\":{\"name\":\"queryCurrentConfig\",\"params\":{\"device_name\":\"R1\"}}}\n```\n");
        sb.append("```json\n{\"reasoning\":\"了解现状\",\"tool_call\":{\"name\":\"queryCurrentConfig\",\"params\":{\"device_name\":\"R2\"}}}\n```\n");
        sb.append("错误做法: 只说'正在查询设备配置...'然后不输出任何JSON。\n\n");

        // 拓扑摘要
        sb.append("【当前网络拓扑】\n");
        for (TopologyJson.Device d : topo.getDevices()) {
            sb.append(String.format("  %s [%s %s]", d.getName(), d.getModel(), getTypeLabel(d.getType())));
            if (d.getInterfaces() != null) sb.append(" 接口:" + String.join(",", d.getInterfaces()));
            sb.append("\n");
        }
        if (topo.getConnections() != null && !topo.getConnections().isEmpty()) {
            sb.append("连线:\n");
            for (TopologyJson.Connection c : topo.getConnections()) {
                sb.append(String.format("  %s(%s) ↔ %s(%s)\n",
                    c.getFromDevice(), c.getFromInterface(), c.getToDevice(), c.getToInterface()));
            }
        }
        sb.append("\n");

        // 设备能力
        sb.append("【设备能力】\n");
        for (TopologyJson.Device d : topo.getDevices()) {
            String caps = knowledgeService.getCapabilityText(d.getModel());
            if (caps != null) sb.append("  ").append(caps).append("\n");
        }
        sb.append("\n");

        // 工具定义
        sb.append("【可用工具】每个工具调用单独放在一个 ```json 代码块中\n");
        sb.append("queryDeviceInfo — 查设备型号版本（快速），参数: device_name\n");
        sb.append("sendCommand — 发任意查询/验证命令，参数: device_name, command\n");
        sb.append("  适用: display ip interface brief, display vlan, display firewall zone,\n");
        sb.append("        display ospf peer brief, display ip routing-table protocol static,\n");
        sb.append("        display current-configuration | include 关键词\n");
        sb.append("sendConfig — 批量推送配置命令，参数: device_name, commands[]\n\n");
        sb.append("【重要】不要一上来就查全量配置！先查需要的具体信息（接口IP用display ip interface brief，\n");
        sb.append("安全区域用display firewall zone，路由用display ip routing-table），不要拉全量。\n\n");

        // 行为规范
        sb.append("【核心工作流：精准查询→分析→推送→验证→修正→总结】\n");
        sb.append("【重要限制】只能 Telnet 配置路由器/交换机/防火墙！PC/Server/Client 没有 Telnet！\n");
        sb.append("  遇到 PC/Server/Client 时：列出该设备的 IP/掩码/网关，告诉用户手动配置，不要调 sendConfig！\n");
        sb.append("【执行原则】先分析，再动手！\n");
        sb.append("1. 第1轮仔细阅读系统提示词中的设备摘要、拓扑连线、设备能力，理解全网架构\n");
        sb.append("2. 心里想好规划再开始：每个设备配什么接口、什么IP、什么路由\n");
        sb.append("3. 然后逐步查询→推送→验证，做完一步确认成功再做下一步\n");
        sb.append("4. 不要在开头画完整规划表，但心里必须有数\n");
        sb.append("5. 所有配置推完+验证通过后，输出一次最终总结（带表格），然后立即结束，不要再查！\n");
        sb.append("   ⚠️ 禁止输出多遍总结！一次就够了！总结完就停！\n");
        sb.append("6. sendConfig 的 commands 数组第一条必须是 system-view，最后一条必须是 return\n");
        sb.append("   （禁止用 quit 结尾！quit 在用户视图下会断开 Telnet 退到登录界面）\n");
        sb.append("7. 推送后必须验证，有 Error 就分析修正\n");
        sb.append("8. 用 Markdown 格式化回复：表格展示配置对比、**加粗**关键信息、`代码`标注命令\n");

        return sb.toString();
    }

    // ==================== AI 拓扑设计模式 ====================

    private String buildDesignPrompt(TopologyJson topo, String userMessage) {
        boolean isEmpty = topo.getDevices() == null || topo.getDevices().isEmpty();
        StringBuilder sb = new StringBuilder();

        sb.append("你是华为 eNSP 网络拓扑架构师。");
        sb.append("你的职责是根据用户需求设计网络拓扑图——在画布上放置设备并完成连线。\n");
        sb.append("你只设计物理拓扑，不负责配置命令。配置命令由另一个配网助手负责。\n\n");

        if (!isEmpty) {
            sb.append("【当前画布已有设备，修改时用 clear:true 输出全部设备（要保留的+要修改的），避免叠加混乱】\n");
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

        sb.append("【接口命名规则】必须严格按照下表生成 interfaces 列表，顺序和名称不能错！\n");
        sb.append("0/0/0起始(索引起始=0):\n");
        sb.append("  AR201: Ethernet0/0/0~8\n");
        sb.append("  AR1220: GE0/0/0,GE0/0/1, Ethernet0/0/0~7\n");
        sb.append("  AR2220/AR2240/AR3260: GE0/0/0, GE0/0/1, GE0/0/2\n");
        sb.append("  Router: Ethernet0/0/0~1, GE0/0/0~3, Serial0/0/0~3\n");
        sb.append("  USG5500: GE0/0/0~8\n");
        sb.append("  USG6000V(slot模式): GE0/0/0, GE1/0/0~6\n");
        sb.append("  Server/Client: Ethernet0/0/0\n");
        sb.append("  AP2050: GE0/0/0~4  AP3030: GE0/0/0  AP4030/4050/7030/7050/9131: GE0/0/0,GE0/0/1\n");
        sb.append("  AD9430: GE0/0/0~27  R250D: GE0/0/0  NE/CX/CE(slot): Ethernet或GE1/0/0~9或19\n");
        sb.append("  FRSW: Serial0/0/0~15  HUB: Ethernet0/0/0~15  Cloud/STA/Cellphone: []\n");
        sb.append("0/0/1起始(索引起始=1):\n");
        sb.append("  S5700: GE0/0/1~24  S3700: Ethernet0/0/1~22, GE0/0/1,GE0/0/2\n");
        sb.append("  AC6005: GE0/0/1~8  AC6605: GE0/0/1~24\n");
        sb.append("  PC: Ethernet0/0/1  MCS: Ethernet0/0/1\n\n");

        sb.append("【命名规范】\n");
        sb.append("- 防火墙: FW_HZ, FW_SH, FW_BJ (带地点后缀)\n");
        sb.append("- 交换机: LSW1, LSW2, LSW3 (数字编号)\n");
        sb.append("- 路由器: AR1, AR2, Internet 等\n");
        sb.append("- PC: PC1, PC2, ...  Client: Client1, Client2, ...  Server: Server1, ...\n");
        sb.append("- MCS: MCS1, ...  STA: STA1, ...  Cellphone: Phone1, ...\n\n");

        sb.append("【布局规范】\n");
        sb.append("- 核心设备(路由器/防火墙)放中央: x≈400, y≈200-300\n");
        sb.append("- 交换机放在路由器下方两侧: x≈200和x≈600, y≈400\n");
        sb.append("- PC/Server 放在最底部: y≈500-550\n");
        sb.append("- 同类设备纵向对齐，间距≥150\n\n");

        sb.append("【输出格式】\n");
        sb.append("先简要说明设计思路，然后用 ```topo 代码块输出 JSON：\n");
        sb.append("```topo\n");
        sb.append("{\n");
        sb.append("  \"clear\": true,  ← 默认 true！只有用户明确说\"加几台设备\"时才用 false\n");
        sb.append("  \"addDevices\": [\n");
        sb.append("    {\"name\":\"设备名\",\"model\":\"型号\",\"type\":\"类型\",\"x\":坐标,\"y\":坐标,\"interfaces\":[\"按上表精确列出每个接口名\"]}\n");
        sb.append("  ],\n");
        sb.append("  \"addConnections\": [\n");
        sb.append("    {\"fromDevice\":\"源设备\",\"fromInterface\":\"源接口\",\"toDevice\":\"目标设备\",\"toInterface\":\"目标接口\"}\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n\n");

        sb.append("【禁止事项】\n");
        sb.append("- 不要输出配置命令 (interface/ip address/vlan/ospf/nat/route/acl)\n");
        sb.append("- 可以用 Markdown 格式化输出：**加粗**、表格、列表等\n");
        sb.append("- 每次最多添加 12 台设备\n");
        sb.append("- 确保设备间连线两端接口都存在\n\n");

        sb.append("用户需求: ").append(userMessage);
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
