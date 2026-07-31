package com.topo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AI 工具注册表 — 定义 AI 可以调用的工具
 *
 * 工具定义注入到 System Prompt，AI 通过结构化输出决定调用哪个工具。
 * ChatService 解析 AI 输出的 function_call，执行对应工具，返回结果。
 */
@Component
public class ToolRegistry {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TelnetService telnetService;

    public ToolRegistry(TelnetService telnetService) {
        this.telnetService = telnetService;
    }

    /**
     * 工具名 → 执行器
     */
    public interface ToolExecutor {
        String execute(Map<String, Object> params);
    }

    private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();
    private boolean initialized = false;

    public void init(ConfigValidator validator) {
        if (initialized) return;
        initialized = true;

        // 工具 1: 查询设备信息
        executors.put("queryDeviceInfo", params -> {
            String devName = (String) params.get("device_name");
            return telnetService.queryDeviceInfo(devName);
        });

        // 工具 2: 查询设备当前配置（优先缓存）
        executors.put("queryCurrentConfig", params -> {
            String devName = (String) params.get("device_name");
            String cached = telnetService.getCachedConfig(devName);
            if (cached != null && !cached.isBlank() && !cached.startsWith("[错误]")) return cached;
            return telnetService.queryCurrentConfig(devName);
        });

        // 工具 3: 查询可用命令
        executors.put("queryAvailableCommands", params -> {
            String devName = (String) params.get("device_name");
            String view = (String) params.getOrDefault("view", "system-view");
            return telnetService.sendCommand(devName, view + " ?");
        });

        // 工具 4: 检查命令是否存在
        executors.put("checkCommand", params -> {
            String devName = (String) params.get("device_name");
            String cmd = (String) params.get("command");
            String r = telnetService.sendCommand(devName, cmd + " ?");
            return r.contains("Error") || r.contains("Unrecognized") ? "命令不存在" : "命令存在";
        });

        // 工具 5: 发送配置命令（原样发送，AI 自己管理视图，有报错自己修正）
        executors.put("sendConfig", params -> {
            String devName = (String) params.get("device_name");
            @SuppressWarnings("unchecked")
            List<String> commands = (List<String>) params.get("commands");
            StringBuilder batch = new StringBuilder();
            for (String cmd : commands) {
                cmd = fixCommandSpacing(cmd.trim());
                if (cmd.isEmpty() || cmd.startsWith("#") || cmd.startsWith("!")) continue;
                batch.append(cmd).append("\n");
            }
            String result = telnetService.sendCommands(devName, batch.toString());
            return result.isBlank() ? "推送完成(无回显)" : result.substring(0, Math.min(800, result.length()));
        });

        // 工具 6: 发送单条命令
        executors.put("sendCommand", params -> {
            String devName = (String) params.get("device_name");
            String cmd = (String) params.get("command");
            return telnetService.sendCommand(devName, cmd);
        });

    }

    private String fixCommandSpacing(String cmd) {
        return cmd
            .replaceAll("(?i)ipaddress(\\d)", "ip address $1")
            .replaceAll("(?i)^sysname(\\S)", "sysname $1")
            .replaceAll("(?i)^interface(Gigabit)", "interface $1")
            .replaceAll("(?i)undoshutdown", "undo shutdown")
            .replaceAll("(?i)firewallzone", "firewall zone ")
            .replaceAll("(?i)addinterface", "add interface ")
            .replaceAll("(?i)^ospf(\\d)", "ospf $1")
            .replaceAll("(?i)^acl(\\d)", "acl $1")
            .replaceAll("(?i)portlink-type", "port link-type ")
            .replaceAll("(?i)portdefaultvlan", "port default vlan ")
            .replaceAll("(?i)porttrunkallow-pass", "port trunk allow-pass ")
            .replaceAll("(?i)vlanbatch", "vlan batch ");
    }

    private String sanitizeResult(String result) {
        if (result == null || result.isBlank()) return "OK";
        if (result.contains("Error") || result.contains("Unrecognized")) return "FAIL:" + result.substring(0, Math.min(80, result.length()));
        return "OK";
    }

    /** 执行工具调用 */
    public String execute(String toolName, Map<String, Object> params) {
        ToolExecutor executor = executors.get(toolName);
        if (executor == null) return "未知工具: " + toolName;
        try {
            return executor.execute(params);
        } catch (Exception e) {
            return "工具执行异常: " + e.getMessage();
        }
    }

    /** 提取所有工具调用（支持一次返回多个） */
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

    /** 检查响应中是否包含工具调用（兼容旧接口） */
    public String extractToolCall(String aiOutput) {
        List<String> all = extractAllToolCalls(aiOutput);
        return all.isEmpty() ? null : all.get(0);
    }
}
