package com.topo.service;

import jakarta.annotation.PreDestroy;
import org.apache.commons.net.telnet.TelnetClient;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelnetService {

    private ChatService chatService;
    public void setChatService(ChatService cs) { this.chatService = cs; }

    private final Map<String, TelnetSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> cachedConfig = new ConcurrentHashMap<>();
    private final Set<String> pwdChangedDevices = ConcurrentHashMap.newKeySet();

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 30000;
    private static final int DEFAULT_SCAN_START = 2000;
    private static final int DEFAULT_SCAN_END = 2050;

    public static class TelnetSession {
        public final String host; public final int port; public final String deviceName;
        private final TelnetClient client; private final PrintWriter writer; private final BufferedReader reader;
        TelnetSession(String h, int p, String n, TelnetClient c, PrintWriter w, BufferedReader r) {
            host=h; port=p; deviceName=n; client=c; writer=w; reader=r;
        }
        public void disconnect() {
            try { writer.close(); } catch (Exception ignored) {}
            try { reader.close(); } catch (Exception ignored) {}
            try { client.disconnect(); } catch (Exception ignored) {}
        }
    }

    public List<Integer> scanDevices(int start, int end) {
        List<Integer> found = new ArrayList<>();
        for (int port = start; port <= end; port++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                found.add(port);
                System.out.println("[Telnet] 发现设备端口: " + port);
            } catch (Exception ignored) {}
        }
        System.out.println("[Telnet] 扫描完成: " + start + "-" + end + " → 发现 " + found.size() + " 个设备");
        return found;
    }

    public List<Integer> scanDevices() { return scanDevices(DEFAULT_SCAN_START, DEFAULT_SCAN_END); }

    public boolean connect(String deviceName, String host, int port, String user, String pwd) {
        if (sessions.containsKey(deviceName)) disconnect(deviceName);
        try {
            TelnetClient client = new TelnetClient();
            client.setConnectTimeout(CONNECT_TIMEOUT);
            client.setDefaultTimeout(READ_TIMEOUT);
            client.connect(host, port);

            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            TelnetSession session = new TelnetSession(host, port, deviceName, client, writer, reader);

            // 等设备就绪：多次回车 + 较长等待
            Thread.sleep(1000);
            for (int i = 0; i < 5; i++) { writer.println(""); Thread.sleep(800); }
            // 多读几次，合并所有输出
            StringBuilder allOutput = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                allOutput.append(readAvailable(reader));
                Thread.sleep(500);
            }
            String welcome = allOutput.toString();
            System.out.println("[Telnet] " + deviceName + " 回显(" + welcome.length() + "B): " + welcome.substring(0, Math.min(100, welcome.length())).replace('\n',' '));

            // 检测到防火墙登录
            if (welcome.contains("Username") || welcome.contains("login") || welcome.contains("Password")) {
                if (pwd == null) {
                    System.out.println("[Telnet] " + deviceName + " 需要密码认证，待用户处理");
                    return false;
                }
                // 用户提供了密码，直接登录
                System.out.println("[Telnet] " + deviceName + " 用提供的密码登录...");
                writer.println("admin");
                waitFor(reader, new String[]{"Password", "password"}, 5000);
                writer.println(pwd);
                Thread.sleep(3000);
                String result = readAvailable(reader);
                if (result.contains("fail") || result.contains("incorrect") || result.contains("Error: Authentication")) {
                    System.out.println("[Telnet] " + deviceName + " 密码错误");
                    return false;
                }
            }

            // 成功后才注册
            sessions.put(deviceName, session);
            readAvailable(reader);
            System.out.println("[Telnet] 已连接 " + deviceName + " @ " + host + ":" + port);
            return true;
        } catch (Exception e) {
            System.err.println("[Telnet] 连接失败 " + deviceName + ":" + port + " - " + e.getMessage());
            return false;
        }
    }

    public String sendCommand(String deviceName, String command) {
        TelnetSession s = sessions.get(deviceName);
        if (s == null) return "[错误] 未连接";
        try {
            s.writer.println(command);
            int wait = command.startsWith("display") ? 2000 : 500;
            Thread.sleep(wait);
            String r = readAvailable(s.reader);
            int show = Math.min(80, r.length());
            System.out.println("[Telnet] " + deviceName + " → " + command + " → (" + r.length() + "B) " + r.substring(0, show).replace('\n',' '));
            return r.strip();
        } catch (Exception e) { return "[错误] " + e.getMessage(); }
    }

    public String sendCommands(String deviceName, String commands) {
        TelnetSession s = sessions.get(deviceName);
        if (s == null) return "[错误] 未连接";
        try {
            for (String line : commands.split("\n")) {
                line = line.trim(); if (line.isEmpty()) continue;
                s.writer.println(line);
                Thread.sleep(line.contains("system-view") ? 800 : 300);
            }
            Thread.sleep(2000);
            String r = readAvailable(s.reader);
            System.out.println("[Telnet] " + deviceName + " ← 批量(" + r.length() + "B)");
            return r.strip();
        } catch (Exception e) { return "[错误] " + e.getMessage(); }
    }

    /** 安全发送：失败自动 ? 查设备 + AI 纠错 + 重试 */
    public String safeSend(String deviceName, String command) {
        String result = sendCommand(deviceName, command);
        if (result == null) return "[错误]";
        boolean isErr = result.contains("Error:") || result.contains("Unrecognized command")
            || result.contains("Incomplete command") || result.contains("Ambiguous command");
        if (!isErr) return result;

        System.out.println("[Telnet] safeSend 纠错: " + deviceName + " ← " + command);
        for (int attempt = 0; attempt < 2 && chatService != null; attempt++) {
            String help = sendCommand(deviceName, command.split("\\s+")[0] + " ?");
            if (help.length() < 5 || help.contains("Unrecognized")) help = sendCommand(deviceName, "?");
            try {
                String prompt = "华为VRP命令失败。输出唯一一条修正命令，不要解释。\n失败:" + command + "\n错误:" + result.substring(0, Math.min(300, result.length())) + "\n?输出:" + help.substring(0, Math.min(500, help.length())) + "\n无法修正输出NO_FIX。";
                StringBuilder out = new StringBuilder();
                chatService.chatStream(prompt, List.of(), "修正", out::append);
                String fixed = out.toString().trim();
                if (fixed.contains("NO_FIX") || fixed.length() < 2) break;
                System.out.println("[Telnet] safeSend 重试: " + fixed);
                result = sendCommand(deviceName, fixed);
                if (!result.contains("Error:") && !result.contains("Unrecognized")) return result;
            } catch (Exception ignored) {}
        }
        return result;
    }

    public String queryDeviceInfo(String deviceName) { return safeSend(deviceName, "display version"); }

    public String queryCurrentConfig(String deviceName) {
        safeSend(deviceName, "return");
        String cfg = sendWithPagination(deviceName, "display current-configuration");
        if (cfg != null && !cfg.isBlank() && !cfg.startsWith("[错误]")) cachedConfig.put(deviceName, cfg);
        System.out.println("[Telnet] queryCurrentConfig " + deviceName + " → " + (cfg != null ? cfg.length() + "B" : "null"));
        return cfg;
    }

    /** 轻量配置查询：用几个小命令替代 display current-configuration（缩减 20~50 倍） */
    public String queryLightConfig(String deviceName, String deviceType) {
        StringBuilder sb = new StringBuilder();
        // 1. IP 接口概要（所有设备）
        sb.append("--- ip interface brief ---\n");
        sb.append(sendCommand(deviceName, "display ip interface brief"));
        // 2. VLAN（交换机）
        if ("switch".equals(deviceType)) {
            sb.append("\n--- vlan ---\n");
            sb.append(sendCommand(deviceName, "display vlan"));
        }
        // 3. 静态路由（路由器）
        if ("router".equals(deviceType)) {
            sb.append("\n--- static routes ---\n");
            sb.append(sendCommand(deviceName, "display ip routing-table protocol static"));
        }
        // 4. 防火墙 zone（防火墙）
        if ("firewall".equals(deviceType)) {
            sb.append("\n--- firewall zone ---\n");
            sb.append(sendCommand(deviceName, "display firewall zone"));
        }
        System.out.println("[Telnet] queryLightConfig " + deviceName + "(" + deviceType + ") → " + sb.length() + "B");
        return sb.toString();
    }

    public String getCachedConfig(String deviceName) { return cachedConfig.get(deviceName); }
    public Set<String> getAndClearPwdChanged() {
        Set<String> copy = new HashSet<>(pwdChangedDevices);
        pwdChangedDevices.clear(); return copy;
    }

    private String sendWithPagination(String deviceName, String command) {
        TelnetSession s = sessions.get(deviceName);
        if (s == null) return "[错误] 未连接";
        try {
            s.writer.println(command);
            StringBuilder full = new StringBuilder();
            int emptyCount = 0;
            while (true) {
                Thread.sleep(500);
                String page = readAvailable(s.reader);
                if (page.length() < 5) {
                    if (++emptyCount > 10) break; // 10次空读，设备可能卡死了
                    continue;
                }
                emptyCount = 0;
                full.append(page);
                if (!page.contains("---- More ----")) break;
                s.writer.print(" "); s.writer.flush();
                Thread.sleep(300);
            }
            System.out.println("[Telnet] " + deviceName + " ← paged(" + full.length() + "B)");
            return full.toString().strip();
        } catch (Exception e) { return "[错误] " + e.getMessage(); }
    }

    /** 连接全新防火墙（默认密码），自动处理改密码流程 */
    public boolean connectNewFirewall(String deviceName, int port) {
        return connectNewFirewall(deviceName, "127.0.0.1", port);
    }

    public boolean connectNewFirewall(String deviceName, String host, int port) {
        if (sessions.containsKey(deviceName)) disconnect(deviceName);
        try {
            TelnetClient client = new TelnetClient();
            client.setConnectTimeout(CONNECT_TIMEOUT);
            client.setDefaultTimeout(READ_TIMEOUT);
            client.connect(host, port);
            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));

            // 等设备就绪
            Thread.sleep(1000);
            for (int i = 0; i < 5; i++) { writer.println(""); Thread.sleep(800); }
            readAvailable(reader);

            // Step 1: 等待登录提示，输入用户名
            waitFor(reader, new String[]{"Username", "username", "login"}, 10000);
            System.out.println("[Telnet] " + deviceName + " 输入用户名...");
            writer.println("admin");
            // Step 2: 等待密码提示，输入默认密码
            waitFor(reader, new String[]{"Password", "password"}, 8000);
            System.out.println("[Telnet] " + deviceName + " 输入密码...");
            writer.println("Admin@123");
            // Step 3: 等结果
            String postLogin = waitFor(reader, new String[]{"Change now", "needs to be changed", "fail", "incorrect", "Error:", ">"}, 10000);
            System.out.println("[Telnet] 登录响应: " + postLogin.substring(0, Math.min(120, postLogin.length())).replace('\n',' '));

            if (postLogin.contains("incorrect") || postLogin.contains("fail") || postLogin.contains("Error: Authentication")) {
                System.out.println("[Telnet] " + deviceName + " 默认密码不正确");
                return false;
            }

            // Step 4: 改密码流程
            if (postLogin.contains("Change now") || postLogin.contains("needs to be changed")) {
                System.out.println("[Telnet] " + deviceName + " 改密码...");
                writer.println("y");
                waitFor(reader, new String[]{"old password", "Old password"}, 8000);
                writer.println("Admin@123");  // 旧密码
                waitFor(reader, new String[]{"new password", "New password"}, 8000);
                writer.println("admin@123");  // 新密码
                waitFor(reader, new String[]{"confirm", "Confirm"}, 8000);
                writer.println("admin@123");  // 确认
                Thread.sleep(2000);
                String finalResp = readAvailable(reader);
                if (finalResp.contains("fail") || finalResp.contains("Username")) {
                    System.out.println("[Telnet] 改密码失败");
                    return false;
                }
                System.out.println("[Telnet] " + deviceName + " 密码→admin@123");
                pwdChangedDevices.add(deviceName);
            }

            // 验证登录成功：发空行，检查是否有正常提示符
            writer.println("");
            Thread.sleep(1000);
            String verify = readAvailable(reader);
            if (verify.contains("Username") || verify.contains("Password") || verify.contains("fail") || verify.contains("Error: Authentication")) {
                System.out.println("[Telnet] 登录验证失败: " + verify.replace('\n',' '));
                return false;
            }

            TelnetSession session = new TelnetSession(host, port, deviceName, client, writer, reader);
            sessions.put(deviceName, session);
            System.out.println("[Telnet] 已连接 " + deviceName + " @ " + host + ":" + port);
            return true;
        } catch (Exception e) {
            System.err.println("[Telnet] 新防火墙连接失败 " + deviceName + " - " + e.getMessage());
            return false;
        }
    }

    /** 等待读取到指定关键词（最多等 timeout 毫秒），返回所有已读内容 */
    private String waitFor(BufferedReader reader, String[] keywords, int timeoutMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (reader.ready()) {
                char[] buf = new char[4096]; int n = reader.read(buf);
                if (n > 0) { sb.append(buf, 0, n); start = System.currentTimeMillis(); }
            }
            String soFar = sb.toString();
            for (String kw : keywords) if (soFar.contains(kw)) return soFar;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return sb.toString();
    }

    public boolean isConnected(String name) { return sessions.containsKey(name); }
    public Set<String> getConnectedDevices() { return Collections.unmodifiableSet(sessions.keySet()); }
    public void rename(String oldName, String newName) {
        TelnetSession s = sessions.remove(oldName);
        if (s != null) { sessions.put(newName, s); System.out.println("[Telnet] 重命名 " + oldName + " → " + newName); }
    }

    public void disconnect(String name) {
        TelnetSession s = sessions.remove(name);
        if (s != null) { s.disconnect(); System.out.println("[Telnet] 已断开 " + name); }
    }

    @PreDestroy
    public void disconnectAll() {
        for (String name : new ArrayList<>(sessions.keySet())) disconnect(name);
    }

    private String readAvailable(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            if (reader.ready()) {
                char[] buf = new char[4096]; int n = reader.read(buf);
                if (n > 0) { sb.append(buf, 0, n); start = System.currentTimeMillis(); }
            } else if (sb.length() > 0) break;
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        return sb.toString().replaceAll("\\[[;\\d]*[A-Za-z]", "").replaceAll("\\r", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }
}
