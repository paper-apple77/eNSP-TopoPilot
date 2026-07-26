package com.topo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.topo.model.vo.TopologyJson;
import com.topo.result.Result;
import com.topo.service.*;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final PromptBuilder promptBuilder;
    private final ConversationHistory conversationHistory;
    private final ConfigValidator configValidator;
    private final TelnetService telnetService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final CommandKnowledgeService knowledgeService;
    private final TopoXmlWriter topoXmlWriter;
    private final TopoXmlParser topoXmlParser;

    public ChatController(ChatService chatService, PromptBuilder promptBuilder,
                          ConversationHistory conversationHistory, ConfigValidator configValidator,
                          TelnetService telnetService, ToolRegistry toolRegistry,
                          ObjectMapper objectMapper, CommandKnowledgeService knowledgeService,
                          TopoXmlWriter topoXmlWriter, TopoXmlParser topoXmlParser) {
        this.chatService = chatService;
        this.promptBuilder = promptBuilder;
        this.conversationHistory = conversationHistory;
        this.configValidator = configValidator;
        this.telnetService = telnetService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;
        this.topoXmlWriter = topoXmlWriter;
        this.topoXmlParser = topoXmlParser;
        this.telnetService.setChatService(chatService);
        this.toolRegistry.init(knowledgeService, configValidator);
    }

    @GetMapping("/stream")
    public void chatStream(@RequestParam String message,
                            @RequestParam(required = false) String topologyJson,
                            @RequestParam(required = false, defaultValue = "connect") String mode,
                            @RequestParam(required = false) String token,
                            HttpServletRequest request,
                            HttpServletResponse response) throws Exception {
        Long userId = (Long) request.getAttribute("userId");

        // 解析拓扑
        TopologyJson topoJson = new TopologyJson();
        topoJson.setDevices(new ArrayList<>());
        topoJson.setConnections(new ArrayList<>());
        if (topologyJson != null && !topologyJson.isBlank()) {
            try { topoJson = objectMapper.readValue(topologyJson, TopologyJson.class); }
            catch (Exception ignored) {}
        }

        String systemPrompt = promptBuilder.buildSystemPrompt(topoJson, message, mode);
        List<Map<String, String>> history = conversationHistory.getHistory(userId, 0L, mode);

        // 直接写 response，绕过 SseEmitter
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        ServletOutputStream out = response.getOutputStream();

        // 推送命令：不调 AI，直接从历史中提取配置推送到设备
        if (!"design".equals(mode) && isPushCommand(message)) {
            pushConfigsFromHistory(userId, mode, out, response);
            conversationHistory.add(userId, 0L, message, "配置已推送到设备", mode);
            return;
        }

        // 连接模式：打包所有已知信息给 AI（拓扑+配置+PC IP）
        if (!"design".equals(mode)) {
            StringBuilder ctx = new StringBuilder();
            ctx.append("\n## 完整网络信息（来自拓扑文件 + 设备实时查询）\n\n");

            // 1. 拓扑结构一览
            ctx.append("### 设备清单\n");
            for (TopologyJson.Device d : topoJson.getDevices()) {
                ctx.append(d.getName()).append(" | 型号:").append(d.getModel()).append(" | 类型:").append(d.getType());
                if (d.getInterfaces() != null && !d.getInterfaces().isEmpty())
                    ctx.append(" | 接口: ").append(String.join(", ", d.getInterfaces()));
                // PC 的 IP 信息直接跟在设备后面
                if (("pc".equals(d.getType()) || "client".equals(d.getType()) || "server".equals(d.getType()))
                    && d.getSettings() != null && !d.getSettings().isBlank()) {
                    ctx.append(" | ").append(parsePcSettings(d.getSettings()));
                }
                ctx.append("\n");
            }
            ctx.append("\n### 连线关系\n");
            if (topoJson.getConnections() != null) {
                for (TopologyJson.Connection c : topoJson.getConnections()) {
                    ctx.append(c.getFromDevice()).append("(").append(c.getFromInterface()).append(") ↔ ")
                       .append(c.getToDevice()).append("(").append(c.getToInterface()).append(")\n");
                }
            }

            // 2. 设备实时配置（优先缓存，缓存空则实时查询）
            ctx.append("\n### 设备当前运行配置\n");
            var connected = telnetService.getConnectedDevices();
            System.out.println("[Chat] 连接模式，已连接设备: " + connected);
            for (String devName : connected) {
                String cfg = telnetService.getCachedConfig(devName);
                if (cfg == null || cfg.isBlank() || cfg.startsWith("[错误]")) {
                    cfg = telnetService.queryCurrentConfig(devName);
                }
                if (cfg != null && !cfg.isBlank() && !cfg.startsWith("[错误]")) {
                    ctx.append("--- ").append(devName).append(" ---\n").append(cfg).append("\n");
                } else {
                    ctx.append("--- ").append(devName).append(" --- 出厂默认配置\n");
                }
            }

            systemPrompt = systemPrompt + ctx.toString();
        }

        // 流式输出 AI 回复，同时收集完整文本
        StringBuilder fullResponse = new StringBuilder();
        chatService.chatStream(systemPrompt, history, message, chunk -> {
            try {
                fullResponse.append(chunk);
                // SSE data 不能含裸换行，转义
                String safe = chunk.replace("\n", "\\n");
                out.write(("data:" + safe + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                response.flushBuffer();
            } catch (Exception ignored) {}
        });
        out.write("data:[DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        // 保存真实 AI 回复到历史（从 fullResponse 中取，不是 "completed"）
        String aiReply = fullResponse.length() > 0 ? fullResponse.toString() : "completed";
        conversationHistory.add(userId, 0L, message, aiReply, mode);
    }

    /** 用户消息是否表示确认推送 */
    private boolean isPushCommand(String msg) {
        if (msg == null) return false;
        String m = msg.trim().toLowerCase();
        return m.equals("推送") || m.equals("推") || m.equals("执行")
            || m.equals("确认") || m.equals("确认推送") || m.equals("ok")
            || m.equals("yes") || m.equals("push") || m.equals("go");
    }

    /** 从对话历史中找到上一轮 AI 回复，提取其中的 ```config 块并推送 */
    private void pushConfigsFromHistory(Long userId, String mode, ServletOutputStream out, HttpServletResponse response) {
        List<Map<String, String>> history = conversationHistory.getHistory(userId, 0L, mode);
        if (history.isEmpty()) { sendSse(out, "无历史消息可推送", response); return; }
        // 最后一条 assistant 消息
        String lastAiMsg = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(history.get(i).get("role"))) {
                lastAiMsg = history.get(i).get("content");
                break;
            }
        }
        if (lastAiMsg == null || lastAiMsg.isBlank()) {
            sendSse(out, "上一轮没有 AI 配置可推送", response); return;
        }
        sendSse(out, "正在推送配置...", response);
        pushConfigs(lastAiMsg, out, response);
    }

    /** 解析配置文本中的 ```config 块并推送到对应设备 */
    private void pushConfigs(String fullResponse, ServletOutputStream out, HttpServletResponse response) {
        // 兼容 ```config AR1 和 ```configAR1 两种写法
        var pattern = java.util.regex.Pattern.compile(
            "```config\\s*(\\S+)\\s*\\n?([\\s\\S]*?)```");
        var matcher = pattern.matcher(fullResponse);
        boolean pushed = false;
        while (matcher.find()) {
            String devName = matcher.group(1).trim();
            String config = matcher.group(2).trim();
            if (!telnetService.isConnected(devName)) {
                sendSse(out, "⚠️ " + devName + " 未连接，跳过推送", response);
                continue;
            }
            // 构建完整配置序列，跟踪当前视图自动插入 quit
            config = fixCommandSpacing(config);
            StringBuilder batch = new StringBuilder();
            batch.append("system-view\n");
            boolean inSubView = false;
            for (String line : config.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                if (inSubView && !isSubCommand(line)) {
                    batch.append("quit\n");  // 退回到 system-view
                    inSubView = false;
                }
                if (isSubViewEntry(line)) {
                    inSubView = true;
                }
                batch.append(line).append("\n");
            }
            batch.append("return\n");
            // 批量发送（逐行间隔150ms）
            String result = telnetService.sendCommands(devName, batch.toString());
            boolean hasError = result.contains("Error:") || result.contains("Incomplete command")
                || result.contains("Unrecognized command");
            if (hasError) {
                sendSse(out, "⚠️ " + devName + " 推送异常，AI 正在自动修正...", response);
                // 自动修正：查设备 + 问 AI
                String fixed = autoFix(devName, config, result);
                if (fixed != null && !fixed.isBlank()) {
                    sendSse(out, "🔧 修正后的命令:\n" + fixed, response);
                    String r2 = telnetService.sendCommands(devName, "system-view\n" + fixed + "\nreturn");
                    boolean err2 = r2.contains("Error:") || r2.contains("Incomplete command");
                    sendSse(out, (err2 ? "⚠️" : "✅") + " 修正推送" + (err2 ? "仍有问题" : "成功"), response);
                }
            } else {
                sendSse(out, "✅ " + devName + " 推送完成", response);
            }
            telnetService.queryCurrentConfig(devName);
            pushed = true;
        }
        if (!pushed) {
            sendSse(out, "💡 未检测到配置推送。如需推送配置，请用 ```config 设备名\\n命令\\n``` 格式输出。", response);
        }
    }

    /** AI 自动修正失败的命令：用 ? 查设备 → AI 分析 → 返回修正命令 */
    private String autoFix(String devName, String config, String error) {
        try {
            // 1. 找到第一条失败的命令
            String firstLine = config.split("\n")[0].trim();
            // 2. 用 ? 查设备该位置有哪些可用命令
            String helpOutput = telnetService.sendCommand(devName, firstLine + " ?");
            // 如果整条命令的 ? 没用，试试只用第一个词的 ?
            if (helpOutput.length() < 5 || helpOutput.contains("Unrecognized")) {
                String firstWord = firstLine.split("\\s+")[0];
                helpOutput = telnetService.sendCommand(devName, firstWord + " ?");
            }
            System.out.println("[AutoFix] ? 输出(" + helpOutput.length() + "B)");

            // 3. 把错误 + ? 输出 + 原命令一起给 AI 分析
            String shortError = error.length() > 500 ? error.substring(0, 500) : error;
            String prompt = "华为 VRP 设备 " + devName + " 执行以下命令失败。\n"
                + "【失败命令】\n" + config + "\n"
                + "【设备回显】\n" + shortError + "\n"
                + "【设备? 输出】\n" + helpOutput + "\n"
                + "根据 ? 输出分析可用的正确命令格式，输出修正后的命令。只输出命令，不解释。无法修正输出 NO_FIX。";

            StringBuilder result = new StringBuilder();
            chatService.chatStream(prompt, List.of(), "修正", result::append);
            String fixed = result.toString().trim();
            System.out.println("[AutoFix] AI 建议: " + fixed.substring(0, Math.min(100, fixed.length())));
            if (fixed.contains("NO_FIX") || fixed.isBlank() || fixed.length() < 3) return null;
            return fixCommandSpacing(fixed);
        } catch (Exception e) {
            System.err.println("[AutoFix] " + e.getMessage());
            return null;
        }
    }

    private void sendSse(ServletOutputStream out, String msg, HttpServletResponse response) {
        try {
            out.write(("data:" + msg + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            response.flushBuffer();
        } catch (Exception ignored) {}
    }

    // ===== Telnet =====
    @GetMapping("/devices/scan")
    public Result<List<Integer>> scanDevices(@RequestParam(defaultValue = "2000") int start, @RequestParam(defaultValue = "2050") int end) {
        return Result.success(telnetService.scanDevices(start, end));
    }

    @PostMapping("/devices/connect-all")
    public Result<Map<String, Object>> connectAll(@RequestBody(required = false) Map<String, Object> body) {
        try {
            // 先断开所有旧连接，避免重复
            for (String name : telnetService.getConnectedDevices()) {
                telnetService.disconnect(name);
            }
            List<Integer> ports = telnetService.scanDevices();
            if (ports.isEmpty()) return Result.error("未发现设备，请先启动 eNSP 中的拓扑");

            // com_port 匹配 + 顺序分配兜底（两个策略互补，不互斥）
            Map<Integer, String> portToName = new LinkedHashMap<>();
            if (body != null && body.get("topologyJson") != null) {
                TopologyJson existing = objectMapper.readValue(body.get("topologyJson").toString(), TopologyJson.class);
                if (existing.getDevices() != null) {
                    // 策略1: com_port 精确匹配
                    for (TopologyJson.Device d : existing.getDevices()) {
                        if (d.getComPort() > 0 && ports.contains(d.getComPort())) {
                            portToName.put(d.getComPort(), d.getName());
                        }
                    }
                    // 策略2: 未匹配的端口按 .topo 设备顺序兜底
                    List<TopologyJson.Device> nonPc = new ArrayList<>();
                    for (TopologyJson.Device d : existing.getDevices()) {
                        if (!"pc".equals(d.getType()) && !"client".equals(d.getType()) && !"server".equals(d.getType())) {
                            nonPc.add(d);
                        }
                    }
                    int idx = 0;
                    for (int p : ports) {
                        if (!portToName.containsKey(p) && idx < nonPc.size()) {
                            portToName.put(p, nonPc.get(idx).getName());
                            idx++;
                        }
                    }
                }
            }

            // 用户可选的密码映射（从前端传入）
            Map<String, String> userPwds = new HashMap<>();
            if (body != null && body.get("passwords") != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> pwds = (Map<String, String>) body.get("passwords");
                userPwds.putAll(pwds);
            }

            List<DeviceInfo> devices = new ArrayList<>();
            for (int port : ports) {
                String name = portToName.getOrDefault(port, "Device_" + port);
                String pwd = userPwds.get(name);
                if (telnetService.connect(name, port, "admin", pwd)) {
                    String info = telnetService.queryDeviceInfo(name);
                    if (info == null || info.length() < 50) {
                        System.out.println("[ConnectAll] 跳过 " + name + ":" + port + " - 无有效回显");
                        telnetService.disconnect(name);
                        continue;
                    }
                    String model = extractModel(info);
                    if (model == null) model = "unknown";
                    // 查询配置拿到真名，如果和当前名不同则重命名
                    String cfg = telnetService.queryCurrentConfig(name);
                    String realName = extractSysname(cfg);
                    // 只在名字是临时名 "Device_xxx" 时才用 sysname 纠正
                    if (name.startsWith("Device_") && realName != null && !realName.isBlank()
                        && !"Huawei".equalsIgnoreCase(realName)
                        && !realName.matches("(?i)USG\\d+.*|S\\d+|AR\\d+|CE\\d+")) {
                        // rename: 断开旧名，用真名重连...不，直接改 sessions 里的 key
                        telnetService.rename(name, realName);
                        name = realName;
                    }
                    devices.add(new DeviceInfo(name, model, port));
                } else {
                    // 连接失败（可能是防火墙需要密码），加入列表标记
                    DeviceInfo d = new DeviceInfo(name, "firewall", port);
                    d.authFailed = true;
                    devices.add(d);
                }
            }
            // 如果画布已有拓扑，不覆盖，只返回连接状态
            boolean hasExisting = body != null && body.get("topologyJson") != null;
            Map<String, Object> result = new LinkedHashMap<>();
            // 收集需要认证的设备
            List<Map<String, Object>> authFailed = new ArrayList<>();
            for (DeviceInfo d : devices) {
                if (d.authFailed) authFailed.add(Map.of("name", d.name, "port", d.port));
            }
            result.put("connected", devices.stream().filter(d -> !d.authFailed).count());
            result.put("devices", devices.stream().filter(d -> !d.authFailed).map(d -> d.name).toList());
            if (!authFailed.isEmpty()) {
                result.put("authFailed", authFailed);
                result.put("authMsg", "以下防火墙需要密码验证");
            }
            Set<String> pwdChanged = telnetService.getAndClearPwdChanged();
            if (!pwdChanged.isEmpty()) {
                result.put("pwdChanged", pwdChanged);
                result.put("pwdMsg", "防火墙密码已重置为 admin@123，请妥善保管");
            }

            if (!hasExisting) {
                // 画布为空：用发现的设备生成拓扑
                TopologyJson topo = new TopologyJson();
                topo.setDevices(new ArrayList<>());
                topo.setConnections(new ArrayList<>());
                int x = 150, y = 200;
                for (DeviceInfo dev : devices) {
                    TopologyJson.Device d = new TopologyJson.Device();
                    d.setId(UUID.randomUUID().toString().toUpperCase());
                    d.setName(dev.name);
                    d.setModel(dev.model != null ? dev.model : "unknown");
                    d.setType(inferType(dev.model));
                    d.setX(x); d.setY(y);
                    d.setInterfaces(inferInterfaces(dev.model));
                    topo.getDevices().add(d);
                    x += 180;
                }
                result.put("topologyJson", objectMapper.writeValueAsString(topo));
            }
            System.out.println("[ConnectAll] 完成: 连接" + devices.size() + "台, sessions=" + telnetService.getConnectedDevices());
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/devices/connect-firewall")
    public Result<Map<String, Object>> connectFirewall(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("deviceName");
        int port = ((Number) body.get("port")).intValue();
        String option = (String) body.getOrDefault("option", "existing");
        String pwd = (String) body.get("password");

        boolean ok;
        if ("new".equals(option)) {
            ok = telnetService.connectNewFirewall(name, port);
        } else {
            ok = telnetService.connect(name, port, "admin", pwd);
        }

        if (ok) {
            telnetService.queryCurrentConfig(name);
            return Result.success(Map.of("deviceName", name, "connected", true,
                "pwdChanged", telnetService.getAndClearPwdChanged().contains(name)));
        }
        return Result.error("登录失败，请检查密码");
    }

    @PostMapping("/devices/disconnect")
    public Result<String> disconnectDevice(@RequestBody Map<String, String> body) {
        telnetService.disconnect(body.get("deviceName"));
        return Result.success("ok");
    }

    @GetMapping("/devices/connected")
    public Result<List<String>> getConnected() {
        return Result.success(new ArrayList<>(telnetService.getConnectedDevices()));
    }

    @PostMapping("/import-topo")
    public Result<Map<String, Object>> importTopo(@RequestParam("file") MultipartFile file) {
        try {
            TopologyJson topo = topoXmlParser.parse(file.getBytes());
            String json = objectMapper.writeValueAsString(topo);
            return Result.success(Map.of("topologyJson", json, "deviceCount", topo.getDevices().size()));
        } catch (Exception e) {
            return Result.error("导入失败: " + e.getMessage());
        }
    }

    @PostMapping("/export-topo")
    public Result<Map<String, Object>> exportTopo(@RequestBody Map<String, Object> body) {
        try {
            String topoStr = objectMapper.writeValueAsString(body.get("topology"));
            TopologyJson topo = objectMapper.readValue(topoStr, TopologyJson.class);
            String projectName = (String) body.getOrDefault("projectName", "untitled");
            String xml = topoXmlWriter.write(topo);
            return Result.success(Map.of("topoXml", xml, "filename", projectName + ".topo"));
        } catch (Exception e) {
            return Result.error("导出失败: " + e.getMessage());
        }
    }

    /** 解析 .topo 中 PC 的 settings 属性（格式: -simpc_ip:192.168.1.1 -simpc_mask:255.255.255.0 -simpc_gateway:192.168.1.254） */
    /** 修复 AI 输出配置命令时丢失的空格 */
    /** 该命令是否会进入子视图 */
    private boolean isSubViewEntry(String cmd) {
        if (cmd == null) return false;
        return cmd.startsWith("interface ") || cmd.startsWith("ospf ") || cmd.startsWith("acl ")
            || cmd.startsWith("aaa") || cmd.startsWith("user-interface ") || cmd.startsWith("ike ")
            || cmd.startsWith("ipsec ") || cmd.startsWith("firewall zone ") || cmd.startsWith("nat-policy")
            || cmd.startsWith("security-policy") || cmd.startsWith("vlan ") || cmd.startsWith("rip ")
            || cmd.startsWith("bgp ") || cmd.startsWith("dhcp ") || cmd.startsWith("ike proposal")
            || cmd.startsWith("ipsec proposal") || cmd.startsWith("ike peer");
    }

    /** 该命令是否是在子视图下的命令（不需要 quit） */
    private boolean isSubCommand(String cmd) {
        if (cmd == null) return false;
        return cmd.startsWith(" ") || cmd.startsWith("\t") || cmd.startsWith("ip address")
            || cmd.startsWith("undo ") || cmd.startsWith("port ") || cmd.startsWith("stp ")
            || cmd.startsWith("eth-trunk") || cmd.startsWith("description ") || cmd.startsWith("dhcp ")
            || cmd.startsWith("rule ") || cmd.startsWith("network ") || cmd.startsWith("area ")
            || cmd.startsWith("set ") || cmd.startsWith("add ") || cmd.startsWith("action ")
            || cmd.startsWith("source-") || cmd.startsWith("destination-") || cmd.startsWith("peer ")
            || cmd.startsWith("security ") || cmd.startsWith("proposal ") || cmd.startsWith("ike-peer")
            || cmd.startsWith("esp ") || cmd.startsWith("encryption-") || cmd.startsWith("authentication-")
            || cmd.startsWith("pre-shared-key") || cmd.startsWith("remote-address") || cmd.startsWith("ipsec policy")
            || cmd.startsWith("version ") || cmd.startsWith("tunnel-protocol") || cmd.startsWith("source ")
            || cmd.startsWith("destination ") || cmd.startsWith("nat outbound") || cmd.startsWith("nat server")
            || cmd.startsWith("vrrp ") || cmd.startsWith("alias ");
    }

    private String fixCommandSpacing(String config) {
        return config
            .replaceAll("(?i)ipaddress(\\d)", "ip address $1")           // ipaddress192.168
            .replaceAll("(?i)^sysname(\\S)", "sysname $1")               // sysnameAR1
            .replaceAll("(?i)^interface(Gigabit)", "interface $1")        // interfaceGigabit
            .replaceAll("(?i)undoshutdown", "undo shutdown")              // undoshutdown
            .replaceAll("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(\\d{1,3}\\.)", "$1 $2") // IP粘连
            .replaceAll("(?i)iproute-static(\\d)", "ip route-static $1")  // iproute-static192
            .replaceAll("(?i)portlink-type", "port link-type ")
            .replaceAll("(?i)portdefaultvlan", "port default vlan ")
            .replaceAll("(?i)porttrunkallow-pass", "port trunk allow-pass ")
            .replaceAll("(?i)vlanbatch", "vlan batch ")
            .replaceAll("(?i)^ospf(\\d)", "ospf $1")                     // ospf1 at start
            .replaceAll("(?i)^acl(\\d)", "acl $1");                      // acl3000 at start
    }

    private String parsePcSettings(String settings) {
        if (settings == null || settings.isBlank()) return "无";
        StringBuilder sb = new StringBuilder();
        for (String part : settings.split(" ")) {
            if (part.startsWith("-simpc_ip:")) sb.append("IP: ").append(part.substring(10)).append("\n");
            else if (part.startsWith("-simpc_mask:")) sb.append("掩码: ").append(part.substring(12)).append("\n");
            else if (part.startsWith("-simpc_gateway:")) sb.append("网关: ").append(part.substring(15)).append("\n");
        }
        return sb.length() > 0 ? sb.toString().trim() : settings;
    }

    private String extractModel(String info) {
        if (info == null) return null;
        for (String m : List.of("USG6000V", "S5700", "AR2220", "S3700", "AR1220"))
            if (info.contains(m)) return m;
        return null;
    }
    private String extractSysname(String cfg) {
        if (cfg == null) return null;
        var m = java.util.regex.Pattern.compile("sysname\\s+(\\S+)").matcher(cfg);
        return m.find() ? m.group(1) : null;
    }
    private String inferType(String m) {
        if (m == null) return "unknown";
        if (m.startsWith("USG")) return "firewall";
        if (m.startsWith("S5") || m.startsWith("S3")) return "switch";
        if (m.startsWith("AR")) return "router";
        return "unknown";
    }
    private List<String> inferInterfaces(String model) {
        CommandKnowledgeService.ModelKnowledge mk = knowledgeService.getModel(model);
        if (mk == null) return List.of();
        List<String> ifs = new ArrayList<>();
        for (int i = 0; i < mk.interfaceCount; i++) ifs.add(mk.interfacePrefix + i);
        return ifs;
    }
    private static class DeviceInfo { String name, model; int port; boolean authFailed; DeviceInfo(String n, String m, int p) { name=n; model=m; port=p; } }
}
