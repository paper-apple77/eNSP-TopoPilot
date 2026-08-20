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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

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
            log.error("[ChatService] 未配置 DEEPSEEK_API_KEY，AI 对话不可用。" +
                "Docker: compose 环境变量；本机: export/set DEEPSEEK_API_KEY 后重启。");
            return;
        }
        model = OpenAiStreamingChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .baseUrl(baseUrl)
            .build();
        log.info("[ChatService] LangChain4j 模型初始化: " + modelName + " @ " + baseUrl);
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
        /** 连续重复工具调用检测：同一命令连续3次即判定绕圈（防 AI 反复查同一信息烧轮数） */
        String lastToolKey = null;
        int sameToolStreak = 0;
        /** 连续纯文字轮计数：首轮就纯文字通常是"只输出计划不动手"，自动注入提醒推它继续 */
        int textOnlyStreak = 0;
        /** 本轮会话是否执行过至少一次工具调用（判断纯文字轮是否属于提前停） */
        boolean toolExecuted = false;
        for (; round < maxRounds; round++) {
            if (cancelled != null && cancelled.get()) {
                callback.accept(new AgentEvent("done", "用户已停止", fullResponse.toString()));
                return;
            }
            // 防卡壳注入：轮数预警 + 定期进度小结（以用户消息加入，符合对话协议）
            if (MAX_TOOL_ROUNDS > 0 && round == MAX_TOOL_ROUNDS - 5) {
                messages.add(UserMessage.from("[系统提醒] 已接近最大执行轮数，最多再执行 5 轮。请停止扩展性查询，基于已掌握的信息完成剩余配置，然后输出最终总结。"));
            } else if (round > 0 && round % 8 == 0) {
                messages.add(UserMessage.from("[系统提醒] 请用一句话说明当前进度和剩余步骤，然后继续执行，不要重复已完成的配置。"));
            }
            callback.accept(new AgentEvent("thinking", "AI 思考中... (第" + (round + 1) + "轮)", null));

            StreamResult sr = streamChat(messages, toolSpecs, callback);
            AiMessage ai = sr.message();
            String text = ai.text() != null ? ai.text() : "";
            fullResponse.append(text);
            log.info("[Agent] 第" + (round + 1) + "轮 AI输出(" + text.length() + "字, finish=" + sr.finishReason() + "): "
                + text.substring(0, Math.min(200, text.length())).replace('\n',' '));

            if (!ai.hasToolExecutionRequests()) {
                // 纯文字轮要区分"被截断/只输出计划"与"真正完成"，防止 AI 说一句计划就停
                if ("LENGTH".equals(sr.finishReason())) {
                    // 输出达到 max_tokens 被截断：注入续写提醒，不当作任务完成
                    textOnlyStreak++;
                    if (textOnlyStreak >= 3) {
                        callback.accept(new AgentEvent("done",
                            "AI 回复连续 3 轮被截断（达到输出长度上限），任务未完成。可发消息让我继续。",
                            fullResponse.toString()));
                        return;
                    }
                    messages.add(ai);
                    messages.add(UserMessage.from("[系统提醒] 你的上一条回复被截断了（达到输出长度上限）。请从断点处继续执行剩余步骤，不要从头重复已说过的内容。"));
                    continue;
                }
                if (!toolExecuted && textOnlyStreak < 2) {
                    // 尚未执行过任何工具就纯文字结束：AI 大概率只输出计划就停，推它一把
                    textOnlyStreak++;
                    messages.add(ai);
                    messages.add(UserMessage.from("[系统提醒] 你只输出了文字，还没有调用任何工具。任务尚未完成：请立即调用工具执行查询/推送/验证，不要只输出计划。"));
                    continue;
                }
                if (text.isBlank()) {
                    String fallback = "（AI 未生成回复，请重试或简化问题）";
                    fullResponse.append(fallback);
                    callback.accept(new AgentEvent("token", fallback, null));
                }
                callback.accept(new AgentEvent("done", "任务完成", fullResponse.toString()));
                return;
            }
            textOnlyStreak = 0;

            List<ToolExecutionRequest> requests = ai.toolExecutionRequests();
            log.info("[Agent] 第" + (round + 1) + "轮, " + requests.size() + " 个工具调用: "
                + requests.stream().map(ToolExecutionRequest::name).toList());

            // 连续重复调用检测：预扫一遍，命中的直接预填结果、跳过执行
            String[] toolResults = new String[requests.size()];
            Set<Integer> skipped = new HashSet<>();
            for (int i = 0; i < requests.size(); i++) {
                ToolExecutionRequest req = requests.get(i);
                String key = req.name() + "|" + deviceOf(req) + "|" + req.arguments();
                if (key.equals(lastToolKey)) {
                    sameToolStreak++;
                } else {
                    lastToolKey = key;
                    sameToolStreak = 1;
                }
                if (sameToolStreak >= 3) {
                    skipped.add(i);
                    toolResults[i] = "--- [" + req.name() + " " + deviceOf(req) + "] ---\n"
                        + "[系统跳过] 该命令已连续重复执行，请停止查询同一信息，基于现有结果推进任务。";
                    log.info("[Agent] 跳过重复调用: " + key);
                }
            }

            // 按设备分组：不同设备并行执行，同一设备内保持调用顺序
            // （每台设备的 Telnet 会话由 TelnetService 的设备锁保护）
            Map<String, List<Integer>> groups = new LinkedHashMap<>();
            for (int i = 0; i < requests.size(); i++) {
                groups.computeIfAbsent(deviceOf(requests.get(i)), k -> new ArrayList<>()).add(i);
            }
            CountDownLatch latch = new CountDownLatch(groups.size());
            for (List<Integer> idxs : groups.values()) {
                toolExecutor.submit(() -> {
                    try {
                        for (int i : idxs) {
                            if (cancelled != null && cancelled.get()) break;
                            if (skipped.contains(i)) continue; // 重复调用已预填结果，不实际执行
                            ToolExecutionRequest req = requests.get(i);
                            callback.accept(new AgentEvent("tool_start",
                                "查询 " + deviceOf(req) + " (" + req.name() + ")", null));
                            String result = new DefaultToolExecutor(toolRegistry, req).execute(req, null);
                            toolResults[i] = "--- [" + req.name() + " " + deviceOf(req) + "] ---\n" + result;
                            log.info("[Agent] " + req.name() + " → "
                                + (result != null ? result.length() + "B" : "null"));
                        }
                    } catch (Exception e) {
                        log.error("[Agent] 工具执行失败: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            toolExecuted = true;
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

        callback.accept(new AgentEvent("done",
            "已执行 " + MAX_TOOL_ROUNDS + " 轮工具调用，为防死循环自动停止。请检查上面已完成的配置，未完成部分可发消息让我继续。",
            fullResponse.toString()));
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
                log.info("[DeepSeek] 完成: finish=" + response.finishReason()
                    + " tokens=" + (response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : "?"));
                if (response.finishReason() != null && "LENGTH".equals(response.finishReason().toString())) {
                    callback.accept("\n\n⚠️ 输出达到长度上限，回复被截断。可发送\"继续\"让我补全剩余内容。");
                }
                latch.countDown();
            }
            @Override public void onError(Throwable t) { error.set(t); latch.countDown(); }
        });
        // 单轮超时保护：防止 DeepSeek 挂起导致请求线程永久阻塞、前端无限等待
        if (!latch.await(300, TimeUnit.SECONDS)) {
            throw new RuntimeException("DeepSeek 响应超时（300秒），请重试");
        }
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

    /** 流式调用一轮（带工具定义），返回 AI 消息与结束原因（finish_reason=LENGTH 表示输出被截断） */
    private StreamResult streamChat(List<ChatMessage> messages, List<ToolSpecification> toolSpecs,
                                    Consumer<AgentEvent> callback) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AiMessage> result = new AtomicReference<>();
        AtomicReference<String> finish = new AtomicReference<>("");
        AtomicReference<Throwable> error = new AtomicReference<>();

        log.info("[DeepSeek] 请求: messages=" + messages.size() + "条, tools=" + toolSpecs.size());
        ChatRequest request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build();

        model.chat(request, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String token) {
                callback.accept(new AgentEvent("token", token, null));
            }
            @Override public void onCompleteResponse(ChatResponse response) {
                finish.set(response.finishReason() != null ? response.finishReason().toString() : "");
                log.info("[DeepSeek] 响应: finish=" + finish.get()
                    + " tokens=" + (response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : "?"));
                result.set(response.aiMessage());
                latch.countDown();
            }
            @Override public void onError(Throwable t) { error.set(t); latch.countDown(); }
        });
        // 单轮超时保护：防止 DeepSeek 挂起导致请求线程永久阻塞、前端无限等待
        if (!latch.await(300, TimeUnit.SECONDS)) {
            throw new RuntimeException("DeepSeek 响应超时（300秒），请重试");
        }
        if (error.get() != null) throw new RuntimeException("DeepSeek 调用失败: " + error.get().getMessage(), error.get());
        if (result.get() == null) throw new RuntimeException("DeepSeek 无响应");
        return new StreamResult(result.get(), finish.get());
    }

    /** 一轮流式调用的结果：AI 消息 + finish_reason 字符串 */
    private record StreamResult(AiMessage message, String finishReason) {}

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
