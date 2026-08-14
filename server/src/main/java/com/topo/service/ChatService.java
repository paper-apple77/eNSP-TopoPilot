package com.topo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * AI 对话服务 — 基于 LangChain4j + DeepSeek（OpenAI 兼容接口）
 *
 * Agent 模式：原生 Function Calling 多轮循环（tools 参数），
 * 框架负责工具 schema 下发与 tool_calls 解析，本类负责循环编排：
 * 不同设备并行执行工具、同一设备内保持顺序、支持手动停止。
 */
@Service
public class ChatService {

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    @Value("${deepseek.api-key}")
    private String apiKey;

    @Value("${deepseek.model:deepseek-chat}")
    private String modelName;

    @Value("${deepseek.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    private OpenAiStreamingChatModel model;

    /** Function Calling 最大循环轮数（防止 AI 死循环烧配额） */
    private static final int MAX_TOOL_ROUNDS = 20;

    /** 工具执行线程池：不同设备并行执行工具调用 */
    private final ExecutorService toolExecutor = Executors.newFixedThreadPool(6);

    public ChatService(ObjectMapper objectMapper, ToolRegistry toolRegistry) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void init() {
        // key 未配置时保留其他功能可用（登录/拓扑/设备连接），AI 调用时给出明确报错
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[ChatService] ⚠️ 未配置 DEEPSEEK_API_KEY，AI 对话不可用。" +
                "Docker: compose 环境变量；本机: export/set DEEPSEEK_API_KEY 后重启。");
            return;
        }
        model = OpenAiStreamingChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .baseUrl(baseUrl)
            .build();
        System.out.println("[ChatService] LangChain4j 模型初始化: " + modelName + " @ " + baseUrl);
    }

    @PreDestroy
    public void shutdown() { toolExecutor.shutdownNow(); }

    /**
     * Agent 模式：AI 可以调工具，多轮循环直到完成任务
     */
    public void agentChat(String systemPrompt, List<Map<String, String>> history,
                          String userMessage, Consumer<AgentEvent> callback,
                          AtomicBoolean cancelled) throws Exception {

        if (model == null) throw new IllegalStateException("未配置 DEEPSEEK_API_KEY 环境变量，AI 功能不可用");
        List<ChatMessage> messages = buildMessages(systemPrompt, history, userMessage);
        List<ToolSpecification> toolSpecs = ToolSpecifications.toolSpecificationsFrom(toolRegistry);
        StringBuilder fullResponse = new StringBuilder();

        int round = 0;
        int maxRounds = MAX_TOOL_ROUNDS > 0 ? MAX_TOOL_ROUNDS : Integer.MAX_VALUE;
        for (; round < maxRounds; round++) {
            if (cancelled != null && cancelled.get()) {
                callback.accept(new AgentEvent("done", "用户已停止", fullResponse.toString()));
                return;
            }
            callback.accept(new AgentEvent("thinking", "AI 思考中... (第" + (round + 1) + "轮)", null));

            AiMessage ai = streamChat(messages, toolSpecs, callback);
            String text = ai.text() != null ? ai.text() : "";
            fullResponse.append(text);
            System.out.println("[Agent] 第" + (round + 1) + "轮 AI输出(" + text.length() + "字): "
                + text.substring(0, Math.min(200, text.length())).replace('\n',' '));

            if (!ai.hasToolExecutionRequests()) {
                if (text.isBlank()) {
                    String fallback = "（AI 未生成回复，请重试或简化问题）";
                    fullResponse.append(fallback);
                    callback.accept(new AgentEvent("token", fallback, null));
                }
                callback.accept(new AgentEvent("done", "任务完成", fullResponse.toString()));
                return;
            }

            List<ToolExecutionRequest> requests = ai.toolExecutionRequests();
            System.out.println("[Agent] 第" + (round + 1) + "轮, " + requests.size() + " 个工具调用: "
                + requests.stream().map(ToolExecutionRequest::name).toList());

            // 按设备分组：不同设备并行执行，同一设备内保持调用顺序
            // （每台设备的 Telnet 会话由 TelnetService 的设备锁保护）
            Map<String, List<Integer>> groups = new LinkedHashMap<>();
            for (int i = 0; i < requests.size(); i++) {
                groups.computeIfAbsent(deviceOf(requests.get(i)), k -> new ArrayList<>()).add(i);
            }
            String[] toolResults = new String[requests.size()];
            CountDownLatch latch = new CountDownLatch(groups.size());
            for (List<Integer> idxs : groups.values()) {
                toolExecutor.submit(() -> {
                    try {
                        for (int i : idxs) {
                            if (cancelled != null && cancelled.get()) break;
                            ToolExecutionRequest req = requests.get(i);
                            callback.accept(new AgentEvent("tool_start",
                                "查询 " + deviceOf(req) + " (" + req.name() + ")", null));
                            String result = new DefaultToolExecutor(toolRegistry, req).execute(req, null);
                            toolResults[i] = "--- [" + req.name() + " " + deviceOf(req) + "] ---\n" + result;
                            System.out.println("[Agent] " + req.name() + " → "
                                + (result != null ? result.length() + "B" : "null"));
                        }
                    } catch (Exception e) {
                        System.err.println("[Agent] 工具执行失败: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(5, TimeUnit.MINUTES);
            if (latch.getCount() > 0) {
                throw new RuntimeException("工具执行超时（5分钟），部分设备可能未完成");
            }

            // 按协议回填：一条 tool_calls 对应一条 tool 结果消息（保持消息交替顺序）
            messages.add(ai);
            for (int i = 0; i < requests.size(); i++) {
                String r = toolResults[i] != null ? toolResults[i] : "[已取消]";
                messages.add(ToolExecutionResultMessage.from(requests.get(i), r));
            }
        }

        callback.accept(new AgentEvent("done", "达到最大轮数，任务可能未完成", fullResponse.toString()));
    }

    /**
     * 普通流式对话（不调工具）—— 设计模式使用
     */
    public void chatStream(String systemPrompt, List<Map<String, String>> history,
                           String userMessage, Consumer<String> callback) throws Exception {
        if (model == null) throw new IllegalStateException("未配置 DEEPSEEK_API_KEY 环境变量，AI 功能不可用");
        List<ChatMessage> messages = buildMessages(systemPrompt, history, userMessage);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        model.chat(messages, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String token) { callback.accept(token); }
            @Override public void onCompleteResponse(ChatResponse response) {
                System.out.println("[DeepSeek] 完成: finish=" + response.finishReason()
                    + " tokens=" + (response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : "?"));
                latch.countDown();
            }
            @Override public void onError(Throwable t) { error.set(t); latch.countDown(); }
        });
        latch.await();
        if (error.get() != null) throw new RuntimeException("DeepSeek 调用失败: " + error.get().getMessage(), error.get());
    }

    /** 组装消息列表：系统提示词 + 最近历史 + 用户消息 */
    private List<ChatMessage> buildMessages(String systemPrompt, List<Map<String, String>> history,
                                            String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 10);
            for (Map<String, String> m : history.subList(start, history.size())) {
                if ("user".equals(m.get("role"))) messages.add(UserMessage.from(m.get("content")));
                else messages.add(AiMessage.from(m.get("content")));
            }
        }
        messages.add(UserMessage.from(userMessage));
        return messages;
    }

    /** 流式调用一轮（带工具定义），返回完整 AiMessage（含 text 和 tool_calls） */
    private AiMessage streamChat(List<ChatMessage> messages, List<ToolSpecification> toolSpecs,
                                 Consumer<AgentEvent> callback) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AiMessage> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        System.out.println("[DeepSeek] 请求: messages=" + messages.size() + "条, tools=" + toolSpecs.size());
        ChatRequest request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build();

        model.chat(request, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String token) {
                callback.accept(new AgentEvent("token", token, null));
            }
            @Override public void onCompleteResponse(ChatResponse response) {
                System.out.println("[DeepSeek] 响应: finish=" + response.finishReason()
                    + " tokens=" + (response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : "?"));
                result.set(response.aiMessage());
                latch.countDown();
            }
            @Override public void onError(Throwable t) { error.set(t); latch.countDown(); }
        });
        latch.await();
        if (error.get() != null) throw new RuntimeException("DeepSeek 调用失败: " + error.get().getMessage(), error.get());
        if (result.get() == null) throw new RuntimeException("DeepSeek 无响应");
        return result.get();
    }

    /** 从工具调用参数中提取设备名（用于分组并行） */
    private String deviceOf(ToolExecutionRequest req) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(req.arguments(), Map.class);
            Object d = args.get("device_name");
            return d != null ? String.valueOf(d) : "";
        } catch (Exception e) {
            return "";
        }
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
