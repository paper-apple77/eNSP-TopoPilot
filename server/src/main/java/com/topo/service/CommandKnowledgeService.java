package com.topo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * 结构化命令知识库
 *
 * 从 commands.json 加载设备型号→命令列表的映射，用于命令合法性校验和能力查询。
 */
@Service
public class CommandKnowledgeService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ModelKnowledge> models = new LinkedHashMap<>();

    /** 单条命令 */
    public static class CommandEntry {
        public String cmd;
        public String view;
        public String params;
        public String sample;
        public String desc;
    }

    /** 一个型号的知识 */
    public static class ModelKnowledge {
        public String type;
        public String fullName;
        public String interfacePrefix;
        public int interfaceCount;
        public List<String> capabilities = List.of();
        public List<String> forbidden = List.of();
        public String notes;
        public List<CommandEntry> commands = List.of();
        // 命令索引
        public final Map<String, CommandEntry> cmdIndex = new LinkedHashMap<>();

        public void buildIndex() {
            cmdIndex.clear();
            for (CommandEntry ce : commands) {
                if (ce.cmd != null) {
                    String key = ce.cmd.replaceAll("\\(.*?\\)", "").trim().toLowerCase();
                    cmdIndex.put(key, ce);
                }
            }
        }
    }

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge/commands.json");
            try (InputStream is = resource.getInputStream()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = objectMapper.readValue(is, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> modelsRaw = (Map<String, Object>) raw.get("models");
                for (Map.Entry<String, Object> entry : modelsRaw.entrySet()) {
                    String modelName = entry.getKey();
                    ModelKnowledge mk = objectMapper.convertValue(entry.getValue(), ModelKnowledge.class);
                    mk.buildIndex();
                    models.put(modelName, mk);
                }
            }
            System.out.println("[CommandKnowledge] 加载 " + models.size() + " 种设备型号");
            for (Map.Entry<String, ModelKnowledge> e : models.entrySet()) {
                System.out.println("[CommandKnowledge]   " + e.getKey()
                    + ": " + e.getValue().commands.size() + "cmds, cap=" + e.getValue().capabilities);
            }
        } catch (Exception e) {
            System.err.println("[CommandKnowledge] 加载失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public ModelKnowledge getModel(String model) { return models.get(model); }
    public Set<String> getAllModels() { return models.keySet(); }

    /** 检查型号是否支持某功能 */
    public boolean supports(String model, String feature) {
        ModelKnowledge mk = models.get(model);
        return mk == null || mk.capabilities.contains(feature.toLowerCase());
    }

    /** 检查型号是否禁止某功能 */
    public boolean isForbidden(String model, String feature) {
        ModelKnowledge mk = models.get(model);
        return mk != null && mk.forbidden.contains(feature.toLowerCase());
    }

    /** 检查某型号的某行命令是否存在于知识库 */
    public boolean isCommandKnown(String model, String line) {
        ModelKnowledge mk = models.get(model);
        if (mk == null) return true; // 未知型号放行
        String t = line.trim().toLowerCase();
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) return true;

        for (String cmd : mk.cmdIndex.keySet()) {
            if (t.startsWith(cmd)) return true;
        }
        // 允许缩进子命令和注释行
        if (t.matches("^\\s{1,4}\\S+.*")) return true;
        return false;
    }

    /** 获取型号能力摘要文本 */
    public String getCapabilityText(String model) {
        ModelKnowledge mk = models.get(model);
        if (mk == null) return null;
        return String.format("%s 支持: %s | 禁止: %s",
            mk.fullName, String.join(", ", mk.capabilities), String.join(", ", mk.forbidden));
    }
}
