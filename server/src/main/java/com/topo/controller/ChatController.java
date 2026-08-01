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
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final PromptBuilder promptBuilder;
    private final ConversationHistory conversationHistory;
    private final TelnetService telnetService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final CommandKnowledgeService knowledgeService;
    private final TopoXmlWriter topoXmlWriter;
    private final TopoXmlParser topoXmlParser;

    public ChatController(ChatService chatService, PromptBuilder promptBuilder,
                          ConversationHistory conversationHistory,
                          TelnetService telnetService, ToolRegistry toolRegistry,
                          ObjectMapper objectMapper, CommandKnowledgeService knowledgeService,
                          TopoXmlWriter topoXmlWriter, TopoXmlParser topoXmlParser) {
        this.chatService = chatService;
        this.promptBuilder = promptBuilder;
        this.conversationHistory = conversationHistory;
        this.telnetService = telnetService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;
        this.topoXmlWriter = topoXmlWriter;
        this.topoXmlParser = topoXmlParser;
        this.telnetService.setChatService(chatService);
        this.toolRegistry.init();
    }

    @PostMapping("/stream")
    public void chatStream(@RequestParam String message,
                            @RequestParam(required = false) String topologyJson,
                            @RequestParam(required = false, defaultValue = "connect") String mode,
                            @RequestParam(required = false) String token,
                            @RequestParam(required = false) String devices,
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
        // 连接模式：注入缓存中的轻量摘要，AI 无需重复查询
        if (!"design".equals(mode)) {
            StringBuilder cacheCtx = new StringBuilder();
            for (String devName : telnetService.getConnectedDevices()) {
                String cached = telnetService.getCachedConfig(devName);
                if (cached != null && !cached.isBlank() && !cached.startsWith("[错误]")) {
                    cacheCtx.append("\n【").append(devName).append("】\n").append(cached).append("\n");
                }
            }
            if (cacheCtx.length() > 0) {
                systemPrompt += "\n\n" + cacheCtx + "\n以上是设备当前运行状态。如需细节再用 sendCommand 查询。";
            }
        }
        List<Map<String, String>> history = conversationHistory.getHistory(userId, 0L, mode);

        // 直接写 response，绕过 SseEmitter，零缓冲确保逐字输出
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setBufferSize(0);
        ServletOutputStream out = response.getOutputStream();
        out.flush();  // 立即发送 HTTP 头

        // 推送命令：不调 AI，直接从历史中提取配置推送到设备
        if (!"design".equals(mode) && isPushCommand(message)) {
            pushConfigsFromHistory(userId, mode, out, response);
            conversationHistory.add(userId, 0L, message, "配置已推送到设备", mode);
            return;
        }

        try {
            // 先发思考提示
            out.write("data:🔍 AI 正在分析...\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            response.flushBuffer();

            StringBuilder fullResponse = new StringBuilder();
            if ("design".equals(mode)) {
            // 设计模式：流式对话
            chatService.chatStream(systemPrompt, history, message, chunk -> {
                fullResponse.append(chunk);
                try {
                    String safe = chunk.replace("\n", "\\n");
                    out.write(("data:" + safe + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    response.flushBuffer();
                } catch (Exception ignored) {}
            });
        } else {
            // 连接模式：Agent 自主调工具
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                chatService.agentChat(systemPrompt, history, message, event -> {
                    try {
                        switch (event.type) {
                            case "thinking" -> {
                                out.write(("data:💭 " + event.message + "\n\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                response.flushBuffer();
                            }
                            case "token" -> {
                                fullResponse.append(event.message);
                                String safe = event.message.replace("\n", "\\n");
                                out.write(("data:" + safe + "\n\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                response.flushBuffer();
                            }
                            case "tool_start" -> {
                                String msg = "🔧 " + event.message;
                                out.write(("data:" + msg + "\n\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                response.flushBuffer();
                            }
                            case "tool_result" -> { /* 静默 */ }
                            case "error" -> {
                                out.write(("data:⚠️ " + event.message + "\n\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                response.flushBuffer();
                            }
                            case "done" -> {
                                System.out.println("[Agent] 完成: " + event.message);
                            }
                        }
                    } catch (Exception e) {
                        cancelled.set(true); // 客户端断开
                    }
                }, cancelled);
            } catch (Exception e) {
                System.err.println("[Chat] Agent异常: " + e.getMessage());
                e.printStackTrace();
                String errMsg = "⚠️ AI 处理异常: " + e.getMessage();
                out.write(("data:" + errMsg + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                response.flushBuffer();
            }
        }
            out.write("data:[DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            String aiReply = fullResponse.length() > 0 ? fullResponse.toString() : "completed";
            conversationHistory.add(userId, 0L, message, aiReply, mode);
        } catch (Exception e) {
            System.err.println("[Chat] SSE异常: " + e.getMessage());
            try { out.write("data:⚠️ 系统异常，请重试\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); } catch (Exception ignored) {}
        }
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
        List<String> pushedDevices = pushConfigs(lastAiMsg, out, response);
        // 推送后自动验证
        if (!pushedDevices.isEmpty()) {
            sendSse(out, "", response);
            verifyPushResults(pushedDevices, lastAiMsg, out, response);
        }
    }

    /** 解析配置文本中的 ```config 块并推送到对应设备，返回推送过的设备名列表 */
    private List<String> pushConfigs(String fullResponse, ServletOutputStream out, HttpServletResponse response) {
        var pattern = java.util.regex.Pattern.compile(
            "```config\\s*(\\S+)\\s*\\n?([\\s\\S]*?)```");
        var matcher = pattern.matcher(fullResponse);
        List<String> pushedDevices = new ArrayList<>();
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
            pushedDevices.add(devName);
        }
        if (pushedDevices.isEmpty()) {
            sendSse(out, "💡 未检测到配置推送。如需推送配置，请用 ```config 设备名\\n命令\\n``` 格式输出。", response);
        }
        return pushedDevices;
    }

    /** 推送后验证：查关键指标，交给 AI 分析是否生效 */
    private void verifyPushResults(List<String> pushedDevices, String originalConfig,
                                    ServletOutputStream out, HttpServletResponse response) {
        sendSse(out, "🔍 正在验证配置...", response);
        // 收集验证数据
        StringBuilder verifyData = new StringBuilder();
        for (String devName : pushedDevices) {
            if (!telnetService.isConnected(devName)) continue;
            verifyData.append("=== ").append(devName).append(" ===\n");
            verifyData.append("[ip interface brief]\n");
            verifyData.append(telnetService.sendCommand(devName, "display ip interface brief")).append("\n");
            // 根据推送内容决定额外查什么
            if (originalConfig.contains("ospf")) {
                verifyData.append("[ospf peer]\n");
                verifyData.append(telnetService.sendCommand(devName, "display ospf peer brief")).append("\n");
            }
            if (originalConfig.contains("vlan")) {
                verifyData.append("[vlan]\n");
                verifyData.append(telnetService.sendCommand(devName, "display vlan")).append("\n");
            }
        }
        // 让 AI 分析
        String prompt = "你刚推送了以下配置到设备，现在验证是否生效。根据设备回显分析每个配置项的生效情况，有问题说问题，没问题说没问题。简短报告。\n\n"
            + "【推送的配置】\n" + originalConfig + "\n\n【设备当前状态】\n" + verifyData;
        try {
            chatService.chatStream(prompt, List.of(), "验证", chunk -> {
                try {
                    String safe = chunk.replace("\n", "\\n");
                    out.write(("data:" + safe + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    response.flushBuffer();
                } catch (Exception ignored) {}
            });
            out.write("data:[DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            sendSse(out, "验证异常: " + e.getMessage(), response);
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

            List<DeviceInfo> devices = Collections.synchronizedList(new ArrayList<>());
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(ports.size(), 6));
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int port : ports) {
                String name = portToName.getOrDefault(port, "Device_" + port);
                String pwd = userPwds.get(name);
                futures.add(CompletableFuture.runAsync(() -> {
                    System.out.println("[ConnectAll] " + name + ":" + port + " 开始连接 [" + Thread.currentThread().getName() + "]");
                    if (telnetService.connect(name, "127.0.0.1", port, "admin", pwd)) {
                        String info = telnetService.queryDeviceInfo(name);
                        if (info == null || info.length() < 50) {
                            System.out.println("[ConnectAll] 跳过 " + name + ":" + port + " - 无有效回显");
                            telnetService.disconnect(name);
                            return;
                        }
                        String model = extractModel(info);
                        if (model == null) model = "unknown";
                        String devName = name;
                        // 用轻量摘要替代全量配置（快 5-10 倍，AI 直接用）
                        String devType = inferType(model);
                        String cfg = telnetService.queryLightConfig(name, devType);
                        telnetService.updateCache(name, cfg);
                        // 快速查 sysname 用于设备重命名
                        String sysInfo = telnetService.sendCommand(name, "display current-configuration | include sysname");
                        String realName = extractSysname(sysInfo);
                        if (devName.startsWith("Device_") && realName != null && !realName.isBlank()
                            && !"Huawei".equalsIgnoreCase(realName)
                            && !realName.matches("(?i)USG\\d+.*|S\\d+|AR\\d+|CE\\d+")) {
                            telnetService.rename(devName, realName);
                            devName = realName;
                        }
                        devices.add(new DeviceInfo(devName, model, port));
                    } else {
                        DeviceInfo d = new DeviceInfo(name, "firewall", port);
                        d.authFailed = true;
                        devices.add(d);
                    }
                }, executor));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(120, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.err.println("[ConnectAll] 部分设备连接超时");
            }
            executor.shutdown();

            // LLDP 邻居匹配：用真实设备连接关系精确识别设备
            if (body != null && body.get("topologyJson") != null) {
                try {
                    TopologyJson topoRef = objectMapper.readValue(body.get("topologyJson").toString(), TopologyJson.class);
                    matchDevicesByLldp(devices, topoRef);
                } catch (Exception e) {
                    System.err.println("[ConnectAll] LLDP匹配异常: " + e.getMessage());
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
            ok = telnetService.connect(name, "127.0.0.1", port, "admin", pwd);
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
    /** LLDP 邻居匹配：用邻居关系+型号精确识别设备 */
    private void matchDevicesByLldp(List<DeviceInfo> devices, TopologyJson topo) {
        if (topo.getDevices() == null || topo.getConnections() == null) return;
        var connectedDevs = devices.stream().filter(d -> !d.authFailed).toList();
        if (connectedDevs.size() < 2) return;

        // LLDP 签名: {名称, 型号, 端口, 连接列表(本端接口, 邻居接口)}
        record LlpdSig(String name, String model, int port, Map<String,String> links) {}
        List<LlpdSig> sigs = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> lldpFutures = new ArrayList<>();
        for (DeviceInfo dev : connectedDevs) {
            lldpFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    String lldpRaw = telnetService.queryLldpNeighbors(dev.name);
                    Map<String,String> links = parseLldpLinks(lldpRaw);
                    String model = dev.model != null ? dev.model : "unknown";
                    sigs.add(new LlpdSig(dev.name, model, dev.port, links));
                    System.out.println("[LLDP] " + dev.name + ":" + dev.port + " model=" + model + " links=" + links);
                } catch (Exception e) {
                    System.err.println("[LLDP] " + dev.name + " 查询失败: " + e.getMessage());
                }
            }));
        }
        try { CompletableFuture.allOf(lldpFutures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS); }
        catch (Exception e) { System.err.println("[LLDP] 并行查询超时"); }

        // .topo 签名: {名称, 型号, 连接列表(本端接口, 对方设备名+接口)}
        record TopoSig(String name, String model, Map<String,String> links) {}
        List<TopoSig> topoSigs = new ArrayList<>();
        for (TopologyJson.Device d : topo.getDevices()) {
            if ("pc".equals(d.getType()) || "client".equals(d.getType()) || "server".equals(d.getType())) continue;
            Map<String,String> links = new LinkedHashMap<>();
            for (TopologyJson.Connection c : topo.getConnections()) {
                if (c.getFromDevice().equals(d.getName()))
                    links.put(c.getFromInterface(), c.getToDevice() + "/" + c.getToInterface());
                if (c.getToDevice().equals(d.getName()))
                    links.put(c.getToInterface(), c.getFromDevice() + "/" + c.getFromInterface());
            }
            topoSigs.add(new TopoSig(d.getName(), d.getModel(), links));
        }

        // 匹配: 型号相同 + 邻居接口重叠
        Set<String> usedNames = new HashSet<>();
        for (LlpdSig sig : sigs) {
            TopoSig bestMatch = null;
            int bestScore = 0;
            for (TopoSig ts : topoSigs) {
                if (usedNames.contains(ts.name)) continue;
                if (ts.model == null || !ts.model.equals(sig.model)) continue;
                // 计算邻居接口匹配数
                int score = 0;
                for (Map.Entry<String,String> e : sig.links.entrySet()) {
                    String localIface = e.getKey();
                    String remoteIface = e.getValue();
                    String tsRemote = ts.links.get(localIface);
                    if (tsRemote != null && tsRemote.contains(remoteIface)) score += 2;
                    else if (ts.links.containsKey(localIface)) score += 1;
                }
                if (score > bestScore) { bestScore = score; bestMatch = ts; }
            }
            if (bestMatch != null && bestScore > 0 && !bestMatch.name.equals(sig.name)) {
                System.out.println("[LLDP] 匹配: 端口" + sig.port + "(" + sig.name + "," + sig.model + ") → " + bestMatch.name + " (score=" + bestScore + ")");
                telnetService.rename(sig.name, bestMatch.name);
                usedNames.add(bestMatch.name);
                for (DeviceInfo d : devices) {
                    if (d.name.equals(sig.name)) { d.name = bestMatch.name; break; }
                }
            }
        }
    }

    /** 解析 display lldp neighbor brief: {本端接口 → 邻居接口} */
    private Map<String,String> parseLldpLinks(String lldpOutput) {
        Map<String,String> links = new LinkedHashMap<>();
        if (lldpOutput == null || lldpOutput.isBlank()) return links;
        for (String line : lldpOutput.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("Local") || line.startsWith("--") || line.contains("Error:")) continue;
            // 格式: GE0/0/1      USG6000V1                GE1/0/1                   116
            String[] parts = line.split("\\s+");
            if (parts.length >= 3 && parts[0].contains("/") && parts[2].contains("/")) {
                links.put(parts[0], parts[2]); // 本端接口 → 邻居接口
            }
        }
        return links;
    }

    private static class DeviceInfo { String name, model; int port; boolean authFailed; DeviceInfo(String n, String m, int p) { name=n; model=m; port=p; } }
}
