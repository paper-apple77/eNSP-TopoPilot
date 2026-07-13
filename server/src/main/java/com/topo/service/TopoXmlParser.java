package com.topo.service;

import com.topo.model.vo.TopologyJson;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * eNSP .topo 文件 XML 解析器
 *
 * 解析 <topo> 根元素下的 <devices> 和 <lines>，
 * 提取设备（名称/型号/接口列表/坐标）和连线关系（两端设备+接口名）。
 *
 * 接口命名规则：
 *   交换机/PC/Client/Server: {interfacename}0/0/{序号}，序号从 0 开始累计
 *   防火墙(USG6000V):       {type}{slotIndex}/{cardIndex}/{interfaceIndex}
 *   路由器(AR2220):          {interfacename}0/0/{序号}（同交换机）
 */
@Component
public class TopoXmlParser {

    private static final Set<String> FIREWALL_MODELS = Set.of("USG6000V", "USG6000V1", "USG6000V2", "USG6600");
    private static final Set<String> SWITCH_MODELS   = Set.of("S5700", "S3700", "S6700");
    private static final Set<String> ROUTER_MODELS   = Set.of("AR2220", "AR2200", "AR3200");

    public TopologyJson parse(byte[] fileBytes) throws Exception {
        // eNSP 在中文 Windows 上生成 .topo 时中文内容用 GBK 编码
        // 先试 UTF-8，XML 声明有 "UNICODE" 则改用 GBK
        String xml = new String(fileBytes, StandardCharsets.UTF_8);
        if (xml.contains("encoding=\"UNICODE\"")) {
            xml = new String(fileBytes, java.nio.charset.Charset.forName("GBK"));
        }
        xml = xml.replace("encoding=\"UNICODE\"", "encoding=\"UTF-8\"");
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        doc.getDocumentElement().normalize();

        // 1. 解析设备
        Map<String, TopologyJson.Device> deviceMap = new LinkedHashMap<>();
        Map<String, List<String>> interfaceIndexMap = new HashMap<>(); // deviceId → 有序接口名列表

        NodeList devNodes = doc.getElementsByTagName("dev");
        for (int i = 0; i < devNodes.getLength(); i++) {
            Element devElem = (Element) devNodes.item(i);
            String id   = devElem.getAttribute("id");
            String name = devElem.getAttribute("name");
            String model= devElem.getAttribute("model");
            double cx   = parseDouble(devElem.getAttribute("cx"));
            double cy   = parseDouble(devElem.getAttribute("cy"));

            TopologyJson.Device device = new TopologyJson.Device();
            device.setId(id);
            device.setName(name);
            device.setModel(model);
            device.setType(inferType(model));
            // eNSP 坐标太密，统一放大 1.3 倍
            device.setX(cx);
            device.setY(cy);

            // 解析接口列表（有序）
            List<String> interfaces = parseInterfaces(devElem, model);
            device.setInterfaces(interfaces);
            interfaceIndexMap.put(id, interfaces);

            deviceMap.put(id, device);
        }

        // 2. 解析连线
        List<TopologyJson.Connection> connections = new ArrayList<>();
        NodeList lineNodes = doc.getElementsByTagName("line");
        for (int i = 0; i < lineNodes.getLength(); i++) {
            Element lineElem = (Element) lineNodes.item(i);
            String srcDevId  = lineElem.getAttribute("srcDeviceID");
            String destDevId = lineElem.getAttribute("destDeviceID");

            List<String> srcIfaces = interfaceIndexMap.get(srcDevId);
            List<String> destIfaces = interfaceIndexMap.get(destDevId);

            if (srcIfaces == null || destIfaces == null) continue; // 找不到设备，跳过

            NodeList pairs = lineElem.getElementsByTagName("interfacePair");
            for (int j = 0; j < pairs.getLength(); j++) {
                Element pair = (Element) pairs.item(j);
                String lineName = pair.getAttribute("lineName");
                int srcIdx = Integer.parseInt(pair.getAttribute("srcIndex"));
                int tarIdx = Integer.parseInt(pair.getAttribute("tarIndex"));

                String srcIface, tgtIface;
                // CTL = Console/RS232 控制线，不按普通接口索引
                if ("CTL".equals(lineName)) {
                    String srcType = inferType(deviceMap.get(srcDevId).getModel());
                    String tgtType = inferType(deviceMap.get(destDevId).getModel());
                    srcIface = isNetworkDevice(srcType) ? "Console" : "pc".equals(srcType) ? "RS232" : "CTL";
                    tgtIface = isNetworkDevice(tgtType) ? "Console" : "pc".equals(tgtType) ? "RS232" : "CTL";
                } else {
                    // Copper = 普通接口连接
                    if (srcIdx >= srcIfaces.size() || tarIdx >= destIfaces.size()) continue;
                    srcIface = srcIfaces.get(srcIdx);
                    tgtIface = destIfaces.get(tarIdx);
                }

                TopologyJson.Connection conn = new TopologyJson.Connection();
                conn.setFromDevice(deviceMap.get(srcDevId).getName());
                conn.setFromInterface(srcIface);
                conn.setToDevice(deviceMap.get(destDevId).getName());
                conn.setToInterface(tgtIface);
                connections.add(conn);
            }
        }

        // 3. 组装结果
        TopologyJson result = new TopologyJson();
        result.setDevices(new ArrayList<>(deviceMap.values()));
        result.setConnections(connections);
        return result;
    }

