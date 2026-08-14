package com.topo.service;

import jakarta.annotation.PreDestroy;
import org.apache.commons.net.telnet.TelnetClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class TelnetService {

    private ChatService chatService;
    public void setChatService(ChatService cs) { this.chatService = cs; }

    /** eNSP 所在主机：本机运行 127.0.0.1，Docker 部署 ENSP_HOST=host.docker.internal */
    @Value("${ensp.host:127.0.0.1}")
    private String enspHost;

    public String getEnspHost() { return enspHost; }

    private final Map<String, TelnetSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> cachedConfig = new ConcurrentHashMap<>();
    private final Set<String> pwdChangedDevices = ConcurrentHashMap.newKeySet();
    /** 每台设备一把锁：AI 命令执行与心跳互斥，同设备串行、跨设备并行 */
    private final Map<String, ReentrantLock> deviceLocks = new ConcurrentHashMap<>();

    public ReentrantLock getDeviceLock(String deviceName) {
        return deviceLocks.computeIfAbsent(deviceName, k -> new ReentrantLock());
    }

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 30000;
    private static final int DEFAULT_SCAN_START = 2000;
    private static final int DEFAULT_SCAN_END = 2050;

    public static class TelnetSession {
        public final String host; public final int port; public final String deviceName;
        public String user; public String password;
        private final TelnetClient client; public final PrintWriter writer; public final BufferedReader reader;
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
                s.connect(new InetSocketAddress(enspHost, port), 500);
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

            // 等设备就绪：多次回车唤醒
            Thread.sleep(1000);
            for (int i = 0; i < 5; i++) { writeln(session, ""); Thread.sleep(800); }
            StringBuilder allOutput = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                allOutput.append(readAvailable(reader));
                Thread.sleep(500);
            }
            String welcome = allOutput.toString();
            System.out.println("[Telnet] " + deviceName + " 回显(" + welcome.length() + "B): " + welcome.substring(0, Math.min(100, welcome.length())).replace('\n',' '));

            // 防火墙空闲休眠：Press ENTER 唤醒后再读登录提示
            if (welcome.contains("Press ENTER") || welcome.contains("ENTER")) {
                System.out.println("[Telnet] " + deviceName + " 设备休眠，按回车唤醒...");
                writeln(session, "");
                Thread.sleep(2000);
                welcome = readAvailable(reader);
                System.out.println("[Telnet] " + deviceName + " 唤醒后(" + welcome.length() + "B): " + welcome.substring(0, Math.min(100, welcome.length())).replace('\n',' '));
            }

            // 有错误但没有登录提示 → 设备彻底不可用
            boolean hasLoginPrompt = welcome.contains("Username") || welcome.contains("login") || welcome.contains("Password");
            boolean hasError = welcome.toLowerCase().contains("fail") || welcome.toLowerCase().contains("error:");
            if (hasError && !hasLoginPrompt) {
                System.out.println("[Telnet] " + deviceName + " 设备回显错误且无登录提示，放弃连接");
                return false;
            }

            // 错误+登录提示共存 → 错误是旧的残留，登录提示说明设备可用，继续
            if (hasError && hasLoginPrompt) {
                System.out.println("[Telnet] " + deviceName + " 回显含旧错误+登录提示，忽略旧错误继续登录");
            }

            if (hasLoginPrompt) {
                if (pwd == null) {
                    System.out.println("[Telnet] " + deviceName + " 需要密码认证，待用户处理");
                    return false;
                }
                // 用户提供了密码，直接登录
                System.out.println("[Telnet] " + deviceName + " 用提供的密码登录...");
                writeln(session, "admin");
                waitFor(reader, new String[]{"Password", "password"}, 5000);
                writeln(session, pwd);
                Thread.sleep(3000);
                String result = readAvailable(reader);
                String lower = result.toLowerCase();
                // 正面检查：必须出现命令行提示符 >
                if (!result.contains(">")) {
                    System.out.println("[Telnet] " + deviceName + " 登录未成功(" + result.length() + "B): " + result.substring(0, Math.min(80, result.length())).replace('\n',' '));
                    return false;
                }
            }

            // 成功后才注册
            session.user = user; session.password = pwd;
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
        ReentrantLock lock = getDeviceLock(deviceName);
        lock.lock();
        try {
            writeln(s, command);
            Thread.sleep(100);
            long timeout = command.startsWith("display") ? 20000 : 15000;
            String r = readUntilPrompt(s, timeout, 500);
            int show = Math.min(80, r.length());
            System.out.println("[Telnet] " + deviceName + " → " + command + " → (" + r.length() + "B) " + r.substring(0, show).replace('\n',' '));
            return r;
        } catch (Exception e) {
            return "[错误] " + e.getMessage();
        } finally {
            lock.unlock();
        }
    }

    public String sendCommands(String deviceName, String commands) {
        TelnetSession s = sessions.get(deviceName);
        if (s == null) return "[错误] 未连接";
        ReentrantLock lock = getDeviceLock(deviceName);
        lock.lock();
        try {
            for (String line : commands.split("\n")) {
                line = line.trim(); if (line.isEmpty()) continue;
                writeln(s, line);
                Thread.sleep(100); // 命令间隔 100ms 即可
            }
            String r = readUntilPrompt(s, 15000, 500);
            System.out.println("[Telnet] " + deviceName + " ← 批量(" + r.length() + "B)");
            return r;
        } catch (Exception e) {
            return "[错误] " + e.getMessage();
        } finally {
            lock.unlock();
        }
    }

    /** 安全发送：失败自动 ? 查设备 + AI 纠错 + 重试 */
    public String safeSend(String deviceName, String command) {
        ReentrantLock lock = getDeviceLock(deviceName);
        lock.lock();
        try {
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
        } finally {
            lock.unlock();
        }
    }

    public String queryDeviceInfo(String deviceName) { return safeSend(deviceName, "display version"); }

    /** 查 LLDP 邻居 */
    public String queryLldpNeighbors(String deviceName) {
        ReentrantLock lock = getDeviceLock(deviceName);
        lock.lock();
        try {
            String result = sendCommand(deviceName, "display lldp neighbor brief");
            // 如果没开启 LLDP，开启后再查
            if (result.contains("not enabled") || result.contains("LLDP is not")) {
                sendCommand(deviceName, "system-view");
                sendCommand(deviceName, "lldp enable");
                sendCommand(deviceName, "return");
                result = sendCommand(deviceName, "display lldp neighbor brief");
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    public String queryCurrentConfig(String deviceName) {
        safeSend(deviceName, "return");
        String cfg = sendCommand(deviceName, "display current-configuration");
        if (cfg != null && !cfg.isBlank() && !cfg.startsWith("[错误]")) cachedConfig.put(deviceName, cfg);
        System.out.println("[Telnet] queryCurrentConfig " + deviceName + " → " + (cfg != null ? cfg.length() + "B" : "null"));
        return cfg;
    }

    /** 轻量配置查询：5 个命令覆盖核心网络信息（持锁保证心跳不插入） */
    public String queryLightConfig(String deviceName, String deviceType) {
        ReentrantLock lock = getDeviceLock(deviceName);
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("--- ip interface brief ---\n");
            sb.append(sendCommand(deviceName, "display ip interface brief"));
            sb.append("\n--- routing-table ---\n");
            sb.append(sendCommand(deviceName, "display ip routing-table"));
            if ("switch".equals(deviceType)) {
                sb.append("\n--- vlan ---\n");
                sb.append(sendCommand(deviceName, "display vlan"));
            }
            if ("firewall".equals(deviceType)) {
                sb.append("\n--- firewall zone ---\n");
                sb.append(sendCommand(deviceName, "display firewall zone"));
                sb.append("\n--- security-policy ---\n");
                sb.append(sendCommand(deviceName, "display security-policy rule all"));
            }
            System.out.println("[Telnet] queryLightConfig " + deviceName + "(" + deviceType + ") → " + sb.length() + "B");
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }

    public String getCachedConfig(String deviceName) { return cachedConfig.get(deviceName); }
    public void updateCache(String deviceName, String config) { cachedConfig.put(deviceName, config); }
    public Set<String> getAndClearPwdChanged() {
        Set<String> copy = new HashSet<>(pwdChangedDevices);
        pwdChangedDevices.clear(); return copy;
    }

    /** 读取命令输出直到出现提示符（自动翻页），返回去空白后的完整输出 */
    private String readUntilPrompt(TelnetSession s, long timeoutMs, int maxPages) {
        StringBuilder result = new StringBuilder();
        int morePages = 0, emptyRounds = 0;
        while (morePages < maxPages) {
            String chunk;
            try {
                chunk = waitFor(s.reader, new String[]{">", "Error:", "---- More ----"}, (int) timeoutMs);
            } catch (IOException e) {
                break;
            }
            if (chunk.isEmpty()) { if (++emptyRounds >= 2) break; }
            else emptyRounds = 0;
            result.append(chunk);
            String r = result.toString();
            if (r.strip().endsWith(">") || r.strip().endsWith("]")) break;
            if (r.contains("Error:") || r.contains("Unrecognized command")) break;
            if (r.contains("---- More ----")) {
                try {
                    s.client.getOutputStream().write(' ');
                    s.client.getOutputStream().flush();
                    Thread.sleep(200);
                } catch (Exception e) { break; }
                morePages++;
                continue;
            }
            break;
        }
        return result.toString().strip();
    }

    /** 连接全新防火墙（默认密码），自动处理改密码流程 */
    public boolean connectNewFirewall(String deviceName, int port) {
        return connectNewFirewall(deviceName, enspHost, port);
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
            TelnetSession session = new TelnetSession(host, port, deviceName, client, writer, reader);

            // 等设备就绪
            Thread.sleep(1000);
            for (int i = 0; i < 5; i++) { writeln(session, ""); Thread.sleep(800); }
            String bootOutput = readAvailable(reader);

            // 防火墙休眠唤醒
            if (bootOutput.contains("Press ENTER") || bootOutput.contains("ENTER")) {
                System.out.println("[Telnet] " + deviceName + " 休眠，按回车唤醒...");
                writeln(session, "");
                Thread.sleep(2000);
                readAvailable(reader);
            }

            // Step 1: 等待登录提示，输入用户名
            waitFor(reader, new String[]{"Username", "username", "login"}, 10000);
            System.out.println("[Telnet] " + deviceName + " 输入用户名...");
            writeln(session, "admin");
            // Step 2: 等待密码提示，输入默认密码
            waitFor(reader, new String[]{"Password", "password"}, 8000);
            System.out.println("[Telnet] " + deviceName + " 输入密码...");
            writeln(session, "Admin@123");
            // Step 3: 等结果
            String postLogin = waitFor(reader, new String[]{"Change now", "needs to be changed", "fail", "incorrect", "Error:", ">"}, 10000);
            System.out.println("[Telnet] 登录响应: " + postLogin.substring(0, Math.min(120, postLogin.length())).replace('\n',' '));

            // > = 已登录；Change now = 需改密（也是登录成功）；fail/error = 真失败
            boolean needChangePwd = postLogin.contains("Change now") || postLogin.contains("needs to be changed");
            boolean hasPrompt = postLogin.contains(">");
            boolean hasError = postLogin.toLowerCase().contains("fail") || postLogin.toLowerCase().contains("incorrect");
            if (!needChangePwd && !hasPrompt && hasError) {
                System.out.println("[Telnet] " + deviceName + " 登录失败");
                return false;
            }
            if (!needChangePwd && !hasPrompt && !hasError) {
                System.out.println("[Telnet] " + deviceName + " 登录响应异常，放弃");
                return false;
            }

            // Step 4: 改密码流程
            if (postLogin.contains("Change now") || postLogin.contains("needs to be changed")) {
                System.out.println("[Telnet] " + deviceName + " 改密码...");
                writeln(session, "y");
                waitFor(reader, new String[]{"old password", "Old password"}, 8000);
                writeln(session, "Admin@123");  // 旧密码
                waitFor(reader, new String[]{"new password", "New password"}, 8000);
                writeln(session, "admin@123");  // 新密码
                waitFor(reader, new String[]{"confirm", "Confirm"}, 8000);
                writeln(session, "admin@123");  // 确认
                Thread.sleep(2000);
                String finalResp = readAvailable(reader);
                if (!finalResp.contains(">")) {
                    System.out.println("[Telnet] 改密码后未进入命令行");
                    return false;
                }
                System.out.println("[Telnet] " + deviceName + " 密码→admin@123");
                pwdChangedDevices.add(deviceName);
            }

            // 验证：必须出现命令行提示符 >
            writeln(session, "");
            Thread.sleep(1000);
            String verify = readAvailable(reader);
            if (!verify.contains(">")) {
                System.out.println("[Telnet] 登录验证失败(" + verify.length() + "B): " + verify.replace('\n',' '));
                return false;
            }

            session.user = "admin"; session.password = "admin@123";
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
        if (sessions.containsKey(newName)) {
            System.out.println("[Telnet] 重命名失败 " + oldName + " → " + newName + " (目标名已存在)");
            return;
        }
        TelnetSession s = sessions.remove(oldName);
        if (s != null) { sessions.put(newName, s); System.out.println("[Telnet] 重命名 " + oldName + " → " + newName); }
    }

    public void disconnect(String name) {
        TelnetSession s = sessions.remove(name);
        if (s != null) {
            s.disconnect();
            System.out.println("[Telnet] 已断开 " + name);
        }
        deviceLocks.remove(name);
        cachedConfig.remove(name);
    }

    /** 心跳保活：每 3 分钟对已连接设备发空回车，防止空闲超时断开（设备忙时跳过，避免干扰 AI 命令） */
    @Scheduled(fixedRate = 180000)
    public void heartbeat() {
        for (TelnetSession s : sessions.values()) {
            ReentrantLock lock = getDeviceLock(s.deviceName);
            if (!lock.tryLock()) {
                System.out.println("[Telnet] " + s.deviceName + " 心跳跳过(设备忙)");
                continue;
            }
            try {
                writeln(s, "");
                Thread.sleep(100);
                String resp = readAvailable(s.reader, 1000, 3);
                // 检查是否已掉线（出现登录提示）
                if (resp.contains("Username") || resp.contains("login")) {
                    System.out.println("[Telnet] " + s.deviceName + " 心跳发现已掉线，尝试重连...");
                    tryReconnect(s);
                }
            } catch (Exception ignored) {
            } finally {
                lock.unlock();
            }
        }
    }

    private boolean tryReconnect(TelnetSession s) {
        try {
            String user = s.user != null ? s.user : "admin";
            String pwd = s.password;
            writeln(s, user);
            waitFor(s.reader, new String[]{"Password", "password"}, 5000);
            if (pwd == null) {
                System.out.println("[Telnet] " + s.deviceName + " 无密码，无法重登");
                return false;
            }
            writeln(s, pwd);
            Thread.sleep(2000);
            String result = readAvailable(s.reader);
            if (!result.contains(">")) {
                System.out.println("[Telnet] " + s.deviceName + " 重登失败(" + result.length() + "B)");
                return false;
            }
            System.out.println("[Telnet] " + s.deviceName + " 重登成功");
            return true;
        } catch (Exception e) {
            System.err.println("[Telnet] " + s.deviceName + " 重登异常: " + e.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void disconnectAll() {
        for (String name : new ArrayList<>(sessions.keySet())) disconnect(name);
    }

    /** 发送一行：分块写入 + flush，防止 Telnet 协议吃掉字符（4字符/批，比逐字符快 10 倍） */
    private void writeln(TelnetSession s, String line) throws IOException {
        OutputStream os = s.client.getOutputStream();
        for (int i = 0; i < line.length(); i += 4) {
            int end = Math.min(i + 4, line.length());
            for (int j = i; j < end; j++) os.write(line.charAt(j));
            os.flush();
            try { Thread.sleep(2); } catch (InterruptedException ignored) {}
        }
        os.write('\r');
        os.write('\n');
        os.flush();
        System.out.println("[Telnet-WRITE] " + s.deviceName + " ← " + line);
    }

    private String readAvailable(BufferedReader reader) throws IOException {
        return readAvailable(reader, 5000, 20);
    }

    /** 短超时版：最多等 maxWaitMs，连续 emptyPollThreshold 次(每次50ms)无数据即返回 */
    private String readAvailable(BufferedReader reader, long maxWaitMs, int emptyPollThreshold) throws IOException {
        StringBuilder sb = new StringBuilder();
        long start = System.currentTimeMillis();
        int emptyPolls = 0;
        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (reader.ready()) {
                char[] buf = new char[4096]; int n = reader.read(buf);
                if (n > 0) { sb.append(buf, 0, n); start = System.currentTimeMillis(); emptyPolls = 0; }
            } else if (sb.length() > 0) {
                emptyPolls++;
                if (emptyPolls > emptyPollThreshold) break;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        String raw = sb.toString();
        String cleaned = raw.replaceAll("\\[[;\\d]*[A-Za-z]", "").replaceAll("\\r", "\n").replaceAll("\n{3,}", "\n\n").trim();
        if (raw.length() > 0 && !raw.equals(cleaned) && raw.length() < 200) {
            System.out.println("[Telnet-RAW] raw=" + raw.replace("\r","\\r").replace("\n","\\n").replace("","ESC"));
        }
        return cleaned;
    }
}
