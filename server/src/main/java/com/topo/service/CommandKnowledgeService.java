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
 * 从 commands.json 加载设备型号→命令列表的映射。
 * 提供：能力查询、命令合法性校验、生成 RAG 可搜索文本
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
    public Map<String, ModelKnowledge> getAllModelKnowledge() { return models; }

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

    /**
     * 生成用于 RAG 向量化的文本
     * 按机型分别生成，保留 [适用:xxx] 标签用于设备过滤
     */
    public List<KnowledgeChunk> generateRagChunks() {
        List<KnowledgeChunk> result = new ArrayList<>();
        for (Map.Entry<String, ModelKnowledge> entry : models.entrySet()) {
            String model = entry.getKey();
            ModelKnowledge mk = entry.getValue();

            // 每个命令生成一个独立的 chunk
            for (CommandEntry ce : mk.commands) {
                StringBuilder sb = new StringBuilder();
                sb.append("[").append(model).append(" ").append(mk.fullName).append("]\n");
                sb.append("命令: ").append(ce.cmd).append("\n");
                sb.append("视图: ").append(ce.view).append("\n");
                sb.append("参数: ").append(ce.params).append("\n");
                sb.append("示例: ").append(ce.sample).append("\n");
                sb.append("说明: ").append(ce.desc).append("\n");

                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.text = sb.toString();
                chunk.models = Set.of(model);
                chunk.content = ce.cmd + " " + ce.desc + " " + ce.params + " " + ce.sample;
                result.add(chunk);
            }

            // 每个型号的能力概述也作为一个 chunk
            StringBuilder capSb = new StringBuilder();
            capSb.append("[").append(model).append(" ").append(mk.fullName).append("]\n");
            capSb.append("类型: ").append(mk.type).append("\n");
            capSb.append("接口前缀: ").append(mk.interfacePrefix).append("共").append(mk.interfaceCount).append("个\n");
            capSb.append("支持功能: ").append(String.join(", ", mk.capabilities)).append("\n");
            capSb.append("禁止功能: ").append(String.join(", ", mk.forbidden)).append("\n");
            if (mk.notes != null) capSb.append(mk.notes).append("\n");

            KnowledgeChunk capChunk = new KnowledgeChunk();
            capChunk.text = capSb.toString();
            capChunk.models = Set.of(model);
            capChunk.content = model + " " + mk.type + " " + mk.fullName + " " + String.join(" ", mk.capabilities) + " " + String.join(" ", mk.forbidden);
            result.add(capChunk);
        }
        return result;
    }

    /** 知识块：文本 + 适用机型 */
    public static class KnowledgeChunk {
        public String text;       // 完整展示文本
        public Set<String> models = Set.of(); // 适用机型
        public String content;    // 用于向量化的纯内容
    }
}
