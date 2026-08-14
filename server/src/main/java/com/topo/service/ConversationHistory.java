package com.topo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.topo.mapper.ChatHistoryMapper;
import com.topo.mapper.ChatSummaryMapper;
import com.topo.model.entity.ChatHistory;
import com.topo.model.entity.ChatSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 多轮对话历史管理（按用户+拓扑+模式分组）
 *
 * 两级存储：
 * 1. 内存缓存（≤10 条）：对话进行时的快速路径
 * 2. MySQL 持久化（每用户每拓扑每模式 ≤200 条）：服务重启后历史不丢失，
 *    缓存 miss 时懒加载最近 10 条回内存
 *
 * topologyId=0 表示未绑定拓扑的通用对话；保存过拓扑的对话按拓扑隔离，
 * 不同拓扑的历史互不干扰（配网场景下每张拓扑的上下文差异很大）。
 *
 * 记忆摘要：内存超过保留上限被淘汰的最旧几轮不会直接丢弃，而是攒够一批
 * 后异步交给 AI 压缩成要点摘要存入 tb_chat_summary（新摘要合并旧摘要），
 * 下轮对话注入 system prompt —— 让 AI 记得久远的上下文。
 * 摘要失败静默降级（DB 里仍有完整历史兜底）。
 *
 * DB 故障时自动降级为纯内存，不影响对话主流程。
 */
@Component
public class ConversationHistory {

    /** 内存保留条数（与 AI 上下文窗口一致） */
    private static final int MAX_IN_MEMORY = 10;
    /** DB 每用户每拓扑每模式保留条数，超出删最旧 */
    private static final int MAX_IN_DB = 200;
    /** 攒够多少轮被淘汰的对话才触发一次 AI 摘要 */
    private static final int SUMMARIZE_BATCH = 6;

    private final ChatHistoryMapper chatHistoryMapper;
    /** 测试/降级场景可为 null（null 时只做滑动窗口淘汰，不生成摘要） */
    private final ChatService chatService;
    private final ChatSummaryMapper summaryMapper;

