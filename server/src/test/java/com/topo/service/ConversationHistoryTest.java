package com.topo.service;

import com.topo.mapper.ChatHistoryMapper;
import com.topo.mapper.ChatSummaryMapper;
import com.topo.model.entity.ChatHistory;
import com.topo.model.entity.ChatSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ConversationHistory 测试：内存缓存 + MySQL 持久化 + 故障降级
 */
class ConversationHistoryTest {

    private ChatHistoryMapper mapper;
    private ConversationHistory history;

    @BeforeEach
    void setUp() {
        mapper = mock(ChatHistoryMapper.class);
        when(mapper.insert(any(ChatHistory.class))).thenReturn(1);
        history = new ConversationHistory(mapper);
    }

    // ========== 内存路径 ==========

    @Test
    void 对话后能取回用户与AI交替消息() {
        history.add(1L, 0L, "你好", "你好，我是配网助手", "agent");
        history.add(1L, 0L, "帮我配VLAN", "好的", "agent");

        List<java.util.Map<String, String>> msgs = history.getHistory(1L, 0L, "agent");
        assertEquals(4, msgs.size(), "2 轮问答 = 4 条消息");
        assertEquals("user", msgs.get(0).get("role"));
        assertEquals("你好", msgs.get(0).get("content"));
        assertEquals("assistant", msgs.get(1).get("role"));
        assertEquals("好的", msgs.get(3).get("content"));
    }

    @Test
    void 内存只保留最近十轮() {
        for (int i = 1; i <= 12; i++) {
            history.add(1L, 0L, "问" + i, "答" + i, "agent");
        }
        List<java.util.Map<String, String>> msgs = history.getHistory(1L, 0L, "agent");
        assertEquals(20, msgs.size(), "最多 10 轮 = 20 条消息");
        assertEquals("问3", msgs.get(0).get("content"), "最旧的 2 轮应被淘汰");
        assertEquals("答12", msgs.get(19).get("content"), "最新一轮应保留");
    }

    @Test
    void 不同用户和模式相互隔离() {
        history.add(1L, 0L, "用户1的问题", "答", "agent");
        history.add(2L, 0L, "用户2的问题", "答", "agent");
        history.add(1L, 0L, "设计问题", "答", "design");

        assertEquals(2, history.getHistory(1L, 0L, "agent").size());
        assertEquals(2, history.getHistory(2L, 0L, "agent").size());
        assertEquals(2, history.getHistory(1L, 0L, "design").size());
    }

    // ========== MySQL 持久化 ==========

    @Test
    void 新增对话写入数据库并清理旧记录() {
        history.add(1L, 5L, "问题", "回答", "agent");

        ArgumentCaptor<ChatHistory> captor = ArgumentCaptor.forClass(ChatHistory.class);
        verify(mapper).insert(captor.capture());
        ChatHistory ch = captor.getValue();
        assertEquals(1L, ch.getUserId());
        assertEquals(5L, ch.getTopologyId());
        assertEquals("agent", ch.getMode());
        assertEquals("问题", ch.getUserMessage());
        assertEquals("回答", ch.getAssistantMessage());

        verify(mapper).deleteOldest(eq(1L), eq(5L), eq("agent"), eq(200));
    }

    @Test
    void 重启后从数据库恢复上下文() {
        // 模拟 DB 里已有的 2 条记录（findRecent 按 id 倒序返回）
        ChatHistory older = new ChatHistory();
        older.setId(2L);
        older.setUserMessage("之前的会话");
        older.setAssistantMessage("之前的回答");
        ChatHistory newer = new ChatHistory();
        newer.setId(3L);
        newer.setUserMessage("最新问题");
        newer.setAssistantMessage("最新回答");
        when(mapper.findRecent(1L, 0L, "agent", 10)).thenReturn(List.of(newer, older));

        // 新实例 = 服务重启，内存缓存为空
        ConversationHistory restarted = new ConversationHistory(mapper);
        List<java.util.Map<String, String>> msgs = restarted.getHistory(1L, 0L, "agent");

        assertEquals(4, msgs.size(), "应从 DB 恢复 2 轮问答");
        assertEquals("之前的会话", msgs.get(0).get("content"), "倒序查询结果应反转为正序");
        assertEquals("最新回答", msgs.get(3).get("content"));
    }

