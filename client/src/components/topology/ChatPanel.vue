<script setup lang="ts">
import { ref, nextTick } from 'vue'

const props = defineProps<{ topologyJson?: string; mode?: string }>()
const emit = defineEmits<{ topoUpdate: [json: string] }>()

interface Message { role: 'user' | 'assistant'; content: string }

const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)
const chatBody = ref<HTMLDivElement>()

async function send() {
  const text = input.value.trim()
  if (!text) return
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true

  const assistantMsg: Message = { role: 'assistant', content: '' }
  messages.value.push(assistantMsg)
  const msgIdx = messages.value.length - 1
  let rawContent = ''

  const token = localStorage.getItem('token')
  const url = `http://localhost:8080/api/chat/stream?message=${encodeURIComponent(text)}&topologyJson=${encodeURIComponent(props.topologyJson || '{}')}&mode=${props.mode || 'connect'}&token=${encodeURIComponent(token || '')}`

  const es = new EventSource(url)
  es.onmessage = (event) => {
    if (!event.data) return
    if (event.data === '[DONE]') {
      es.close(); loading.value = false
      const match = rawContent.match(/`{2,}topo\s*([\s\S]*?)`{2,}/)
      if (match) {
        assistantMsg.content = rawContent.replace(/`{2,}topo[\s\S]*?`{2,}/, '✅ 拓扑已更新')
        try {
          const update = JSON.parse(match[1].trim())
          const cur = JSON.parse(props.topologyJson || '{"devices":[],"connections":[]}')
          if (update.addDevices) cur.devices.push(...update.addDevices)
          if (update.addConnections) cur.connections.push(...update.addConnections)
          emit('topoUpdate', JSON.stringify(cur))
        } catch(e) {}
      }
      return
    }
    // 跳过 JSON 工具调用块
    if (event.data.startsWith('{') && (event.data.includes('tool_call') || event.data.includes('reasoning'))) return
    rawContent += event.data.replace(/\\n/g, '\n')
    assistantMsg.content = rawContent.replace(/`{2,}topo[\s\S]*?(?:`{2,}|$)/g, '')
    nextTick(() => {
      if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
    })
  }
  es.onerror = () => { es.close(); loading.value = false }
}

function scrollBottom() {
  if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
}
</script>

<template>
  <div class="chat-panel">
    <div class="chat-header">AI 配置助手</div>
    <div ref="chatBody" class="chat-body">
      <div v-if="messages.length === 0" class="chat-empty">
        👋 连接 eNSP 后在这里对话生成配置命令
      </div>
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="msg-label">{{ m.role === 'user' ? '你' : 'AI' }}</div>
        <div class="msg-content"><pre>{{ m.content }}</pre></div>
      </div>
    </div>
    <div class="chat-input">
      <textarea v-model="input" placeholder="描述你想要的网络..." :disabled="loading" :rows="2" @keydown.enter.exact.prevent="send" />
      <button :disabled="loading || !input.trim()" @click="send">{{ loading ? '...' : '发送' }}</button>
    </div>
  </div>
</template>

<style scoped>
.chat-panel { flex: 1; display: flex; flex-direction: column; background: #fff; min-height: 0; }
.chat-header { padding: 12px 16px; font-weight: 600; font-size: 14px; border-bottom: 1px solid #eee; flex-shrink: 0; }
.chat-body { flex: 1; overflow-y: auto; padding: 12px; min-height: 0; }
.chat-input { flex-shrink: 0; }
.chat-empty { color: #999; text-align: center; padding-top: 60px; font-size: 13px; }
.msg { margin-bottom: 12px; }
.msg-label { font-size: 11px; color: #999; margin-bottom: 4px; }
.msg-content pre { margin: 0; padding: 10px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; word-break: break-all; }
.msg.user .msg-content pre { background: #EEF2FF; }
.msg.assistant .msg-content pre { background: #F5F5F5; }
.chat-input { display: flex; padding: 10px; border-top: 1px solid #eee; }
.chat-input textarea { flex: 1; border: 1px solid #ddd; border-radius: 4px; padding: 8px 10px; font-size: 13px; outline: none; resize: vertical; font-family: inherit; min-height: 40px; }
.chat-input button { margin-left: 8px; padding: 8px 14px; background: #409EFF; color: #fff; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
.chat-input button:disabled { background: #ccc; cursor: not-allowed; }
</style>
