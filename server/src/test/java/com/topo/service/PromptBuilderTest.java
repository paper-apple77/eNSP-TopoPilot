package com.topo.service;

import com.topo.model.vo.TopologyJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptBuilder 测试：系统提示词必须包含拓扑上下文和工具使用策略
 */
class PromptBuilderTest {

    static PromptBuilder builder;

    @BeforeAll
    static void setUp() {
        CommandKnowledgeService knowledge = new CommandKnowledgeService();
        knowledge.init(); // 手动触发 @PostConstruct 加载 knowledge/commands.json
        builder = new PromptBuilder(knowledge);
    }

    private TopologyJson buildTopo() {
        TopologyJson topo = new TopologyJson();
        topo.setDevices(new ArrayList<>());
        topo.setConnections(new ArrayList<>());

        TopologyJson.Device r1 = new TopologyJson.Device();
        r1.setName("R1");
        r1.setModel("AR2220");
        r1.setType("router");
        r1.setInterfaces(List.of("GE0/0/0", "GE0/0/1"));
        topo.getDevices().add(r1);

        TopologyJson.Device s1 = new TopologyJson.Device();
        s1.setName("LSW1");
        s1.setModel("S5700");
        s1.setType("switch");
        s1.setInterfaces(List.of("GE0/0/1"));
        topo.getDevices().add(s1);

        TopologyJson.Connection c = new TopologyJson.Connection();
        c.setFromDevice("R1");
        c.setFromInterface("GE0/0/0");
        c.setToDevice("LSW1");
        c.setToInterface("GE0/0/1");
        topo.getConnections().add(c);
        return topo;
    }

    @Test
    void 配网模式提示词包含拓扑与工具策略() {
        String prompt = builder.buildSystemPrompt(buildTopo(), "帮我把网络配好", "agent");
        assertTrue(prompt.contains("R1"), "应包含设备名");
        assertTrue(prompt.contains("LSW1"), "应包含设备名");
        assertTrue(prompt.contains("AR2220"), "应包含设备型号");
        assertTrue(prompt.contains("R1(GE0/0/0)"), "应包含连线信息");
        assertTrue(prompt.contains("sendConfig"), "应包含工具使用策略");
        assertTrue(prompt.contains("queryCurrentConfig"), "应包含工具使用策略");
        assertTrue(prompt.contains("不要一上来就查全量配置"), "应包含轻量查询优先的策略");
        assertTrue(prompt.contains("system-view"), "应包含批量配置格式要求");
    }

    @Test
    void 设计模式提示词包含拓扑JSON格式() {
        String prompt = builder.buildSystemPrompt(buildTopo(), "设计一个企业网络", "design");
        assertTrue(prompt.contains("```topo"), "应包含 topo 代码块格式");
        assertTrue(prompt.contains("addDevices"), "应包含 addDevices 字段");
        assertTrue(prompt.contains("addConnections"), "应包含 addConnections 字段");
        assertTrue(prompt.contains("S5700: GE0/0/1~24"), "应包含接口命名规则表");
        assertTrue(prompt.contains("用户需求: 设计一个企业网络"), "应包含用户需求");
    }

    @Test
    void 设计模式已有设备时提示修改策略() {
        String prompt = builder.buildSystemPrompt(buildTopo(), "加两台PC", "design");
        assertTrue(prompt.contains("clear:true"), "已有设备时应提示 clear 策略");
        assertTrue(prompt.contains("R1 [AR2220]"), "应列出已有设备");
    }
}
