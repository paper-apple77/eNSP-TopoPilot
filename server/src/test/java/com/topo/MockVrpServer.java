package com.topo;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 模拟华为 VRP 的 Telnet 服务器（单元测试用，不依赖 eNSP）
 *
 * 行为仿真真实 VRP：
 * - 连接即回显欢迎语 + 提示符 &lt;Huawei&gt;
 * - 支持 system-view / return 视图切换（[Huawei] / <Huawei>）
 * - display current-configuration 带 ---- More ---- 分页（等空格翻页）
 * - 记录所有收到的命令行，供测试断言字符是否完整送达
 */
public class MockVrpServer implements AutoCloseable {

    private final ServerSocket server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    public MockVrpServer() throws IOException {
        server = new ServerSocket(0); // 随机端口，避免冲突
        Thread t = new Thread(this::acceptLoop, "mock-vrp-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() { return server.getLocalPort(); }

    /** 本服务器收到过的所有命令行（按收到顺序，原始内容不去空白） */
    public List<String> received() { return received; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket sock = server.accept();
                // 注意：不能设 soTimeout，否则客户端 connect() 握手期间（readAvailable 空等 5s）
                // 无数据到达时 mock 会读超时断连。翻页等空格单独用 available() 轮询。
                Thread t = new Thread(() -> handle(sock), "mock-vrp-" + sock.getPort());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return; // server closed
            }
        }
    }

    private void handle(Socket sock) {
        try (InputStream in = sock.getInputStream(); OutputStream out = sock.getOutputStream()) {
            // 真实 VRP 连接后的回显
            out.write(("Huawei VRP (R) software, Version 8.180 (S5700 V200R010C00)\n" +
                       "Copyright (C) 2000-2020 Huawei Technologies Co., Ltd.\n" +
                       "<Huawei>").getBytes(StandardCharsets.UTF_8));
            out.flush();

            boolean sysView = false;
            StringBuilder line = new StringBuilder();
            while (running) {
                int b = in.read();
                if (b == -1) break;
                if (b == '\n') {
                    String cmd = line.toString();
                    if (cmd.endsWith("\r")) cmd = cmd.substring(0, cmd.length() - 1);
                    line.setLength(0);
                    received.add(cmd);
                    sysView = respond(out, in, cmd, sysView);
                } else {
                    line.append((char) b);
                }
            }
        } catch (IOException ignored) {
            // 客户端断开，正常退出
        }
    }

    /** 响应一条命令，返回新的视图状态（true=系统视图） */
    private boolean respond(OutputStream out, InputStream in, String cmd, boolean sysView) throws IOException {
        String prompt = sysView ? "[Huawei]" : "<Huawei>";
        switch (cmd) {
            case "":
                write(out, prompt);
                break;
            case "system-view":
                write(out, "Enter system view, return user view with Ctrl+Z.");
                write(out, "[Huawei]");
                return true;
            case "return":
                write(out, "<Huawei>");
                return false;
            case "display version":
                write(out, "Huawei Versatile Routing Platform Software\n" +
                           "VRP (R) software, Version 8.180 (S5700 V200R010C00)\n" +
                           "HUAWEI S5700 uptime is 0 week, 0 day, 0 hour");
                write(out, prompt);
                break;
            case "display current-configuration":
                sendPagedConfig(out, in, prompt);
                break;
            case "display ip interface brief":
                write(out, "Interface        IP Address/Mask      Physical   Protocol\n" +
                           "Vlanif1          192.168.1.1/24       up         up");
                write(out, prompt);
                break;
            case "display vlan":
                write(out, "The total number of VLANs is: 2");
                write(out, prompt);
                break;
            case "display ip routing-table":
                write(out, "Route Flags: R - relay, D - download to fib\n" +
                           "Destination/Mask    Proto   Pre  Cost   NextHop\n" +
                           "192.168.1.0/24      Direct  0    0      192.168.1.1");
                write(out, prompt);
                break;
            default:
                if (cmd.startsWith("sysname ")) {
                    // 改名命令：回显当前视图提示符即可
                    write(out, prompt);
                } else {
                    write(out, "% Unrecognized command found at '^' position.");
                    write(out, prompt);
                }
        }
        return sysView;
    }

    /** display current-configuration：100 行配置，每 25 行一个 ---- More ---- 分页 */
    private void sendPagedConfig(OutputStream out, InputStream in, String prompt) throws IOException {
        for (int i = 1; i <= 100; i++) {
            write(out, "# mock config line " + i);
            if (i % 25 == 0 && i < 100) {
                write(out, "  ---- More ----");
                // 等客户端发空格翻页（available 轮询，3s 超时兜底防挂死）
                if (!waitForSpace(in)) return;
            }
        }
        write(out, prompt);
    }

    /** 等待客户端发来任意字节（翻页空格），最多 3 秒 */
    private boolean waitForSpace(InputStream in) throws IOException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int b = in.read();
                return b != -1;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    private void write(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() {
        running = false;
        try { server.close(); } catch (IOException ignored) {}
    }
}
