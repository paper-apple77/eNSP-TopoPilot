package com.topo.service;

import com.topo.model.vo.TopologyJson;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class TopoXmlParser {

    public TopologyJson parse(byte[] bytes) throws Exception {
        // eNSP .topo: encoding="UNICODE" = UTF-16 LE
        String xml;
        if (bytes.length >= 2 && bytes[0] == (byte)0xFF && bytes[1] == (byte)0xFE) {
            // UTF-16 LE with BOM
            xml = new String(bytes, StandardCharsets.UTF_16LE);
        } else if (bytes.length >= 2 && bytes[0] == (byte)0xFE && bytes[1] == (byte)0xFF) {
            // UTF-16 BE with BOM
            xml = new String(bytes, StandardCharsets.UTF_16BE);
        } else {
            // Try UTF-8
            xml = new String(bytes, StandardCharsets.UTF_8);
        }

        // Clean: remove BOM, strip to <?xml
        xml = xml.replace("﻿", "").trim();
        if (xml.contains("UNICODE")) xml = xml.replace("encoding=\"UNICODE\"", "encoding=\"UTF-8\"");
        int start = xml.indexOf("<?xml");
        if (start > 0) xml = xml.substring(start);
        if (start < 0) throw new RuntimeException("Invalid .topo file");

        Document doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        TopologyJson topo = new TopologyJson();
        topo.setDevices(new ArrayList<>());
        topo.setConnections(new ArrayList<>());

        Map<String, TopologyJson.Device> devMap = new LinkedHashMap<>();

        NodeList devNodes = doc.getElementsByTagName("dev");
        for (int i = 0; i < devNodes.getLength(); i++) {
            Element el = (Element) devNodes.item(i);
            TopologyJson.Device d = new TopologyJson.Device();
            d.setId(el.getAttribute("id"));
            d.setName(el.getAttribute("name"));
            d.setModel(el.getAttribute("model"));
            d.setType(inferType(d.getModel()));
            d.setX(Double.parseDouble(el.getAttribute("cx")));
            d.setY(Double.parseDouble(el.getAttribute("cy")));
            String comPort = el.getAttribute("com_port");
            if (comPort != null && !comPort.isBlank() && !"0".equals(comPort))
                d.setComPort(Integer.parseInt(comPort));
            String settings = el.getAttribute("settings");
            if (settings != null && !settings.isBlank()) d.setSettings(settings);

            List<String> ifaces = new ArrayList<>();
            NodeList slots = el.getElementsByTagName("slot");
            for (int s = 0; s < slots.getLength(); s++) {
                Element slot = (Element) slots.item(s);
                NodeList ifNodes = slot.getElementsByTagName("interface");
                for (int j = 0; j < ifNodes.getLength(); j++)
                    extractInterfaces((Element) ifNodes.item(j), ifaces);
            }
            d.setInterfaces(ifaces);
            topo.getDevices().add(d);
            devMap.put(d.getId(), d);
        }

        NodeList lineNodes = doc.getElementsByTagName("line");
        for (int i = 0; i < lineNodes.getLength(); i++) {
            Element el = (Element) lineNodes.item(i);
            String srcId = el.getAttribute("srcDeviceID");
            String dstId = el.getAttribute("destDeviceID");
            NodeList pairs = el.getElementsByTagName("interfacePair");
            if (pairs.getLength() > 0) {
                Element pair = (Element) pairs.item(0);
                int srcIdx = Integer.parseInt(pair.getAttribute("srcIndex"));
                int tarIdx = Integer.parseInt(pair.getAttribute("tarIndex"));
                TopologyJson.Device srcDev = devMap.get(srcId);
                TopologyJson.Device dstDev = devMap.get(dstId);
                if (srcDev != null && dstDev != null) {
                    TopologyJson.Connection c = new TopologyJson.Connection();
                    c.setFromDevice(srcDev.getName());
                    c.setFromInterface(getIface(srcDev, srcIdx));
                    c.setToDevice(dstDev.getName());
                    c.setToInterface(getIface(dstDev, tarIdx));
                    topo.getConnections().add(c);
                }
            }
        }
        return topo;
    }

    private void extractInterfaces(Element ifEl, List<String> ifaces) {
        String cat = ifEl.getAttribute("category");
        if (!cat.isEmpty()) {
            String slot = ifEl.getAttribute("slotIndex");
            if (!slot.isEmpty())
                ifaces.add(ifEl.getAttribute("type") + slot + "/" + ifEl.getAttribute("cardIndex") + "/" + ifEl.getAttribute("interfaceIndex"));
            return;
        }
        String count = ifEl.getAttribute("count");
        if (!count.isEmpty()) {
            String name = ifEl.getAttribute("interfacename");
            for (int i = 0; i < Integer.parseInt(count) && i < 48; i++)
                ifaces.add(name + "0/0/" + i);
        }
    }

    private String getIface(TopologyJson.Device dev, int idx) {
        return (dev.getInterfaces() != null && idx >= 0 && idx < dev.getInterfaces().size())
            ? dev.getInterfaces().get(idx) : "unknown";
    }

    private String inferType(String m) {
        if (m == null) return "unknown";
        if (m.startsWith("USG")) return "firewall";
        if (m.startsWith("S5") || m.startsWith("S3")) return "switch";
        if (m.startsWith("AR")) return "router";
        if (m.equals("PC")) return "pc";
        if (m.equals("Server")) return "server";
        if (m.equals("Client")) return "client";
        return "unknown";
    }
}
