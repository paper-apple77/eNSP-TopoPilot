<script setup lang="ts">
import { ref, computed, watch } from 'vue'

const props = defineProps<{ topologyJson?: string; mode?: string; connectedDevices?: string[] }>()
const emit = defineEmits<{ topoUpdate: [json: string] }>()

interface Message { role: 'user' | 'assistant'; content: string }

const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)
const chatBody = ref<HTMLDivElement>()
const selectedDevices = ref<string[]>([])

const devices = computed(() => {
  try {
    const topo = JSON.parse(props.topologyJson || '{"devices":[]}')
    return (topo.devices || []).map((d: any) => d.name)
  } catch { return [] }
})

// 已连接设备名集合（快速查找）
const connectedSet = computed(() => new Set(props.connectedDevices || []))

// 自动选中新连接的设备
watch(() => props.connectedDevices, (list) => {
  if (list && list.length > 0) {
    selectedDevices.value = [...list]
  }
})

const emptyText = computed(() =>
  props.mode === 'design'
    ? '👋 描述你想要的网络拓扑，AI 帮你设计'
    : '👋 连接 eNSP 后在这里对话生成配置命令'
)

async function send() {
  const text = input.value.trim()
  if (!text) return
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true

  const msgIdx = messages.value.length
  messages.value.push({ role: 'assistant', content: '⏳ 等待中...' })
  let rawContent = ''

  const token = localStorage.getItem('token')
  const devicesParam = selectedDevices.value.length > 0
    ? selectedDevices.value.join(',')
    : ''

  // POST 方式避免 URL 过长
  const formData = new URLSearchParams()
  formData.append('message', text)
  formData.append('topologyJson', props.topologyJson || '{}')
  formData.append('mode', props.mode || 'connect')
  formData.append('token', token || '')
  formData.append('devices', devicesParam)

  try {
    const response = await fetch('http://localhost:8080/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: formData.toString()
    })
    if (!response.ok) { loading.value = false; return }
    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const data = line.substring(5).trim()
        if (!data) continue
        if (data === '[DONE]') {
          loading.value = false
          messages.value[msgIdx].content = rawContent.replace(/`{2,}topo[\s\S]*?(?:`{2,}|$)/g, '')
          const match = rawContent.match(/`{2,}topo\s*([\s\S]*?)`{2,}/)
          if (match) {
            messages.value[msgIdx].content = rawContent.replace(/`{2,}topo[\s\S]*?`{2,}/, '✅ 拓扑已更新')
            try {
              const update = JSON.parse(match[1].trim())
              const cur = JSON.parse(props.topologyJson || '{"devices":[],"connections":[]}')
              if (update.addDevices) cur.devices.push(...update.addDevices)
              if (update.addConnections) cur.connections.push(...update.addConnections)
              emit('topoUpdate', JSON.stringify(cur))
            } catch {}
          }
          if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
          return
        }
        if (data.startsWith('🔧')) {
          messages.value[msgIdx].content = data
          if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
          continue
        }
        if (data.startsWith('{') && (data.includes('tool_call') || data.includes('reasoning'))) continue
        // 跳过 ```json 代码块中的 tool_call
        if (data.includes('tool_call') && (data.startsWith('`') || data.startsWith('{'))) continue
        if (data.includes('🔍')) {
          messages.value[msgIdx].content = data
          continue
        }
        rawContent += data.replace(/\\n/g, '\n')
        // 清理显示：去掉 ```json tool_call 块 和 ```topo 块
        let clean = rawContent
          .replace(/```json[\s\S]*?tool_call[\s\S]*?```/g, '')
          .replace(/`{2,}topo[\s\S]*?(?:`{2,}|$)/g, '')
          .replace(/\n{3,}/g, '\n\n')
        messages.value[msgIdx].content = clean
        if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
      }
    }
  } catch (err) {
    console.error('[Chat] error:', err)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="chat-panel">
    <div class="chat-header">AI 配置助手</div>
    <div ref="chatBody" class="chat-body">
      <div v-if="messages.length === 0" class="chat-empty">
        {{ emptyText }}
      </div>
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="msg-label">{{ m.role === 'user' ? '你' : 'AI' }}</div>
        <div class="msg-content"><pre>{{ m.content }}</pre></div>
      </div>
    </div>
    <div class="chat-input">
      <!-- 设备选择下拉框，仅连接模式显示 -->
      <div v-if="mode !== 'design' && devices.length > 0" class="device-select">
        <el-select
          v-model="selectedDevices"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="选择设备（可选，不选默认全部）"
          size="small"
          clearable
        >
          <el-option
            v-for="d in devices"
            :key="d"
            :label="d"
            :value="d"
          >
            <span :style="{ color: connectedSet.has(d) ? '#67c23a' : '#999' }">
              {{ connectedSet.has(d) ? '🟢' : '⚪' }} {{ d }}
            </span>
          </el-option>
        </el-select>
      </div>
      <textarea v-model="input" :placeholder="mode === 'design' ? '描述你想要的网络拓扑...' : '描述你想要的网络配置...'" :disabled="loading" :rows="2" @keydown.enter.exact.prevent="send" />
      <button :disabled="loading || !input.trim()" @click="send">{{ loading ? '...' : '发送' }}</button>
    </div>
  </div>
</template>

<style scoped>
.chat-panel { flex: 1; display: flex; flex-direction: column; background: #fff; min-height: 0; }
.chat-header { padding: 12px 16px; font-weight: 600; font-size: 14px; border-bottom: 1px solid #eee; flex-shrink: 0; }
.chat-body { flex: 1; overflow-y: auto; padding: 12px; min-height: 0; }
.chat-empty { color: #999; text-align: center; padding-top: 60px; font-size: 13px; }
.msg { margin-bottom: 12px; }
.msg-label { font-size: 11px; color: #999; margin-bottom: 4px; }
.msg-content pre { margin: 0; padding: 10px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; word-break: break-all; }
.msg.user .msg-content pre { background: #EEF2FF; }
.msg.assistant .msg-content pre { background: #F5F5F5; }
.chat-input { flex-shrink: 0; display: flex; flex-direction: column; padding: 10px; border-top: 1px solid #eee; gap: 8px; }
.chat-input textarea { width: 100%; border: 1px solid #ddd; border-radius: 4px; padding: 8px 10px; font-size: 13px; outline: none; resize: vertical; font-family: inherit; min-height: 40px; box-sizing: border-box; }
.chat-input button { align-self: flex-end; padding: 8px 14px; background: #409EFF; color: #fff; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
.chat-input button:disabled { background: #ccc; cursor: not-allowed; }
.device-select { width: 100%; }
</style>
