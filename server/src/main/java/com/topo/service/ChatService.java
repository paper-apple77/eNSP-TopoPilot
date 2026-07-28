package com.topo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DeepSeek API 调用 — 支持 Function Calling 多轮循环
 */
@Service
public class ChatService {

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    @Value("${deepseek.api-key}")
    private String apiKey;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    /** Function Calling 最大循环轮数 */
    private static final int MAX_TOOL_ROUNDS = 3;

    public ChatService(ObjectMapper objectMapper, ToolRegistry toolRegistry) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Agent 模式：AI 可以调工具，多轮循环直到完成任务
     */
    public void agentChat(String systemPrompt, List<Map<String, String>> history,
                          String userMessage, Consumer<AgentEvent> callback) throws Exception {

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 10);
            messages.addAll(history.subList(start, history.size()));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        StringBuilder fullResponse = new StringBuilder();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            callback.accept(new AgentEvent("thinking", "AI 思考中... (第" + (round + 1) + "轮)", null));

            String aiOutput = callDeepSeek(messages, chunk -> {
                callback.accept(new AgentEvent("token", chunk, null));
            });

            fullResponse.append(aiOutput);

            // 检查是否有工具调用
            String toolCallJson = toolRegistry.extractToolCall(aiOutput);
            if (toolCallJson == null) {
                // 没有工具调用了，AI 认为任务完成
                callback.accept(new AgentEvent("done", "任务完成", fullResponse.toString()));
                return;
            }

            // 解析工具调用
            try {
                Map<String, Object> tcObj = objectMapper.readValue(toolCallJson, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> toolCall = (Map<String, Object>) tcObj.get("tool_call");
                String reasoning = (String) tcObj.getOrDefault("reasoning", "");
                if (toolCall != null) {
                    String toolName = (String) toolCall.get("name");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = (Map<String, Object>) toolCall.get("params");

                    callback.accept(new AgentEvent("tool_start",
                        "🔧 调用工具: " + toolName + " → " + reasoning, null));

                    String toolResult = toolRegistry.execute(toolName, params);

                    callback.accept(new AgentEvent("tool_result",
                        "📋 " + toolName + " 结果: " + toolResult.substring(0, Math.min(200, toolResult.length())), null));

                    // 把 AI 输出和工具结果加入对话
                    messages.add(Map.of("role", "assistant", "content", aiOutput));
                    messages.add(Map.of("role", "user",
                        "content", "工具 [" + toolName + "] 执行结果:\n" + toolResult +
                        "\n\n请基于此结果继续推理。如果任务完成，直接输出配置命令，不要再调工具。"));
                }
            } catch (Exception e) {
                System.err.println("[Agent] 工具调用解析失败: " + e.getMessage());
                callback.accept(new AgentEvent("error", "工具调用解析失败: " + e.getMessage(), null));
                break;
            }
        }

        callback.accept(new AgentEvent("done", "达到最大轮数，任务可能未完成", fullResponse.toString()));
    }

    /**
     * 普通流式对话（不调工具）—— 直接用最简代码保证逐字输出
     */
    public void chatStream(String systemPrompt, List<Map<String, String>> history,
                           String userMessage, Consumer<String> callback) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 10);
            messages.addAll(history.subList(start, history.size()));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = Map.of("model", model, "messages", messages, "stream", true);
        String json = objectMapper.writeValueAsString(body);

        HttpURLConnection conn = (HttpURLConnection) URI.create(
            "https://api.deepseek.com/v1/chat/completions").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // 检查 HTTP 错误码
        int code = conn.getResponseCode();
        if (code != 200) {
            // 读取错误详情
            try (java.io.InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    String err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                    System.err.println("[DeepSeek] HTTP " + code + ": " + err);
                }
            }
            throw new IOException("DeepSeek API returned " + code);
        }

        // 逐字节读取，避免 BufferedReader 内部缓冲
        try (InputStream is = conn.getInputStream()) {
            java.io.ByteArrayOutputStream lineBuf = new java.io.ByteArrayOutputStream();
            long firstTokenAt = 0;
            int tokenCount = 0;
            int b;
            while ((b = is.read()) != -1) {
                if (b == '\n') {
                    String line = lineBuf.toString("UTF-8");
                    lineBuf.reset();
                    if (line.contains("[DONE]")) break;
                    if (line.startsWith("data: ")) {
                        try {
                            String data = line.substring(6);
                            Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                            if (choices != null && !choices.isEmpty()) {
                                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                if (delta != null && delta.get("content") != null) {
                                    String token = delta.get("content").toString();
                                    if (firstTokenAt == 0) firstTokenAt = System.currentTimeMillis();
                                    tokenCount++;
                                    callback.accept(token);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                } else {
                    lineBuf.write(b);
                }
            }
            long elapsed = firstTokenAt > 0 ? System.currentTimeMillis() - firstTokenAt : 0;
            System.out.println("[SSE] 收到 " + tokenCount + " 个 token，首个 token 之后耗时 " + elapsed + "ms");
        }
    }

    /** 调用 DeepSeek API，流式返回完整响应 */
    private String callDeepSeek(List<Map<String, String>> messages, Consumer<String> onToken) throws Exception {
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", messages,
            "stream", true
        );

        String json = objectMapper.writeValueAsString(body);
        HttpURLConnection conn = (HttpURLConnection) URI.create(
            "https://api.deepseek.com/v1/chat/completions").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("[DONE]")) break;
                if (line.startsWith("data: ")) {
                    try {
                        String data = line.substring(6);
                        Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                        if (choices != null && !choices.isEmpty()) {
                            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                            if (delta != null && delta.get("content") != null) {
                                String token = delta.get("content").toString();
                                full.append(token);
                                onToken.accept(token);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return full.toString();
    }

    /** Agent 事件 */
    public static class AgentEvent {
        public String type;    // thinking, token, tool_start, tool_result, error, done
        public String message;
        public String fullResponse;
        public AgentEvent(String type, String message, String fullResponse) {
            this.type = type; this.message = message; this.fullResponse = fullResponse;
        }
    }
}
