package com.topo.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 设备感知的向量化语义检索引擎
 *
 * 从 CommandKnowledgeService 获取结构化知识块，TF-IDF 向量化。
 * 搜索时按当前拓扑的机型过滤，只返回适用于相关型号的知识。
 */
@Service
public class VectorSearchService {

    private final CommandKnowledgeService knowledgeService;

    /** 知识块 */
    private final List<Chunk> chunks = new ArrayList<>();
    /** 每个块的 TF-IDF 向量 */
    private final List<double[]> chunkVectors = new ArrayList<>();
    /** 全局 IDF */
    private Map<String, Double> idf = new HashMap<>();
    /** 词汇表 */
    private List<String> vocabulary = new ArrayList<>();
    private boolean ready = false;

    public VectorSearchService(CommandKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostConstruct
    public void init() {
        try {
            List<CommandKnowledgeService.KnowledgeChunk> kChunks = knowledgeService.generateRagChunks();
            for (CommandKnowledgeService.KnowledgeChunk kc : kChunks) {
                Chunk c = new Chunk();
                c.text = kc.text;
                c.models = kc.models;
                c.content = kc.content;
                chunks.add(c);
            }

            // 分词 + 构建 TF-IDF
            List<List<String>> tokenizedChunks = new ArrayList<>();
            for (Chunk chunk : chunks) {
                tokenizedChunks.add(tokenize(chunk.content));
            }
            buildTFIDF(tokenizedChunks);
            ready = true;
            System.out.println("[VectorSearch] 索引 " + chunks.size() + " 个知识块, 词汇量 " + vocabulary.size());
        } catch (Exception e) {
            System.err.println("[VectorSearch] 初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * 设备感知搜索
     * @param query 用户查询
     * @param topK 返回 Top-K
     * @param deviceModels 拓扑中的机型集合（空=不过滤）
     */
    public List<String> search(String query, int topK, Set<String> deviceModels) {
        if (!ready || query == null || query.isBlank()) return List.of();

        List<String> queryTokens = tokenize(query.toLowerCase());
        double[] queryVec = tfidfVector(queryTokens);

        // 计算相似度 + 机型过滤
        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < chunkVectors.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (!deviceModels.isEmpty()) {
                boolean match = chunk.models.stream().anyMatch(deviceModels::contains);
                if (!match) continue;
            }
            double sim = cosineSim(queryVec, chunkVectors.get(i));
            if (sim > 0.02) {
                scored.add(new ScoredChunk(i, sim, chunk));
            }
        }

        scored.sort((a, b) -> Double.compare(b.sim, a.sim));

        System.out.println("[VectorSearch] \"" + query + "\" → 过滤:" + deviceModels + " → 候选:" + scored.size());
        List<String> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            ScoredChunk sc = scored.get(i);
            if (sc.sim < 0.03) break;
            System.out.println("[VectorSearch]   #" + (i+1) + " sim=" + String.format("%.3f", sc.sim)
                + " models=" + sc.chunk.models);
            results.add(sc.chunk.text);
        }
        return results;
    }

    // --- data classes ---

    private static class Chunk {
        String text;
        Set<String> models = Set.of();
        String content;
    }

    private static class ScoredChunk {
        int idx; double sim; Chunk chunk;
        ScoredChunk(int i, double s, Chunk c) { idx = i; sim = s; chunk = c; }
    }

    // --- TF-IDF ---

    private void buildTFIDF(List<List<String>> tokenizedChunks) {
        int N = tokenizedChunks.size();
        Map<String, Integer> df = new HashMap<>();
        for (List<String> tokens : tokenizedChunks) {
            Set<String> unique = new HashSet<>(tokens);
            for (String t : unique) df.merge(t, 1, Integer::sum);
        }
        vocabulary = new ArrayList<>(df.keySet());
        idf = new HashMap<>();
        for (String term : vocabulary) {
            idf.put(term, Math.log((double) N / (df.get(term) + 1)) + 1);
        }
        for (List<String> tokens : tokenizedChunks) {
            chunkVectors.add(tfidfVector(tokens));
        }
    }

    private double[] tfidfVector(List<String> tokens) {
        double[] vec = new double[vocabulary.size()];
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);
        for (int i = 0; i < vocabulary.size(); i++) {
            String term = vocabulary.get(i);
            double termFreq = 1.0 + Math.log(tf.getOrDefault(term, 0) + 1);
            vec[i] = termFreq * idf.getOrDefault(term, 1.0);
        }
        double norm = 0;
        for (double v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        return vec;
    }

    private double cosineSim(double[] a, double[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        text = text.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", " ");
        for (String w : text.split("\\s+")) {
            w = w.trim();
            if (w.length() >= 2) tokens.add(w);
        }
        String chinese = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < chinese.length() - 1; i++) {
            tokens.add(chinese.substring(i, i + 2));
        }
        return tokens;
    }
}
