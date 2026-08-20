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
        sb.append("【最重要】需要信息时立即调用工具查询，推送配置时用 sendConfig 工具自己推送。\n");
        sb.append("不要说'请回复推送'或'配置已生成等待执行'，你必须自己调 sendConfig 推送！\n\n");

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
            if (caps != null) {
                sb.append("  ").append(caps).append("\n");
            } else {
                sb.append("  ").append(d.getName()).append(" [").append(d.getModel()).append("] 型号不在知识库 → ")
                  .append(defaultCapByType(d.getType())).append("（待现场验证）\n");
            }
        }
        sb.append("\n");

        // 工具通过 Function Calling 协议下发（工具定义见 ChatService），这里只给使用策略
        sb.append("【可用工具】queryDeviceInfo（查版本）、sendCommand（单条查询/验证）、\n");
        sb.append("sendConfig（批量推送配置）、queryCurrentConfig（全量配置）。\n");
        sb.append("【重要】不要一上来就查全量配置！先查需要的具体信息（接口IP用display ip interface brief，\n");
        sb.append("安全区域用display firewall zone，路由用display ip routing-table），不要拉全量。\n\n");

        // 行为规范
        sb.append("【核心工作流：精准查询→分析→推送→验证→修正→总结】\n");
        sb.append("【能力判定三阶梯】遇到知识库里没有的型号时，用下面的方法现场判定能力，不要反复犹豫：\n");
        sb.append("  1. 知识库有该型号 → 直接采信能力表\n");
        sb.append("  2. 没有 → 先按类型默认：交换机=纯二层(不能配接口IP/VLANIF)，路由器=三层全功能，防火墙=安全/NAT\n");
        sb.append("  3. 现场验证(最多2条命令)：display version 确认型号；再在 system-view 下敲 ? 看可用命令，\n");
        sb.append("     有 ip address / interface Vlanif 才算三层，没有就按纯二层处理\n");
        sb.append("  验证完立即基于结论继续配置，不要在能力问题上反复查询！\n");
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
        sb.append("9. 同一信息最多查询2次！查不到就基于现有信息推进，并说明你的假设\n");
        sb.append("10. 任务未全部完成时，每轮回复必须附带工具调用继续执行！\n");
        sb.append("    只输出计划或说明就结束回合会导致任务中断。全部配置推送并验证通过后，\n");
        sb.append("    才允许以纯文字输出最终总结。\n");

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

        sb.append("【命名规范】（优先级最高：即使历史对话、记忆摘要或 eNSP 默认命名不同，也必须遵守本规则）\n");
        sb.append("- 防火墙: FW1, FW2, FW3 (数字编号)。禁止使用 FW_HZ/FW_SH/FW_BJ 等地名后缀命名\n");
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

    /** 知识库无此型号时，按设备类型给默认能力模型（第2阶梯，AI 仍需现场验证） */
    private String defaultCapByType(String type) {
        return switch (type == null ? "" : type) {
            case "switch" -> "按交换机默认：纯二层（VLAN/trunk/STP），不能配接口IP和VLANIF";
            case "router" -> "按路由器默认：三层全功能（接口IP/静态路由/动态路由）";
            case "firewall" -> "按防火墙默认：安全区域/NAT/策略路由";
            default -> "能力未知，先 display version 确认型号再探测";
        };
    }
}
