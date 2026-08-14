package com.topo.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 测试
 *
 * 核心价值：验证 @Tool 注解生成的 Function Calling schema 参数 key
 * 与 ChatService.deviceOf() 解析用的 "device_name" 一致（LangChain4j 迁移的关键假设）。
 */
class ToolRegistryTest {

    private final ToolRegistry registry = new ToolRegistry(null); // 本测试不碰 Telnet

    // ========== 1. Function Calling schema 生成 ==========

    @Test
    void 工具注册生成四个工具Schema() {
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(registry);
        assertEquals(4, specs.size(), "应有 4 个工具");
        Map<String, ToolSpecification> byName = specs.stream()
            .collect(Collectors.toMap(ToolSpecification::name, Function.identity()));
        assertTrue(byName.containsKey("queryDeviceInfo"));
        assertTrue(byName.containsKey("queryCurrentConfig"));
        assertTrue(byName.containsKey("sendConfig"));
        assertTrue(byName.containsKey("sendCommand"));
    }

    @Test
    void 工具Schema参数key必须是snake_case() {
        // ChatService.deviceOf() 从 arguments JSON 里取 "device_name"，
        // 这里验证 @P 生成的 schema 属性名与之一致（依赖 -parameters 编译保留参数名）
        ToolSpecification spec = ToolSpecifications.toolSpecificationsFrom(registry).stream()
            .filter(s -> s.name().equals("sendCommand"))
            .findFirst().orElseThrow();
        String json = spec.toJson();
        System.out.println("[TEST] sendCommand schema: " + json);
        assertTrue(json.contains("device_name"), "schema 应包含 device_name 参数: " + json);
        assertTrue(json.contains("command"), "schema 应包含 command 参数: " + json);

        ToolSpecification spec2 = ToolSpecifications.toolSpecificationsFrom(registry).stream()
            .filter(s -> s.name().equals("sendConfig"))
            .findFirst().orElseThrow();
        String json2 = spec2.toJson();
        assertTrue(json2.contains("device_name"), "sendConfig 应包含 device_name: " + json2);
        assertTrue(json2.contains("commands"), "sendConfig 应包含 commands 数组: " + json2);
    }

    // ========== 2. 工具参数映射（DefaultToolExecutor 反射调用链路） ==========

    /** 最小 @Tool 类，验证 @P + snake_case 参数名的完整映射 */
    static class DummyDevice {
        @Tool(name = "sendCommand", value = "测试工具")
        public String sendCommand(@P("拓扑中的设备名") String device_name, @P("完整命令") String command) {
            return device_name + "|" + command;
        }

        @Tool(name = "sendConfig", value = "测试批量工具")
        public String sendConfig(@P("拓扑中的设备名") String device_name, @P("配置命令数组") List<String> commands) {
            return device_name + "|" + String.join(",", commands);
        }
    }

