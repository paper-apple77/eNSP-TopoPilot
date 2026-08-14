package com.topo.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备操作工具注册（LangChain4j Function Calling）
 *
 * 方法上的 @Tool 注解会被框架自动转成工具 schema 发给大模型，
 * AI 发起的工具调用由 DefaultToolExecutor 反射回这些方法执行。
 */
@Component
public class ToolRegistry {

    private final TelnetService telnetService;

    public ToolRegistry(TelnetService telnetService) {
        this.telnetService = telnetService;
    }

    @Tool(name = "queryDeviceInfo", value = "查询设备型号/版本信息（快速），用于识别设备。device_name 为拓扑中的设备名")
    public String queryDeviceInfo(@P("拓扑中的设备名") String device_name) {
        return telnetService.queryDeviceInfo(device_name);
    }

    @Tool(name = "queryCurrentConfig", value = "查询设备当前完整配置（缓存优先，无缓存才实时查询）。输出很大，仅在确实需要全量配置时调用。device_name 为拓扑中的设备名")
    public String queryCurrentConfig(@P("拓扑中的设备名") String device_name) {
        String cached = telnetService.getCachedConfig(device_name);
        if (cached != null && !cached.isBlank() && !cached.startsWith("[错误]")) return cached;
        return telnetService.queryCurrentConfig(device_name);
    }

    @Tool(name = "sendConfig", value = "批量推送配置命令到设备并返回回显。commands 数组第一条必须是 system-view，最后一条必须是 return（禁止 quit 结尾）。device_name 为拓扑中的设备名")
    public String sendConfig(@P("拓扑中的设备名") String device_name, @P("配置命令数组") List<String> commands) {
        StringBuilder batch = new StringBuilder();
        for (String cmd : commands) {
            cmd = fixCommandSpacing(cmd.trim());
            if (cmd.isEmpty() || cmd.startsWith("#") || cmd.startsWith("!")) continue;
            batch.append(cmd).append("\n");
        }
        String result = telnetService.sendCommands(device_name, batch.toString());
        return result.isBlank() ? "推送完成(无回显)" : result;
    }

    @Tool(name = "sendCommand", value = "在设备上执行单条查询/验证命令，如 display ip interface brief、display vlan、display ospf peer brief。device_name 为拓扑中的设备名，command 为完整命令")
    public String sendCommand(@P("拓扑中的设备名") String device_name, @P("完整命令") String command) {
        return telnetService.sendCommand(device_name, command);
    }

    /** 修复 AI 命令的常见粘连错误（空格丢失），全项目唯一入口 */
    public String fixCommandSpacing(String cmd) {
        return cmd
            .replaceAll("(?i)ipaddress(\\d)", "ip address $1")           // ipaddress192.168
            .replaceAll("(?i)address(\\d)", "address $1")                // ip address192.168
            .replaceAll("(?i)^sysname(\\S)", "sysname $1")               // sysnameAR1
            .replaceAll("(?i)^interface(Gigabit)", "interface $1")        // interfaceGigabit
            .replaceAll("(?i)undoshutdown", "undo shutdown")              // undoshutdown
            .replaceAll("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(\\d{1,3}\\.)", "$1 $2") // IP粘连
            .replaceAll("(?i)iproute-static(\\d)", "ip route-static $1")  // iproute-static192
            .replaceAll("(?i)firewallzone\\h*", "firewall zone ")         // firewallzoneGigabit / firewallzone Gigabit
            .replaceAll("(?i)addinterface\\h*", "add interface ")
            .replaceAll("(?i)^ospf(\\d)", "ospf $1")
            .replaceAll("(?i)^acl(\\d)", "acl $1")
            .replaceAll("(?i)portlink-type\\h*", "port link-type ")
            .replaceAll("(?i)portdefaultvlan\\h*", "port default vlan ")
            .replaceAll("(?i)porttrunkallow-pass\\h*", "port trunk allow-pass ")
            .replaceAll("(?i)vlanbatch\\h*", "vlan batch ");
    }

    /** 剥离回复中的 tool_call JSON 块（兼容旧格式回复，避免存入对话历史浪费 token） */
    public String stripToolCallBlocks(String text) {
        if (text == null) return null;
        // 先移除 ```json 围栏块，再移除裸 JSON 对象（extractAllToolCalls 可精确找到括号范围）
        String cleaned = text.replaceAll("(?s)```json\\s*\\{[^`]*?\"tool_call\"[^`]*?```", "");
        for (String call : extractAllToolCalls(cleaned)) {
            cleaned = cleaned.replace(call, "");
        }
        return cleaned;
    }

    /** 从旧格式回复中提取所有 {"tool_call":...} JSON 对象（兼容用） */
    public List<String> extractAllToolCalls(String aiOutput) {
        List<String> result = new ArrayList<>();
        if (aiOutput == null) return result;
        int searchFrom = 0;
        while (true) {
            int start = aiOutput.indexOf("\"tool_call\"", searchFrom);
            if (start < 0) break;
            int braceStart = aiOutput.lastIndexOf('{', start);
            if (braceStart < 0 || braceStart < searchFrom) { searchFrom = start + 1; continue; }
            int depth = 0, braceEnd = -1;
            for (int i = braceStart; i < aiOutput.length(); i++) {
                if (aiOutput.charAt(i) == '{') depth++;
                else if (aiOutput.charAt(i) == '}') { depth--; if (depth == 0) { braceEnd = i + 1; break; } }
            }
            if (braceEnd < 0) break;
            result.add(aiOutput.substring(braceStart, braceEnd));
            searchFrom = braceEnd;
        }
        return result;
    }
}
