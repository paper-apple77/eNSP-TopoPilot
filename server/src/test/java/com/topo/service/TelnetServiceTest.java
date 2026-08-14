package com.topo.service;

import com.topo.MockVrpServer;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TelnetService 核心链路测试 — 用 MockVrpServer 模拟 eNSP 设备
 *
 * 覆盖：连接握手、单命令查询、---- More ---- 分页翻页、
 * 批量命令送达、错误命令中断、4字符分块写入的字符完整性。
 */
class TelnetServiceTest {

    static MockVrpServer mock;
    static TelnetService ts;

    @BeforeAll
    static void setUp() throws Exception {
        mock = new MockVrpServer();
        ts = new TelnetService();
        // 无登录提示 → 直接注册会话
        assertTrue(ts.connect("SW1", "127.0.0.1", mock.port(), "admin", null), "连接 Mock VRP 失败");
    }

    @AfterAll
    static void tearDown() {
        ts.disconnectAll();
        mock.close();
    }

    @Test
    void 连接后设备在会话表中() {
        assertTrue(ts.isConnected("SW1"));
        assertEquals(List.of("SW1"), ts.getConnectedDevices().stream().toList());
    }

    @Test
    void 查询版本命令完整回显() {
        String r = ts.sendCommand("SW1", "display version");
        assertFalse(r.startsWith("[错误]"), "不应报错: " + r);
        assertTrue(r.contains("Huawei Versatile Routing Platform"), "应包含版本输出");
        assertTrue(r.contains("S5700"), "应包含设备型号");
        assertTrue(r.strip().endsWith(">"), "应以提示符结尾: " + r);
    }

    @Test
    void 长输出自动翻页读全() {
        String r = ts.sendCommand("SW1", "display current-configuration");
        assertFalse(r.startsWith("[错误]"), "不应报错: " + r);
        assertTrue(r.contains("# mock config line 1"), "应包含第 1 行配置");
        assertTrue(r.contains("# mock config line 100"), "应包含第 100 行配置（翻页 3 次后）");
        assertTrue(r.strip().endsWith(">"), "应以提示符结尾");
        // 翻页标记被正常处理，输出不含残留的 More
        assertFalse(r.endsWith("---- More ----"), "不应停在分页处");
    }

    @Test
    void 批量命令全部送达设备() {
        String r = ts.sendCommands("SW1", "system-view\nsysname CORE_TEST\nreturn");
        assertFalse(r.startsWith("[错误]"), "不应报错: " + r);
        List<String> received = mock.received();
        assertTrue(received.contains("system-view"), "应收到 system-view，实际: " + received);
        assertTrue(received.contains("sysname CORE_TEST"), "应收到 sysname 命令，实际: " + received);
        assertTrue(received.contains("return"), "应收到 return，实际: " + received);
        assertTrue(r.strip().endsWith(">"), "最终应回到用户视图提示符");
    }

    @Test
    void 未知命令回显错误并中断() {
        String r = ts.sendCommand("SW1", "bogus_command_xyz");
        assertTrue(r.contains("Unrecognized command"), "应包含未知命令错误: " + r);
    }

    @Test
    void 未连接设备返回错误() {
        assertEquals("[错误] 未连接", ts.sendCommand("NOT_EXIST", "display version"));
    }

    @Test
    void 长命令分块写入无字符丢失() {
        // 超过 4 字符会走 writeln 的 4字符/批分块路径
        String cmd = "sysname TopoPilot_SW_Test_2026_#01";
        ts.sendCommand("SW1", cmd);
        assertTrue(mock.received().contains(cmd), "设备收到的命令应逐字符完整，实际: " + mock.received());
    }

    @Test
    void 查询接口摘要与路由表() {
        String r = ts.sendCommand("SW1", "display ip interface brief");
        assertTrue(r.contains("192.168.1.1/24"), "应包含接口 IP: " + r);
        String r2 = ts.sendCommand("SW1", "display ip routing-table");
        assertTrue(r2.contains("192.168.1.0/24"), "应包含路由表项: " + r2);
    }
}
