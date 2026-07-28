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
                int comPort = (isFirewall(model) || isSwitch(model) || "router".equals(d.getType())) ? 2000 + portCounter++ : 0;
                if ("pc".equals(d.getType()) || "server".equals(d.getType()) || "client".equals(d.getType())) comPort = 0;

                sb.append(String.format("        <dev id=\"%s\" name=\"%s\" poe=\"0\" model=\"%s\" " +
                    "settings=\"\" system_mac=\"%s\" com_port=\"%d\" bootmode=\"1\" " +
                    "cx=\"%.6f\" cy=\"%.6f\" edit_left=\"%.6f\" edit_top=\"%.6f\">\n",
                    id, d.getName(), model, mac, comPort,
                    cx, cy, cx + 25, cy + 50));

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

    /** 写设备接口 */
    private void writeInterfaces(StringBuilder sb, String model) {
        if (isFirewall(model)) {
            sb.append("            <slot id=\"1\">\n");
            sb.append("                <interface category=\"Ethernet\" type=\"GE\" slotIndex=\"0\" cardIndex=\"0\" interfaceIndex=\"0\" />\n");
            for (int i = 0; i <= 6; i++) {
                sb.append(String.format("                <interface category=\"Ethernet\" type=\"GE\" slotIndex=\"1\" cardIndex=\"0\" interfaceIndex=\"%d\" />\n", i));
            }
            // 防火墙接口在 XML 里重复一遍（eNSP 原始格式）
            sb.append("                <interface category=\"Ethernet\" type=\"GE\" slotIndex=\"0\" cardIndex=\"0\" interfaceIndex=\"0\" />\n");
            for (int i = 0; i <= 6; i++) {
                sb.append(String.format("                <interface category=\"Ethernet\" type=\"GE\" slotIndex=\"1\" cardIndex=\"0\" interfaceIndex=\"%d\" />\n", i));
            }
            sb.append("            </slot>\n");
        } else {
            sb.append("            <slot number=\"slot17\" isMainBoard=\"1\">\n");
            if (isSwitch(model)) {
                sb.append("                <interface sztype=\"Ethernet\" interfacename=\"GE\" count=\"24\" />\n");
            } else if ("PC".equals(model)) {
                sb.append("                <interface sztype=\"Ethernet\" interfacename=\"Ethernet\" count=\"1\" />\n");
                sb.append("                <interface sztype=\"Ethernet\" interfacename=\"GE\" count=\"1\" />\n");
            } else if ("Server".equals(model) || "Client".equals(model)) {
                sb.append("                <interface sztype=\"Ethernet\" interfacename=\"Ethernet\" count=\"1\" />\n");
            } else if (model != null && model.startsWith("AR")) {
                sb.append("                <interface sztype=\"Ethernet\" interfacename=\"GE\" count=\"4\" />\n");
                sb.append("                <interface sztype=\"Ethernet\" interfacename=\"Ethernet\" count=\"4\" />\n");
            } else {
                sb.append("                <interface sztype=\"Ethernet\" interfacename=\"GE\" count=\"2\" />\n");
                sb.append("                <interface sztype=\"Ethernet\" interfacename=\"Ethernet\" count=\"8\" />\n");
            }
            sb.append("            </slot>\n");
        }
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

    private String generateMac(String model) {
        Random r = new Random();
        if (model != null && model.startsWith("S")) // 交换机 4C-1F-CC
            return String.format("4C-1F-CC-%02X-%02X-%02X", r.nextInt(256), r.nextInt(256), r.nextInt(256));
        if (model != null && (model.equals("PC") || model.equals("Server") || model.equals("Client"))) // PC 54-89-98
            return String.format("54-89-98-%02X-%02X-%02X", r.nextInt(256), r.nextInt(256), r.nextInt(256));
        // 防火墙/路由器 00-E0-FC
        return String.format("00-E0-FC-%02X-%02X-%02X", r.nextInt(256), r.nextInt(256), r.nextInt(256));
    }

    private boolean isFirewall(String model) { return model != null && (model.startsWith("USG") || model.contains("FW")); }
    private boolean isSwitch(String model) { return model != null && (model.startsWith("S") || model.startsWith("CE") || model.contains("LSW")); }
}