    // ==================== 接口解析 ====================

    private List<String> parseInterfaces(Element devElem, String model) {
        NodeList slotNodes = devElem.getElementsByTagName("slot");

        if (isFirewall(model)) {
            return parseFirewallInterfaces(slotNodes);
        } else {
            return parseGenericInterfaces(slotNodes);
        }
    }

    // 防火墙：每个 <interface> 标签明确指定 slotIndex/cardIndex/interfaceIndex
    // 命名 = type + slotIndex + "/" + cardIndex + "/" + interfaceIndex
    private List<String> parseFirewallInterfaces(NodeList slotNodes) {
        List<String> interfaces = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(); // 去重（.topo 里接口标签重复了两遍）

        for (int i = 0; i < slotNodes.getLength(); i++) {
            Element slot = (Element) slotNodes.item(i);
            NodeList ifaceNodes = slot.getElementsByTagName("interface");
            for (int j = 0; j < ifaceNodes.getLength(); j++) {
                Element iface = (Element) ifaceNodes.item(j);
                String type = iface.getAttribute("type");
                String slotIdx = iface.getAttribute("slotIndex");
                String cardIdx = iface.getAttribute("cardIndex");
                String ifaceIdx = iface.getAttribute("interfaceIndex");

                if (type.isEmpty() || slotIdx.isEmpty() || cardIdx.isEmpty() || ifaceIdx.isEmpty()) {
                    continue; // 跳过无效标签（如 Tunnel/Vlanif 在 .topo 里用其他方式表示）
                }

                String name = type + slotIdx + "/" + cardIdx + "/" + ifaceIdx;
                seen.add(name);
            }
        }

        // 去重后保持插入顺序
        interfaces.addAll(seen);

        // 给每个 GE 接口生成一个简写别名（GE1/0/2），实际存储全名
        // 这里直接存标准名
        return interfaces;
    }

    // 交换机/PC/Client/Server/路由器：
    // 每个 <interface interfacename="GE" count="24" /> 生成 GE0/0/0 ~ GE0/0/23
    // 序号跨标签累积
    private List<String> parseGenericInterfaces(NodeList slotNodes) {
        List<String> interfaces = new ArrayList<>();
        int globalIndex = 0;

        for (int i = 0; i < slotNodes.getLength(); i++) {
            Element slot = (Element) slotNodes.item(i);
            NodeList ifaceNodes = slot.getElementsByTagName("interface");
            for (int j = 0; j < ifaceNodes.getLength(); j++) {
                Element iface = (Element) ifaceNodes.item(j);
                String ifaceName = iface.getAttribute("interfacename");
                String countStr  = iface.getAttribute("count");

                if (ifaceName.isEmpty() || countStr.isEmpty()) continue;

                int count = Integer.parseInt(countStr);
                for (int k = 0; k < count; k++) {
                    interfaces.add(ifaceName + "0/0/" + globalIndex);
                    globalIndex++;
                }
            }
        }
        return interfaces;
    }

    // ==================== 设备类型推断 ====================

    private String inferType(String model) {
        if (isFirewall(model)) return "firewall";
        if (SWITCH_MODELS.contains(model)) return "switch";
        if (ROUTER_MODELS.contains(model)) return "router";
        if ("PC".equals(model))     return "pc";
        if ("Server".equals(model)) return "server";
        if ("Client".equals(model)) return "client";
        return "unknown";
    }

    private boolean isFirewall(String model) {
        return FIREWALL_MODELS.contains(model) || model.startsWith("USG");
    }

    /** 网络设备（交换机/路由器/防火墙）有 Console 口 */
    private boolean isNetworkDevice(String type) {
        return "switch".equals(type) || "router".equals(type) || "firewall".equals(type);
    }

    // ==================== 工具方法 ====================

    private double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }
}
