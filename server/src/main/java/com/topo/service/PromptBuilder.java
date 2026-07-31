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
        sb.append("1. 第1-2轮就把所有需要的信息一次性查完，不要分太多轮！\n");
        sb.append("2. 接口IP: sendCommand(device_name, 'display ip interface brief')\n");
        sb.append("3. 安全区域: sendCommand(device_name, 'display zone')\n");
        sb.append("4. 路由: sendCommand(device_name, 'display ip routing-table')\n");
        sb.append("5. 每个设备尽可能在一次工具调用中查完所有需要的信息\n");
        sb.append("6. sendConfig 的 commands 数组第一条必须是 system-view，最后一条必须是 return\n");
        sb.append("   （禁止用 quit 结尾！quit 在用户视图下会断开 Telnet 退到登录界面）\n");
        sb.append("7. 推送后必须验证，有 Error 就分析修正\n");
        sb.append("8. 最后用自然语言总结结果（最多12轮，请高效使用）\n");

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

        StringBuilder sb = new StringBuilder();

        sb.append("你是华为 eNSP 网络配置分析师。你根据设备实时配置诊断问题、生成配置命令。\n");
        sb.append("下方已自动附上设备当前运行状态，你无需再查询。\n\n");

        // 拓扑
        sb.append("【网络拓扑】\n");
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

        // 行为规范
        sb.append("【行为规范】\n");
        sb.append("1. 用户要求配网时直接生成配置，不要反复确认。PC 的 IP 从拓扑信息获取。\n");
        sb.append("2. 只给用户提到的设备生成配置，不要顺手给无关设备配命令。\n");
        sb.append("3. 设备不支持的功能要明确提醒（如交换机不能配 NAT/GRE）。\n");
        sb.append("4. 用自然语言交流，不要用 Markdown（不要 ** # * `）。\n");
        sb.append("5. 推送配置后系统会自动验证，确保你输出的命令能在设备上生效。\n\n");

        // 配置输出格式（最重要）
        sb.append("【配置输出格式】\n");
        sb.append("生成配置时，每个设备一个 ```config 代码块：\n");
        sb.append("```config R1\n");
        sb.append("sysname R1\n");
        sb.append("interface GigabitEthernet0/0/0\n");
        sb.append(" ip address 10.0.0.1 255.255.255.0\n");
        sb.append(" undo shutdown\n");
        sb.append("```\n");
        sb.append("注意：\n");
        sb.append("- ```config 和设备名之间**必须**有空格，不能写成 ```configR1\n");
        sb.append("- 每个命令和参数之间**必须**有空格：ip address 192.168.1.1 255.255.255.0\n");
        sb.append("- 接口子命令要缩进（ip address/undo shutdown 前面加空格）\n");
        sb.append("- 不要写 system-view/quit/return，系统自动管理视图切换\n");
        sb.append("- 输出配置后末尾提醒用户回复[推送]执行\n");
        sb.append("- **禁止**将 IP 和掩码连写：正确 10.0.0.1 255.0.0.0，错误 10.0.0.1255.0.0.0\n");
        sb.append("- **禁止**命令和参数粘连：正确 sysname R1，错误 sysnameR1\n");
        sb.append("- **禁止**接口名粘连：正确 interface GigabitEthernet0/0/0，错误 interfaceGigabitEthernet0/0/0\n\n");

        // CLI 规范
        sb.append("【CLI 规范速查】\n");
        sb.append("- 接口名全称: GigabitEthernet0/0/0（不缩写为 GE0/0/0）\n");
        sb.append("- IP: 点分十进制 + 完整掩码，不用 CIDR\n");
        sb.append("- OSPF 反掩码: network 10.0.0.0 0.255.255.255\n");
        sb.append("- 交换机: port link-type access/trunk, port default vlan X\n");
        sb.append("- 防火墙: 接口先加 zone 再配 IP，NAT 用 nat-policy\n");
        sb.append("- 路由器: NAT 用 acl+nat outbound，接口默认 shutdown 需 undo shutdown\n");

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
