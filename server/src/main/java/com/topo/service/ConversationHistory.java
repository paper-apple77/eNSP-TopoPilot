package com.topo.service;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话历史管理（按用户+模式分组）
 */
@Component
public class ConversationHistory {

    private final Map<String, List<Map.Entry<String, String>>> store = new ConcurrentHashMap<>();

    private String key(Long userId, String mode) {
        return userId + ":" + (mode != null ? mode : "default");
    }

    public void add(Long userId, Long topologyId, String userMsg, String assistantMsg, String mode) {
        List<Map.Entry<String, String>> history = store.computeIfAbsent(key(userId, mode), k -> new ArrayList<>());
        history.add(Map.entry(userMsg, assistantMsg));
        if (history.size() > 10) {
            history.subList(0, history.size() - 10).clear();
        }
    }

    public List<Map<String, String>> getHistory(Long userId, Long topologyId, String mode) {
        List<Map.Entry<String, String>> history = store.get(key(userId, mode));
        if (history == null) return List.of();
        List<Map<String, String>> messages = new ArrayList<>();
        for (Map.Entry<String, String> entry : history) {
            messages.add(Map.of("role", "user", "content", entry.getKey()));
            messages.add(Map.of("role", "assistant", "content", entry.getValue()));
        }
        return messages;
    }

    public void clear(Long userId, Long topologyId, String mode) {
        store.remove(key(userId, mode));
    }
}