    /** key(userId:mode[:topo{id}]) → 最近问答列表（正序） */
    private final Map<String, List<Map.Entry<String, String>>> cache = new ConcurrentHashMap<>();
    /** 被淘汰待摘要的对话缓冲区 */
    private final Map<String, List<Map.Entry<String, String>>> pendingSummaries = new ConcurrentHashMap<>();
    /** 正在执行摘要的 key，防止并发重复摘要 */
    private final Set<String> summarizingKeys = ConcurrentHashMap.newKeySet();
    /** 摘要专用单线程（AI 调用串行，避免摘要请求互相抢模型配额） */
    private final ExecutorService summaryExecutor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "history-summarize");
            t.setDaemon(true);
            return t;
        });

    @Autowired
    public ConversationHistory(ChatHistoryMapper chatHistoryMapper,
                               ChatService chatService,
                               ChatSummaryMapper summaryMapper) {
        this.chatHistoryMapper = chatHistoryMapper;
        this.chatService = chatService;
        this.summaryMapper = summaryMapper;
    }

    /** 测试专用：无摘要能力 */
    public ConversationHistory(ChatHistoryMapper chatHistoryMapper) {
        this(chatHistoryMapper, null, null);
    }

    /** topologyId 为空或 0 视为通用对话，否则按拓扑隔离 */
    private static Long normTopologyId(Long topologyId) {
        return topologyId != null && topologyId > 0 ? topologyId : 0L;
    }

    private String key(Long userId, Long topologyId, String mode) {
        String base = userId + ":" + (mode != null ? mode : "default");
        return normTopologyId(topologyId) > 0 ? base + ":topo" + normTopologyId(topologyId) : base;
    }

    public void add(Long userId, Long topologyId, String userMsg, String assistantMsg, String mode) {
        Long topoId = normTopologyId(topologyId);
        String k = key(userId, topoId, mode);
        String m = mode != null ? mode : "default";

        // 1. 持久化到 MySQL（失败降级为纯内存，不影响对话）
        try {
            ChatHistory ch = new ChatHistory();
            ch.setUserId(userId);
            ch.setTopologyId(topoId);
            ch.setMode(m);
            ch.setUserMessage(userMsg);
            ch.setAssistantMessage(assistantMsg);
            chatHistoryMapper.insert(ch);
            // 2. 清理超出上限的最旧记录
            chatHistoryMapper.deleteOldest(userId, topoId, m, MAX_IN_DB);
        } catch (Exception e) {
            System.err.println("[History] 持久化失败（降级为内存）: " + e.getMessage());
        }

        // 3. 更新内存缓存：超限的最旧轮次移入待摘要缓冲区，不直接丢弃
        List<Map.Entry<String, String>> history = cache.computeIfAbsent(k, x -> new ArrayList<>());
        synchronized (history) {
            history.add(Map.entry(userMsg, assistantMsg));
            if (history.size() > MAX_IN_MEMORY) {
                int overflow = history.size() - MAX_IN_MEMORY;
                List<Map.Entry<String, String>> expired = new ArrayList<>(history.subList(0, overflow));
                history.subList(0, overflow).clear();
                if (chatService != null && summaryMapper != null) {
                    List<Map.Entry<String, String>> buf =
                        pendingSummaries.computeIfAbsent(k, x -> new ArrayList<>());
                    synchronized (buf) { buf.addAll(expired); }
                    scheduleSummarize(userId, topoId, m, k);
                }
            }
        }
    }

    public List<Map<String, String>> getHistory(Long userId, Long topologyId, String mode) {
        Long topoId = normTopologyId(topologyId);
        String k = key(userId, topoId, mode);
        String m = mode != null ? mode : "default";

        // 1. 缓存未命中 → 从 DB 懒加载（重启后恢复上下文）
        List<Map.Entry<String, String>> history = cache.get(k);
        if (history == null) {
            List<Map.Entry<String, String>> loaded = loadFromDb(userId, topoId, m);
            if (loaded == null) {
                // DB 不可用：给空列表，下次 add 时重建
                return List.of();
            }
            history = cache.putIfAbsent(k, loaded);
            if (history == null) history = loaded;
        }

        // 2. 转成 AI 消息格式（user/assistant 交替）
        List<Map<String, String>> messages = new ArrayList<>();
        synchronized (history) {
            for (Map.Entry<String, String> entry : history) {
                messages.add(Map.of("role", "user", "content", entry.getKey()));
                messages.add(Map.of("role", "assistant", "content", entry.getValue()));
            }
        }
        return messages;
    }

    /** 取对话记忆摘要（不存在或 DB 故障返回 null，调用方跳过即可） */
    public String getSummary(Long userId, Long topologyId, String mode) {
        if (summaryMapper == null) return null;
        try {
            return loadSummary(userId, normTopologyId(topologyId), mode != null ? mode : "default");
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 DB 读最近 10 条（正序）。DB 故障返回 null。 */
    private List<Map.Entry<String, String>> loadFromDb(Long userId, Long topologyId, String mode) {
        try {
            List<ChatHistory> rows = chatHistoryMapper.findRecent(userId, topologyId, mode, MAX_IN_MEMORY);
            List<Map.Entry<String, String>> entries = new ArrayList<>(rows.size());
            // findRecent 按 id 倒序返回，反转为正序
            for (int i = rows.size() - 1; i >= 0; i--) {
                entries.add(Map.entry(rows.get(i).getUserMessage(), rows.get(i).getAssistantMessage()));
            }
            return entries;
        } catch (Exception e) {
            System.err.println("[History] 读取失败（DB 不可用?）: " + e.getMessage());
            return null;
        }
    }

    // ===== 记忆摘要 =====

    /** 触发摘要任务；已有任务在跑则跳过（正在跑的任务会把新批次一并消化） */
    private void scheduleSummarize(Long userId, Long topologyId, String mode, String k) {
        if (!summarizingKeys.add(k)) return;
        summaryExecutor.submit(() -> {
            try {
                while (true) {
                    List<Map.Entry<String, String>> buf = pendingSummaries.get(k);
                    if (buf == null) break;
                    List<Map.Entry<String, String>> batch;
                    synchronized (buf) {
                        if (buf.size() < SUMMARIZE_BATCH) { batch = null; }
                        else { batch = new ArrayList<>(buf); buf.clear(); }
                    }
                    if (batch == null) break; // 攒够一批再压缩，避免每轮都调 AI
                    summarizeAsync(userId, topologyId, mode, batch);
                }
            } catch (Exception e) {
                System.err.println("[History] 摘要异常: " + e.getMessage());
            } finally {
                summarizingKeys.remove(k);
            }
        });
    }

    /** 调 AI 把一批旧对话合并进已有摘要，结果写回 tb_chat_summary */
    private void summarizeAsync(Long userId, Long topologyId, String mode,
                                List<Map.Entry<String, String>> rounds) {
        try {
            String existing = loadSummary(userId, topologyId, mode);

            StringBuilder dialog = new StringBuilder();
            for (Map.Entry<String, String> e : rounds) {
                dialog.append("用户: ").append(e.getKey())
                      .append("\nAI: ").append(e.getValue()).append("\n\n");
            }

            String prompt = "你是对话记忆摘要助手。把新增的历史对话要点合并进已有摘要，" +
                "保留：用户目标、已完成的配置任务、关键网络参数（IP/VLAN/接口）、未解决的问题。" +
                (existing != null && !existing.isBlank()
                    ? "\n\n【已有摘要】\n" + existing : "") +
                "\n\n【新增对话】\n" + dialog +
                "\n输出合并后的完整摘要，200 字以内，中文，只输出摘要本身。";

            StringBuilder result = new StringBuilder();
            chatService.chatStream(prompt, List.of(), "记忆摘要", result::append);
            String summary = result.toString().trim();
            if (summary.length() < 5) return; // AI 没给出有效摘要，保留旧摘要
            saveSummary(userId, topologyId, mode, summary);
        } catch (Exception e) {
            System.err.println("[History] 摘要生成失败（忽略）: " + e.getMessage());
        }
    }

    private String loadSummary(Long userId, Long topologyId, String mode) {
        LambdaQueryWrapper<ChatSummary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSummary::getUserId, userId)
               .eq(ChatSummary::getTopologyId, topologyId)
               .eq(ChatSummary::getMode, mode);
        ChatSummary s = summaryMapper.selectOne(wrapper);
        return s != null ? s.getSummary() : null;
    }

    /** 手动 upsert：先查再插/更（唯一键保证不会重复） */
    private void saveSummary(Long userId, Long topologyId, String mode, String summary) {
        LambdaQueryWrapper<ChatSummary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSummary::getUserId, userId)
               .eq(ChatSummary::getTopologyId, topologyId)
               .eq(ChatSummary::getMode, mode);
        ChatSummary existing = summaryMapper.selectOne(wrapper);
        if (existing != null) {
            existing.setSummary(summary);
            summaryMapper.updateById(existing);
        } else {
            ChatSummary s = new ChatSummary();
            s.setUserId(userId);
            s.setTopologyId(topologyId);
            s.setMode(mode);
            s.setSummary(summary);
            summaryMapper.insert(s);
        }
    }
}
