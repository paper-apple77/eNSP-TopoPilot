package com.topo.service;

import com.topo.model.vo.TopologyJson;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * .topo XML 反写器
 *
 * 把 TopologyJson 转成 eNSP 能打开的 .topo 文件内容。
 * 生成的 XML 结构与 eNSP v1.3 兼容。
 */
@Component
public class TopoXmlWriter {

    /**
     * TopologyJson → .topo XML 字符串
     */
    public String write(TopologyJson topo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UNICODE\" ?>\n");
        sb.append("<topo version=\"1.3.00.100\">\n");

        // 设备
        sb.append("    <devices>\n");
        List<String> deviceIds = new ArrayList<>();
        int portCounter = 1;
        if (topo.getDevices() != null) {
            for (TopologyJson.Device d : topo.getDevices()) {
                String id = d.getId() != null ? d.getId() : UUID.randomUUID().toString().toUpperCase();
                d.setId(id);  // 同步到设备对象，让 getDeviceId 能找到
                deviceIds.add(id);
                String model = d.getModel() != null ? d.getModel() : "AR2220";
                double cx = d.getX() > 0 ? d.getX() / 1.3 : 400; // undo display scale
                double cy = d.getY() > 0 ? d.getY() / 1.3 : 300;
                String mac = generateMac(model);
                int comPort = COM_PORT_DEVICES.contains(model) ? 2000 + portCounter++ : 0;

                String cxStr = String.format("%.6f", cx).replace(" ", "");
                String cyStr = String.format("%.6f", cy).replace(" ", "");
                String elStr = String.format("%.6f", cx + 25).replace(" ", "");
                String etStr = String.format("%.6f", cy + 50).replace(" ", "");
                sb.append(String.format("        <dev id=\"%s\" name=\"%s\" poe=\"0\" model=\"%s\" " +
                    "settings=\"\" system_mac=\"%s\" com_port=\"%d\" bootmode=\"1\" " +
                    "cx=\"%s\" cy=\"%s\" edit_left=\"%s\" edit_top=\"%s\">\n",
                    id, d.getName(), model, mac, comPort,
                    cxStr, cyStr, elStr, etStr));

                writeInterfaces(sb, model);
                sb.append("        </dev>\n");
            }
        }
        sb.append("    </devices>\n");

        // 连线
        sb.append("    <lines>\n");
        if (topo.getConnections() != null) {
            for (TopologyJson.Connection c : topo.getConnections()) {
                int srcIdx = getInterfaceIndex(topo, c.getFromDevice(), c.getFromInterface());
                int tarIdx = getInterfaceIndex(topo, c.getToDevice(), c.getToInterface());
                String srcDevId = getDeviceId(topo, c.getFromDevice());
                String dstDevId = getDeviceId(topo, c.getToDevice());

                // 判断线型
                String lineName = "Copper";
                if ("Console".equals(c.getFromInterface()) || "RS232".equals(c.getFromInterface())
                    || "Console".equals(c.getToInterface()) || "RS232".equals(c.getToInterface())) {
                    lineName = "CTL";
                }

                sb.append(String.format("        <line srcDeviceID=\"%s\" destDeviceID=\"%s\">\n", srcDevId, dstDevId));
                sb.append(String.format("            <interfacePair lineName=\"%s\" srcIndex=\"%d\" tarIndex=\"%d\" " +
                    "srcBoundRectIsMoved=\"0\" tarBoundRectIsMoved=\"0\" />\n", lineName, srcIdx, tarIdx));
                sb.append("        </line>\n");
            }
        }
        sb.append("    </lines>\n");

