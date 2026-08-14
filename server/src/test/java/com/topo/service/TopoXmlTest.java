package com.topo.service;

import com.topo.model.vo.TopologyJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * .topo XML 往返测试：TopologyJson → TopoXmlWriter → UTF-16LE 字节 → TopoXmlParser → TopologyJson
 *
 * 模拟真实 eNSP 导入导出流程（.topo 文件是带 BOM 的 UTF-16LE 编码）。
 */
class TopoXmlTest {

    private final TopoXmlWriter writer = new TopoXmlWriter();
    private final TopoXmlParser parser = new TopoXmlParser();

    private TopologyJson buildTopo() {
        TopologyJson topo = new TopologyJson();
        topo.setDevices(new ArrayList<>());
        topo.setConnections(new ArrayList<>());

        TopologyJson.Device r1 = new TopologyJson.Device();
        r1.setName("R1");
        r1.setModel("AR2220");
        r1.setX(520);
        r1.setY(260);
        r1.setInterfaces(new ArrayList<>(List.of("GE0/0/0", "GE0/0/1", "GE0/0/2")));
        topo.getDevices().add(r1);

        TopologyJson.Device s1 = new TopologyJson.Device();
        s1.setName("LSW1");
        s1.setModel("S5700");
        s1.setX(260);
        s1.setY(520);
        List<String> s1Ifaces = new ArrayList<>();
        for (int i = 1; i <= 24; i++) s1Ifaces.add("GE0/0/" + i);
        s1.setInterfaces(s1Ifaces);
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
    void 写出再解析设备信息完整保留() throws Exception {
        String xml = writer.write(buildTopo());
        assertTrue(xml.contains("encoding=\"UNICODE\""), "eNSP 需要 UNICODE 声明");

        // 真实 .topo 文件：UTF-16LE + BOM
        byte[] bom = new byte[]{(byte) 0xFF, (byte) 0xFE};
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_16LE);
        byte[] fileBytes = new byte[bom.length + xmlBytes.length];
        System.arraycopy(bom, 0, fileBytes, 0, bom.length);
        System.arraycopy(xmlBytes, 0, fileBytes, bom.length, xmlBytes.length);

        TopologyJson parsed = parser.parse(fileBytes);
        assertNotNull(parsed.getDevices());
        assertEquals(2, parsed.getDevices().size());

        TopologyJson.Device r1 = parsed.getDevices().get(0);
        assertEquals("R1", r1.getName());
        assertEquals("AR2220", r1.getModel());
        assertEquals("router", r1.getType());
        assertEquals(List.of("GE0/0/0", "GE0/0/1", "GE0/0/2"), r1.getInterfaces(),
            "AR2220 接口顺序必须与画布一致");

        TopologyJson.Device s1 = parsed.getDevices().get(1);
        assertEquals("LSW1", s1.getName());
        assertEquals("S5700", s1.getModel());
        assertEquals("switch", s1.getType());
        assertEquals(24, s1.getInterfaces().size(), "S5700 应有 24 个接口");
        assertEquals("GE0/0/1", s1.getInterfaces().get(0), "S5700 从 GE0/0/1 起始");
        assertEquals("GE0/0/24", s1.getInterfaces().get(23));
    }

    @Test
    void 连线信息往返保留() throws Exception {
        String xml = writer.write(buildTopo());
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_16LE);
        TopologyJson parsed = parser.parse(bytes);

        assertNotNull(parsed.getConnections());
        assertEquals(1, parsed.getConnections().size());
        TopologyJson.Connection c = parsed.getConnections().get(0);
        assertEquals("R1", c.getFromDevice());
        assertEquals("GE0/0/0", c.getFromInterface());
        assertEquals("LSW1", c.getToDevice());
        assertEquals("GE0/0/1", c.getToInterface());
    }

    @Test
    void 控制口分配连续递增() throws Exception {
        // writer 把 com_port 写进 XML；真实链路是 eNSP 打开后重新导入时 parser 读回
        String xml = writer.write(buildTopo());
        TopologyJson parsed = parser.parse(xml.getBytes(StandardCharsets.UTF_16LE));
        assertNotEquals(0, parsed.getDevices().get(0).getComPort(), "AR2220 应有 Console 端口");
        assertNotEquals(0, parsed.getDevices().get(1).getComPort(), "S5700 应有 Console 端口");
        assertNotEquals(parsed.getDevices().get(0).getComPort(), parsed.getDevices().get(1).getComPort(),
            "两个设备的 Console 端口不能相同");
    }

    @Test
    void 型号注册表完整覆盖() {
        assertTrue(TopoXmlWriter.allModelNames().containsAll(
            List.of("AR2220", "S5700", "USG6000V", "USG5500", "NE40E", "CE6800", "PC", "AC6005", "AP2050", "Cloud")),
            "型号注册表应覆盖常用设备");
        assertEquals("switch", TopoXmlWriter.modelType("S5700"));
        assertEquals("switch", TopoXmlWriter.modelType("CE6800"));
        assertEquals("router", TopoXmlWriter.modelType("AR2220"));
        assertEquals("router", TopoXmlWriter.modelType("NE40E"));
        assertEquals("firewall", TopoXmlWriter.modelType("USG6000V"));
        assertEquals("firewall", TopoXmlWriter.modelType("USG5500"));
        assertEquals("wireless", TopoXmlWriter.modelType("AC6005"));
        assertEquals("terminal", TopoXmlWriter.modelType("PC"));
        assertEquals("unknown", TopoXmlWriter.modelType("不存在的型号"));
    }
}