    // ========== DB 故障降级 ==========

    @Test
    void 数据库挂了对话功能不受影响() {
        doThrow(new RuntimeException("connection refused")).when(mapper).insert(any(ChatHistory.class));

        history.add(1L, 0L, "问题", "回答", "agent"); // 不应抛异常
        List<java.util.Map<String, String>> msgs = history.getHistory(1L, 0L, "agent");
        assertEquals(2, msgs.size(), "内存缓存应正常工作");
    }

    @Test
    void 数据库挂了历史返回空而不崩溃() {
        when(mapper.findRecent(anyLong(), anyLong(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("connection refused"));

        ConversationHistory restarted = new ConversationHistory(mapper);
        assertTrue(restarted.getHistory(1L, 0L, "agent").isEmpty(), "DB 不可用时应返回空历史");
    }

    // ========== 记忆摘要 ==========

    @Test
    void 内存淘汰的旧对话生成摘要而不是直接丢弃() throws Exception {
        ChatSummaryMapper summaryMapper = mock(ChatSummaryMapper.class);
        ChatService chatService = mock(ChatService.class);
        when(summaryMapper.selectOne(any())).thenReturn(null); // 无已有摘要 → insert
        when(summaryMapper.insert(any(ChatSummary.class))).thenReturn(1);
        // 模拟 AI 同步返回摘要文本
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<String> cb = inv.getArgument(3);
            cb.accept("用户已完成VLAN划分，待配置OSPF");
            return null;
        }).when(chatService).chatStream(anyString(), anyList(), anyString(), any());

        ConversationHistory h = new ConversationHistory(mapper, chatService, summaryMapper);
        // 加到第 16 轮：淘汰 6 轮 → 攒够一批触发摘要
        for (int i = 1; i <= 16; i++) {
            h.add(1L, 0L, "问" + i, "答" + i, "agent");
        }

        // 异步摘要任务执行（最多等 5 秒）
        verify(chatService, timeout(5000).atLeastOnce())
            .chatStream(anyString(), anyList(), anyString(), any());
        verify(summaryMapper, timeout(5000)).insert(any(ChatSummary.class));
        // 内存窗口仍然只保留最近 10 轮
        assertEquals(20, h.getHistory(1L, 0L, "agent").size(), "压缩不影响内存窗口大小");
    }

    @Test
    void 新摘要会合并已有摘要一起交给AI() throws Exception {
        ChatSummaryMapper summaryMapper = mock(ChatSummaryMapper.class);
        ChatService chatService = mock(ChatService.class);
        ChatSummary old = new ChatSummary();
        old.setId(1L);
        old.setSummary("旧摘要：用户规划了三层网络架构");
        when(summaryMapper.selectOne(any())).thenReturn(old);
        when(summaryMapper.updateById(any(ChatSummary.class))).thenReturn(1);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<String> cb = inv.getArgument(3);
            cb.accept("合并后的新摘要");
            return null;
        }).when(chatService).chatStream(anyString(), anyList(), anyString(), any());

        ConversationHistory h = new ConversationHistory(mapper, chatService, summaryMapper);
        for (int i = 1; i <= 16; i++) {
            h.add(1L, 0L, "问" + i, "答" + i, "agent");
        }

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService, timeout(5000)).chatStream(promptCaptor.capture(), anyList(), anyString(), any());
        assertTrue(promptCaptor.getValue().contains("旧摘要：用户规划了三层网络架构"),
            "摘要提示词应包含已有摘要供 AI 合并");
        verify(summaryMapper, timeout(5000)).updateById(any(ChatSummary.class));
    }
}
