package com.topo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ToolRegistry {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TelnetService telnetService;

    public ToolRegistry(TelnetService telnetService) {
        this.telnetService = telnetService;
    }

    public interface ToolExecutor {
        String execute(Map<String, Object> params);
    }

    private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();
    private boolean initialized = false;

    public void init() {
        if (initialized) return;
        initialized = true;

        executors.put("queryDeviceInfo", params -> {
            String devName = (String) params.get("device_name");
            return telnetService.queryDeviceInfo(devName);
        });

        executors.put("queryCurrentConfig", params -> {
            String devName = (String) params.get("device_name");
            String cached = telnetService.getCachedConfig(devName);
            if (cached != null && !cached.isBlank() && !cached.startsWith("[错误]")) return cached;
            return telnetService.queryCurrentConfig(devName);
        });

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
            return result.isBlank() ? "推送完成(无回显)" : result;
        });

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

    public String execute(String toolName, Map<String, Object> params) {
        ToolExecutor executor = executors.get(toolName);
        if (executor == null) return "未知工具: " + toolName;
        try {
            return executor.execute(params);
        } catch (Exception e) {
            return "工具执行异常: " + e.getMessage();
        }
    }

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
