package com.topo.service;

import com.topo.model.vo.TopologyJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * eNSP 工程 zip 包解析器
 *
 * zip 结构：
 *   杭州-总部-上海组网.zip
 *   ├── xxx.topo    → 拓扑结构
 *   ├── FW_HZ.cfg   → 设备已配置命令
 *   ├── FW-01.cfg
 *   └── ...
 *
 * 返回 TopologyJson + 每台设备的配置文本。
 */
@Component
@RequiredArgsConstructor
public class ZipProjectParser {

    private final TopoXmlParser topoXmlParser;

    /**
     * 解析结果
     */
    public static class ProjectResult {
        public TopologyJson topology;
        /** 设备名 → 配置文本 */
        public Map<String, String> deviceConfigs = new LinkedHashMap<>();
    }

    public ProjectResult parse(byte[] zipBytes) throws Exception {
        ProjectResult result = new ProjectResult();
        Map<String, byte[]> files = new LinkedHashMap<>();

        // 解压 zip
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // 处理嵌套目录：取文件名
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                files.put(name, zis.readAllBytes());
            }
        }

        // 找 .topo 文件
        byte[] topoBytes = null;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (e.getKey().toLowerCase().endsWith(".topo")) {
                topoBytes = e.getValue();
                break;
            }
        }
        if (topoBytes == null) throw new RuntimeException("zip 中未找到 .topo 文件");

        // 解析拓扑
        result.topology = topoXmlParser.parse(topoBytes);

        // 解析 .cfg 文件（文件名 = 设备名.cfg）
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String key = e.getKey();
            if (key.toLowerCase().endsWith(".cfg")) {
                String deviceName = key.substring(0, key.length() - 4); // 去 .cfg
                // 尝试 GBK 和 UTF-8
                String config = tryDecode(e.getValue());
                result.deviceConfigs.put(deviceName, config);
            }
        }

        return result;
    }

    /** 先试 GBK（中文 Windows eNSP），再试 UTF-8 */
    private String tryDecode(byte[] bytes) {
        try {
            return new String(bytes, Charset.forName("GBK"));
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