        sb.append("    <shapes />\n");
        sb.append("    <txttips />\n");
        sb.append("</topo>\n");
        return sb.toString();
    }

    // ====== 设备接口规格（基于 eNSP v1.3 参考文件） ======

    /** 型号 → 接口规格 */
    record IfSpec(String type, int count) {}

    /** 防火墙/NE/CE/CX：slot id="1" category 模式 */
    private static final Set<String> CATEGORY_MODE = Set.of("USG6000V");
    private static final Set<String> CATEGORY_10GE = Set.of("NE40E", "NE5000E", "NE9000", "CX");
    private static final Set<String> CATEGORY_CE_GE = Set.of("CE6800");
    private static final Map<String, Integer> CATEGORY_CE_COUNT = Map.of("CE6800", 20, "CE12800", 10);

    /** 交换机（count 模式） */
    private static final Map<String, List<IfSpec>> SWITCH_SPECS = Map.ofEntries(
        Map.entry("S5700", List.of(new IfSpec("GE", 24))),
        Map.entry("S3700", List.of(new IfSpec("Ethernet", 22), new IfSpec("GE", 2)))
    );

    /** 路由器（count 模式） */
    private static final Map<String, List<IfSpec>> ROUTER_SPECS = Map.ofEntries(
        Map.entry("AR201", List.of(new IfSpec("Ethernet", 9))),
        Map.entry("AR1220", List.of(new IfSpec("GE", 2), new IfSpec("Ethernet", 8))),
        Map.entry("AR2220", List.of(new IfSpec("GE", 1), new IfSpec("GE", 2))),
        Map.entry("AR2240", List.of(new IfSpec("GE", 1), new IfSpec("GE", 2))),
        Map.entry("AR3260", List.of(new IfSpec("GE", 1), new IfSpec("GE", 2))),
        Map.entry("Router", List.of(new IfSpec("Ethernet", 2), new IfSpec("GE", 4), new IfSpec("Serial", 4)))
    );

    /** 防火墙 count 模式（USG5500 等非 USG6000V） */
    private static final Map<String, List<IfSpec>> FW_COUNT_SPECS = Map.of(
        "USG5500", List.of(new IfSpec("GE", 9))
    );

    /** AC/AP/AD/SAP 无线设备 */
    private static final Map<String, List<IfSpec>> WIRELESS_SPECS = Map.ofEntries(
        Map.entry("AC6005", List.of(new IfSpec("GE", 8))),
        Map.entry("AC6605", List.of(new IfSpec("GE", 24))),
        Map.entry("AP2050", List.of(new IfSpec("GE", 5))),
        Map.entry("AP3030", List.of(new IfSpec("GE", 1))),
        Map.entry("AP4030", List.of(new IfSpec("GE", 2))),
        Map.entry("AP4050", List.of(new IfSpec("GE", 2))),
        Map.entry("AP7030", List.of(new IfSpec("GE", 2))),
        Map.entry("AP7050", List.of(new IfSpec("GE", 2))),
        Map.entry("AP9131", List.of(new IfSpec("GE", 2))),
        Map.entry("AD9430", List.of(new IfSpec("GE", 28))),
        Map.entry("R250D", List.of(new IfSpec("GE", 1)))
    );

    /** 终端设备 */
    private static final Map<String, List<IfSpec>> TERMINAL_SPECS = Map.ofEntries(
        Map.entry("PC", List.of(new IfSpec("Ethernet", 1))),
        Map.entry("MCS", List.of(new IfSpec("Ethernet", 1))),
        Map.entry("Client", List.of(new IfSpec("Ethernet", 1))),
        Map.entry("Server", List.of(new IfSpec("Ethernet", 1)))
    );

    /** 其他网络设备 */
    private static final Map<String, List<IfSpec>> OTHER_SPECS = Map.ofEntries(
        Map.entry("Cloud", List.of(new IfSpec("Ethernet", 0), new IfSpec("GE", 0), new IfSpec("Serial", 0))),
        Map.entry("FRSW", List.of(new IfSpec("Serial", 16))),
        Map.entry("HUB", List.of(new IfSpec("Ethernet", 16))),
        Map.entry("STA", List.of()),
        Map.entry("Cellphone", List.of())
    );

    /** 全部已知型号名（用于 display version 回显识别设备型号） */
    public static Set<String> allModelNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(CATEGORY_MODE);
        names.addAll(CATEGORY_10GE);
        names.addAll(CATEGORY_CE_COUNT.keySet());
        names.addAll(SWITCH_SPECS.keySet());
        names.addAll(ROUTER_SPECS.keySet());
        names.addAll(FW_COUNT_SPECS.keySet());
        names.addAll(WIRELESS_SPECS.keySet());
        names.addAll(TERMINAL_SPECS.keySet());
        names.addAll(OTHER_SPECS.keySet());
        return names;
    }

    /** 型号 → 设备大类（switch/router/firewall/wireless/terminal） */
    public static String modelType(String model) {
        if (model == null) return "unknown";
        if (CATEGORY_MODE.contains(model) || FW_COUNT_SPECS.containsKey(model)) return "firewall";
        if ("CX".equals(model) || CATEGORY_CE_COUNT.containsKey(model) || SWITCH_SPECS.containsKey(model)) return "switch";
        if (CATEGORY_10GE.contains(model) || ROUTER_SPECS.containsKey(model)) return "router";
        if (WIRELESS_SPECS.containsKey(model)) return "wireless";
        if (TERMINAL_SPECS.containsKey(model)) return "terminal";
        return "unknown";
    }

    /** 写设备接口 */
    private void writeInterfaces(StringBuilder sb, String model) {
        if (model == null) model = "AR2220";

        // 防火墙 USG6000V：category 模式
        if ("USG6000V".equals(model)) {
            sb.append("            <slot id=\"1\">\n");
            sb.append("                <interface category=\"Ethernet\" type=\"GE\" slotIndex=\"0\" cardIndex=\"0\" interfaceIndex=\"0\" />\n");
            for (int i = 0; i <= 6; i++) {
                sb.append(String.format("                <interface category=\"Ethernet\" type=\"GE\" slotIndex=\"1\" cardIndex=\"0\" interfaceIndex=\"%d\" />\n", i));
            }
            sb.append("            </slot>\n");
            return;
        }

        // NE/CX: category 模式 10 Ethernet
        if (CATEGORY_10GE.contains(model)) {
            sb.append("            <slot id=\"1\">\n");
            for (int i = 0; i <= 9; i++) {
                sb.append(String.format("                <interface category=\"Ethernet\" type=\"Ethernet\" slotIndex=\"1\" cardIndex=\"0\" interfaceIndex=\"%d\" />\n", i));
            }
            sb.append("            </slot>\n");
            return;
        }

        // CE: category 模式 GE
        Integer ceCount = CATEGORY_CE_COUNT.get(model);
        if (ceCount != null) {
            sb.append("            <slot id=\"1\">\n");
            for (int i = 0; i < ceCount; i++) {
                sb.append(String.format("                <interface category=\"Ethernet\" type=\"GE\" slotIndex=\"1\" cardIndex=\"0\" interfaceIndex=\"%d\" />\n", i));
            }
            sb.append("            </slot>\n");
            return;
        }

        // count 模式
        sb.append("            <slot number=\"slot17\" isMainBoard=\"1\">\n");
        List<IfSpec> specs = null;
        if (SWITCH_SPECS.containsKey(model)) specs = SWITCH_SPECS.get(model);
        else if (ROUTER_SPECS.containsKey(model)) specs = ROUTER_SPECS.get(model);
        else if (FW_COUNT_SPECS.containsKey(model)) specs = FW_COUNT_SPECS.get(model);
        else if (WIRELESS_SPECS.containsKey(model)) specs = WIRELESS_SPECS.get(model);
        else if (TERMINAL_SPECS.containsKey(model)) specs = TERMINAL_SPECS.get(model);
        else if (OTHER_SPECS.containsKey(model)) specs = OTHER_SPECS.get(model);
        else specs = List.of(new IfSpec("GE", 2)); // 兜底

        for (IfSpec spec : specs) {
            sb.append(String.format("                <interface sztype=\"Ethernet\" interfacename=\"%s\" count=\"%d\" />\n",
                spec.type, spec.count));
        }
        sb.append("            </slot>\n");
    }

    private int getInterfaceIndex(TopologyJson topo, String deviceName, String ifaceName) {
        if (topo.getDevices() == null) return 0;
        for (TopologyJson.Device d : topo.getDevices()) {
            if (d.getName().equals(deviceName) && d.getInterfaces() != null) {
                return d.getInterfaces().indexOf(ifaceName);
            }
        }
        return 0;
    }

    private String getDeviceId(TopologyJson topo, String deviceName) {
        if (topo.getDevices() == null) return deviceName;
        for (TopologyJson.Device d : topo.getDevices()) {
            if (d.getName().equals(deviceName)) return d.getId() != null ? d.getId() : deviceName;
        }
        return deviceName;
    }

    private static final Set<String> MAC_4C = Set.of("S5700", "S3700", "CE6800", "CE12800");
    private static final Set<String> MAC_54 = Set.of("PC", "Server", "Client", "MCS", "STA", "Cellphone");

    private String generateMac(String model) {
        Random r = new Random();
        if (model != null && MAC_4C.contains(model))
            return String.format("4C-1F-CC-%02X-%02X-%02X", r.nextInt(256), r.nextInt(256), r.nextInt(256));
        if (model != null && MAC_54.contains(model))
            return String.format("54-89-98-%02X-%02X-%02X", r.nextInt(256), r.nextInt(256), r.nextInt(256));
        return String.format("00-E0-FC-%02X-%02X-%02X", r.nextInt(256), r.nextInt(256), r.nextInt(256));
    }

    private static final Set<String> COM_PORT_DEVICES = Set.of(
        "USG6000V", "USG5500", "S5700", "S3700", "CE6800", "CE12800",
        "AR201", "AR1220", "AR2220", "AR2240", "AR3260", "Router",
        "NE40E", "NE5000E", "NE9000", "CX",
        "AC6005", "AC6605", "AP2050", "AP3030", "AP4030", "AP4050",
        "AP7030", "AP7050", "AP9131", "AD9430", "R250D"
    );
}
