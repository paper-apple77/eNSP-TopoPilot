package com.topo.service;

import com.topo.model.vo.TopologyJson;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 输出配置校验器（三层防御）
 *
 * 第1层：接口存在性检查
 * 第2层：命令白名单检查（基于结构化知识库）
 * 第3层：危险命令拦截
 */
@Component
public class ConfigValidator {

    private final CommandKnowledgeService knowledgeService;

    public ConfigValidator(CommandKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    // 真正危险的命令
    private static final Set<String> DANGEROUS = Set.of("reset", "reboot", "format", "delete", "erase");

    // 危险 undo（会禁用核心功能）
    private static final Set<String> DANGEROUS_UNDO = Set.of(
        "undo aaa", "undo hrp enable", "undo ospf", "undo bgp", "undo rip",
        "undo security-policy", "undo firewall", "undo stp", "undo dhcp enable",
        "undo ip route-static 0.0.0.0"
    );

    public static class Result {
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public boolean hasErrors() { return !errors.isEmpty(); }
    }

    /**
     * 校验 AI 输出的配置
     */
    public Result validate(String configText, TopologyJson topo) {
        Result result = new Result();

        // 构建设备信息
        Map<String, DeviceInfo> deviceInfo = new HashMap<>();
        if (topo.getDevices() != null) {
            for (TopologyJson.Device d : topo.getDevices()) {
                DeviceInfo info = new DeviceInfo();
                info.model = d.getModel();
                if (d.getInterfaces() != null) info.ifaces.addAll(d.getInterfaces());
                deviceInfo.put(d.getName(), info);
            }
        }

        // 去掉代码块标记
        String cleanText = configText
            .replaceAll("`{2,}topo[\\s\\S]*?`{2,}", "")
            .replaceAll("`{2,}json[\\s\\S]*?`{2,}", "")
            .replaceAll("`{2,}[\\s\\S]*?`{2,}", "");

        String[] lines = cleanText.split("\n");
        String currentDevice = null;
        // 跟踪设备上下文（按 === 设备名 === 或 sysname 推断）
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // 检测设备上下文切换
            for (String devName : deviceInfo.keySet()) {
                if (line.startsWith("=== " + devName) || line.contains("sysname " + devName)) {
                    currentDevice = devName;
                    break;
                }
            }
            // sysname 切换
            Matcher sysMatch = Pattern.compile("sysname\\s+(\\S+)", Pattern.CASE_INSENSITIVE).matcher(line);
            if (sysMatch.find()) {
                String sysn = sysMatch.group(1);
                // 检查 sysname 是否匹配拓扑中的设备名
                if (deviceInfo.containsKey(sysn)) {
                    currentDevice = sysn;
                }
            }

            // === 第1层：接口名检查 ===
            Pattern ifPattern = Pattern.compile("\\b(GE\\d+/\\d+/\\d+|GigabitEthernet\\d+/\\d+/\\d+|Ethernet\\d+/\\d+/\\d+|Serial\\d+/\\d+/\\d+|Tunnel\\d+|Vlanif\\d+|LoopBack\\d+|Eth-Trunk\\d+|Virtual-if\\d+|NULL0)\\b");
            Matcher ifMatcher = ifPattern.matcher(line);
            while (ifMatcher.find()) {
                String iface = ifMatcher.group(1);
                // 跳过 Tunnel/Vlanif/LoopBack/Eth-Trunk/Virtual-if/NULL0（虚拟接口）
                if (iface.startsWith("Tunnel") || iface.startsWith("Vlanif") || iface.startsWith("LoopBack")
                    || iface.startsWith("Eth-Trunk") || iface.startsWith("Virtual-if") || iface.equals("NULL0")) continue;
                boolean exists = false;
                for (DeviceInfo info : deviceInfo.values()) {
                    if (info.ifaces.stream().anyMatch(ifc -> ifc.equals(iface) || ifc.replace("GigabitEthernet", "GE").equals(iface.replace("GigabitEthernet", "GE")))) {
                        exists = true; break;
                    }
                }
                if (!exists) {
                    result.warnings.add("行" + (i+1) + ": 接口 " + iface + " 未在拓扑中找到");
                }
            }

            // === 第2层：命令白名单检查 ===
            if (currentDevice != null && deviceInfo.containsKey(currentDevice)) {
                String model = deviceInfo.get(currentDevice).model;
                if (model != null && knowledgeService.getModel(model) != null) {
                    String lowerLine = line.toLowerCase();

                    // 检查是否是已知命令
                    if (!knowledgeService.isCommandKnown(model, line)) {
                        // 跳过空注释/分隔符
                        if (!line.startsWith("#") && !line.startsWith("!") && !line.startsWith("return")
                            && !line.startsWith("<") && !line.matches("^\\s{1,4}\\S+.*")) {
                            result.warnings.add("行" + (i+1) + ": " + currentDevice + "(" + model + ") 命令 \"" + line.substring(0, Math.min(50, line.length())) + "\" 未在知识库中");
                        }
                    }

                    // 功能禁止检查（三层功能不能给交换机）
                    for (String forbidden : knowledgeService.getModel(model).forbidden) {
                        if (matchesForbiddenFeature(lowerLine, forbidden)) {
                            result.errors.add("行" + (i+1) + ": " + currentDevice + "(" + model + ") 不支持 " + forbidden + "！");
                            break;
                        }
                    }
                }
            }

            // === 第3层：危险命令 ===
            String lowerLine = line.toLowerCase();
            for (String cmd : DANGEROUS) {
                if (lowerLine.startsWith(cmd) || lowerLine.contains(" " + cmd + " ")) {
                    result.errors.add("行" + (i+1) + ": 危险命令 \"" + cmd + "\"");
                }
            }
            for (String du : DANGEROUS_UNDO) {
                if (lowerLine.startsWith(du)) {
                    result.errors.add("行" + (i+1) + ": 危险撤销命令 \"" + du + "\"");
                }
            }
        }

        System.out.println("[Validator] " + result.errors.size() + " errors, " + result.warnings.size() + " warnings");
        return result;
    }

    /** 检查某行是否涉及被禁止的功能 */
    private boolean matchesForbiddenFeature(String lowerLine, String feature) {
        return switch (feature) {
            case "gre" -> lowerLine.contains("tunnel-protocol gre") || lowerLine.contains("interface tunnel");
            case "ipsec" -> lowerLine.contains("ipsec") || lowerLine.contains("ike proposal") || lowerLine.contains("ike peer");
            case "nat-server", "easy-ip" -> lowerLine.contains("nat server") || lowerLine.contains("nat-policy")
                || lowerLine.contains("nat outbound") || lowerLine.contains("source-nat");
            case "vrrp" -> lowerLine.contains("vrrp");
            case "hrp" -> lowerLine.contains("hrp");
            case "bgp" -> lowerLine.startsWith("bgp") || lowerLine.contains(" bgp ");
            case "security-policy" -> lowerLine.startsWith("security-policy");
            case "vlan" -> lowerLine.startsWith("vlan ") || lowerLine.contains("port link-type") || lowerLine.contains("port default vlan")
                || lowerLine.contains("port trunk allow-pass") || lowerLine.equals("vlan");
            case "stp" -> lowerLine.startsWith("stp ") || lowerLine.equals("stp enable");
            case "rip" -> lowerLine.startsWith("rip") || lowerLine.contains(" rip ");
            default -> false;
        };
    }

    private static class DeviceInfo {
        String model = "";
        Set<String> ifaces = new HashSet<>();
    }
}