    @Test
    void 工具调用JSON参数映射到方法参数() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
            .name("sendCommand")
            .arguments("{\"device_name\":\"R1\",\"command\":\"display version\"}")
            .build();
        String result = new DefaultToolExecutor(new DummyDevice(), req).execute(req, null);
        assertEquals("R1|display version", result, "snake_case JSON key 应正确映射到方法参数");
    }

    @Test
    void 工具调用List参数映射() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
            .name("sendConfig")
            .arguments("{\"device_name\":\"SW1\",\"commands\":[\"system-view\",\"vlan 10\",\"return\"]}")
            .build();
        String result = new DefaultToolExecutor(new DummyDevice(), req).execute(req, null);
        assertEquals("SW1|system-view,vlan 10,return", result);
    }

    // ========== 3. 命令修复 ==========

    @Test
    void 修复命令粘连_ipaddress() {
        assertEquals("ip address 192.168.1.1 24", registry.fixCommandSpacing("ip address192.168.1.1 24"));
        assertEquals("ip address 192.168.1.1 24", registry.fixCommandSpacing("ipaddress192.168.1.1 24"));
    }

    @Test
    void 修复命令粘连_sysname与interface() {
        assertEquals("sysname AR1", registry.fixCommandSpacing("sysnameAR1"));
        assertEquals("interface GigabitEthernet0/0/1", registry.fixCommandSpacing("interfaceGigabitEthernet0/0/1"));
    }

    @Test
    void 修复命令粘连_常用组合() {
        assertEquals("undo shutdown", registry.fixCommandSpacing("undoshutdown"));
        assertEquals("ip route-static 192.168.2.0 24 10.0.0.1", registry.fixCommandSpacing("iproute-static192.168.2.0 24 10.0.0.1"));
        assertEquals("firewall zone trust", registry.fixCommandSpacing("firewallzone trust"));
        assertEquals("add interface GigabitEthernet0/0/1", registry.fixCommandSpacing("addinterface GigabitEthernet0/0/1"));
        assertEquals("ospf 1", registry.fixCommandSpacing("ospf1"));
        assertEquals("acl 3000", registry.fixCommandSpacing("acl3000"));
        assertEquals("port link-type trunk", registry.fixCommandSpacing("portlink-type trunk"));
        assertEquals("port default vlan 10", registry.fixCommandSpacing("portdefaultvlan 10"));
        assertEquals("port trunk allow-pass vlan 10 20", registry.fixCommandSpacing("porttrunkallow-pass vlan 10 20"));
        assertEquals("vlan batch 10 20", registry.fixCommandSpacing("vlanbatch 10 20"));
    }

    @Test
    void 修复IP粘连不影响正常命令() {
        assertEquals("ip address 192.168.1.1 24", registry.fixCommandSpacing("ip address 192.168.1.1 24"));
        assertEquals("display ip interface brief", registry.fixCommandSpacing("display ip interface brief"));
    }

    // ========== 4. 旧格式 tool_call 剥离 ==========

    @Test
    void 剥离围栏tool_call块() {
        String text = "我先查询一下设备。\n```json\n{\"reasoning\":\"查询\",\"tool_call\":{\"name\":\"sendCommand\",\"params\":{\"device_name\":\"R1\"}}}\n```\n查询完成。";
        String stripped = registry.stripToolCallBlocks(text);
        assertFalse(stripped.contains("tool_call"), "JSON 块应被移除: " + stripped);
        assertTrue(stripped.contains("查询完成"), "正常文本应保留: " + stripped);
    }

    @Test
    void 剥离裸JSON_tool_call() {
        String text = "分析中 {\"tool_call\":{\"name\":\"sendCommand\",\"params\":{\"device_name\":\"R1\",\"command\":\"display version\"}}} 然后总结";
        String stripped = registry.stripToolCallBlocks(text);
        assertFalse(stripped.contains("tool_call"), "裸 JSON 应被移除: " + stripped);
        assertTrue(stripped.contains("然后总结"), "正常文本应保留: " + stripped);
    }

    @Test
    void 提取嵌套JSON完整匹配括号() {
        String text = "a {\"tool_call\":{\"name\":\"x\",\"params\":{\"k\":[1,2]}}} b {\"tool_call\":{\"name\":\"y\",\"params\":{}}}";
        List<String> calls = registry.extractAllToolCalls(text);
        assertEquals(2, calls.size(), "应提取 2 个调用块");
        assertTrue(calls.get(0).contains("\"name\":\"x\""), "第 1 块: " + calls.get(0));
        assertTrue(calls.get(0).endsWith("}}"), "括号应完整闭合: " + calls.get(0));
    }

    @Test
    void 无tool_call时剥离是幂等的() {
        assertEquals(null, registry.stripToolCallBlocks(null));
        String normal = "普通的回答，没有任何工具调用。";
        assertEquals(normal, registry.stripToolCallBlocks(normal));
    }
}
