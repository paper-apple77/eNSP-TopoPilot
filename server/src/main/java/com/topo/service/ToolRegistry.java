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

    public void init(CommandKnowledgeService knowledgeService, ConfigValidator validator) {
        if (initialized) return;
        initialized = true;

        // 工具 1: 查询设备信息
        executors.put("queryDeviceInfo", params -> {
            String devName = (String) params.get("device_name");
            return telnetService.queryDeviceInfo(devName);
        });

        // 工具 2: 查询设备当前配置
        executors.put("queryCurrentConfig", params -> {
            String devName = (String) params.get("device_name");
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

        // 工具 5: 发送配置命令
        executors.put("sendConfig", params -> {
            String devName = (String) params.get("device_name");
            @SuppressWarnings("unchecked")
            List<String> commands = (List<String>) params.get("commands");
            StringBuilder sb = new StringBuilder();
            for (String cmd : commands) {
                String result = telnetService.sendCommand(devName, cmd);
                sb.append("[").append(sanitizeResult(result)).append("] ");
            }
            return sb.toString().strip();
        });

        // 工具 6: 发送单条命令
        executors.put("sendCommand", params -> {
            String devName = (String) params.get("device_name");
            String cmd = (String) params.get("command");
            return telnetService.sendCommand(devName, cmd);
        });

        // 工具 7: 查询知识库
        executors.put("searchKnowledge", params -> {
            // 这个工具由外部注入（VectorSearchService）
            // 返回占位，实际调用在 ChatController 里处理
            return "知识库查询结果";
        });
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

    /** 生成工具定义文本（注入 System Prompt） */
    public String getToolDefinitions() {
        return """
            ## 可用工具（通过以下 JSON 格式调用工具）
            {
              "reasoning": "推理过程（为什么需要调这个工具）",
              "tool_call": {
                "name": "工具名",
                "params": { "参数名": "参数值" }
              }
            }

            可用工具列表:
            1. queryDeviceInfo  — 查询设备型号和版本
               参数: {"device_name": "设备名"}
            2. queryCurrentConfig — 查询设备当前运行配置
               参数: {"device_name": "设备名"}
            3. queryAvailableCommands — 查询某视图下所有可用命令
               参数: {"device_name": "设备名", "view": "system-view(默认)或具体视图"}
            4. checkCommand — 检查某条命令是否存在
               参数: {"device_name": "设备名", "command": "要检查的命令"}
            5. sendConfig — 发送多条配置命令
               参数: {"device_name": "设备名", "commands": ["命令1", "命令2", ...]}
            6. sendCommand — 发送单条命令
               参数: {"device_name": "设备名", "command": "命令"}
            7. searchKnowledge — 搜索知识库
               参数: {"query": "搜索关键词"}

            规则:
            - 生成配置前必须先查 queryCurrentConfig 了解现状
            - 不确定命令是否可用时先调 checkCommand
            - 推送配置后如果返回 FAIL，分析错误并修正
            - 不要假设设备的型号，先调 queryDeviceInfo
            """;
    }

    /** 检查响应中是否包含工具调用 */
    public String extractToolCall(String aiOutput) {
        if (aiOutput == null) return null;
        int start = aiOutput.indexOf("\"tool_call\"");
        if (start < 0) return null;
        // 找到包含 tool_call 的完整 JSON 对象
        int braceStart = aiOutput.lastIndexOf('{', start);
        if (braceStart < 0) return null;
        int depth = 0;
        int braceEnd = -1;
        for (int i = braceStart; i < aiOutput.length(); i++) {
            if (aiOutput.charAt(i) == '{') depth++;
            else if (aiOutput.charAt(i) == '}') { depth--; if (depth == 0) { braceEnd = i + 1; break; } }
        }
        if (braceEnd < 0) return null;
        return aiOutput.substring(braceStart, braceEnd);
    }
}
