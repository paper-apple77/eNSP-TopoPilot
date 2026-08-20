<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'

// 配置 marked
marked.setOptions({ breaks: true, gfm: true })

const props = defineProps<{ topologyJson?: string; mode?: string; connectedDevices?: string[]; topologyId?: number | null }>()
const emit = defineEmits<{ topoUpdate: [json: string] }>()

interface Message { role: 'user' | 'assistant'; content: string }

function renderMd(text: string): string {
  if (!text) return ''
  // 移除 tool_call JSON 代码块
  let cleaned = text.replace(/```json[\s\S]*?tool_call[\s\S]*?```/g, '')
  // 移除 ```topo 块
  cleaned = cleaned.replace(/`{2,}topo[\s\S]*?`{2,}/g, '')
  return marked.parse(cleaned) as string
}

/** 防火墙命名规范化：AI 可能输出 FW_HZ/FW-SH 等 eNSP 默认地名命名，统一改成 FW1, FW2, FW3。
 *  返回旧名→新名映射，调用方可同步替换 AI 正文里的提及。 */
function normalizeFirewallNames(update: any): Map<string, string> {
  const rename = new Map<string, string>()
  if (!update?.addDevices?.length) return rename
  // 画布上已有的 FW 编号（增量更新时避免重号）
  let start = 1
  try {
    const cur = JSON.parse(props.topologyJson || '{}')
    for (const d of cur.devices || []) {
      const m = /^FW(\d+)$/i.exec(d.name || '')
      if (m) start = Math.max(start, parseInt(m[1]) + 1)
    }
  } catch {}
  let n = start
  for (const d of update.addDevices || []) {
    if (/^FW[-_ ]?[A-Z]{2,3}\d*$/i.test(d.name || '')) {
      rename.set(d.name, `FW${n++}`)
    }
  }
  if (!rename.size) return rename
  for (const d of update.addDevices || []) {
    if (rename.has(d.name)) d.name = rename.get(d.name)
  }
  for (const c of update.addConnections || []) {
    if (rename.has(c.fromDevice)) c.fromDevice = rename.get(c.fromDevice)
    if (rename.has(c.toDevice)) c.toDevice = rename.get(c.toDevice)
  }
  return rename
}

const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)
const chatBody = ref<HTMLDivElement>()
const selectedDevices = ref<string[]>([])
/** 用户是否手动动过设备选择：动过之后就不再自动全选，避免覆盖用户的选择 */
const userTouched = ref(false)
let abortCtrl: AbortController | null = null

const devices = computed(() => {
  try {
    const topo = JSON.parse(props.topologyJson || '{"devices":[]}')
    return (topo.devices || []).map((d: any) => d.name)
  } catch { return [] }
})

// 已连接设备名集合（快速查找）
const connectedSet = computed(() => new Set(props.connectedDevices || []))

// 自动选中新连接的设备：只在用户还没手动选过时生效，选过后不再干预
watch(() => props.connectedDevices, (list) => {
  if (list && list.length > 0 && !userTouched.value) {
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
  let receivedDone = false
  const statusLines: string[] = []

  const token = localStorage.getItem('token')
  const devicesParam = selectedDevices.value.length > 0
    ? selectedDevices.value.join(',')
    : ''

  // POST 方式避免 URL 过长；token 放 Authorization header，避免出现在代理日志/浏览器历史
  const formData = new URLSearchParams()
  formData.append('message', text)
  formData.append('topologyJson', props.topologyJson || '{}')
  formData.append('mode', props.mode || 'connect')
  formData.append('devices', devicesParam)
  if (props.topologyId) formData.append('topologyId', String(props.topologyId))

  const headers: Record<string, string> = { 'Content-Type': 'application/x-www-form-urlencoded' }
  if (token) headers['Authorization'] = `Bearer ${token}`

  abortCtrl = new AbortController()
  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers,
      body: formData.toString(),
      signal: abortCtrl.signal
    })
    if (!response.ok) {
      loading.value = false
      const errText = await response.text().catch(() => '')
      ElMessage.error(errText || `请求失败 (${response.status})`)
      return
    }
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
        // 状态提示累积显示，不覆盖内容
        if (data.startsWith('💭') || data.startsWith('🔧') || data.includes('🔍')) {
          if (!statusLines.includes(data)) statusLines.push(data)
          const display = statusLines.join('\n') + (rawContent ? '\n\n' + rawContent : '')
          messages.value[msgIdx].content = display
          if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
          continue
        }
        if (data === '[DONE]') {
          receivedDone = true
          loading.value = false
          let final = statusLines.join('\n') + '\n\n' + rawContent
          final = final
            .replace(/```json[\s\S]*?tool_call[\s\S]*?```/g, '')
            .replace(/`{2,}topo[\s\S]*?(?:`{2,}|$)/g, '')
            .replace(/\n{3,}/g, '\n\n')
          const match = rawContent.match(/`{2,}topo\s*([\s\S]*?)`{2,}/)
          if (match) {
            try {
              const update = JSON.parse(match[1].trim())
              const renames = normalizeFirewallNames(update)
              let cur = { devices: [] as any[], connections: [] as any[] }
              if (update.clear) {
                // 全量替换
                cur.devices = update.addDevices || []
                cur.connections = update.addConnections || []
              } else {
                cur = JSON.parse(props.topologyJson || '{"devices":[],"connections":[]}')
                if (update.addDevices) cur.devices.push(...update.addDevices)
                if (update.addConnections) cur.connections.push(...update.addConnections)
              }
              emit('topoUpdate', JSON.stringify(cur))
              // AI 正文里也替换旧命名，避免聊天文本和画布不一致
              for (const [oldName, newName] of renames) {
                final = final.replace(new RegExp('\\b' + oldName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\b', 'g'), newName)
              }
              final = final.replace(/`{2,}topo[\s\S]*?`{2,}/, '✅ 拓扑已更新')
            } catch {}
          }
          messages.value[msgIdx].content = final
          if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
          return
        }
        if (data.startsWith('{') && (data.includes('tool_call') || data.includes('reasoning'))) continue
        if (data.includes('tool_call') && (data.startsWith('`') || data.startsWith('{'))) continue
        rawContent += data.replace(/\\n/g, '\n')
        let clean = statusLines.join('\n') + '\n\n' + rawContent
        clean = clean
          .replace(/```json[\s\S]*?tool_call[\s\S]*?```/g, '')
          .replace(/`{2,}topo[\s\S]*?(?:`{2,}|$)/g, '')
          .replace(/\n{3,}/g, '\n\n')
        messages.value[msgIdx].content = clean
        if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
      }
    }
    // 流正常关闭但没收到 [DONE] → 后端异常中断，提示用户而不是静默停止
    if (!receivedDone) {
      const partial = (statusLines.join('\n') + '\n\n' + rawContent).trim()
      messages.value[msgIdx].content = partial + '\n\n⚠️ 连接中断，回复可能不完整。如 AI 停在半路，可发"继续"让它接着完成。'
    }
  } catch (err: any) {
    if (err.name === 'AbortError') {
      messages.value[msgIdx].content = (statusLines.join('\n') + '\n\n' + rawContent + '\n\n⚠️ 已手动停止').trim()
    } else if (err instanceof TypeError) {
      // 网络层错误：后端没启动/连接被拒
      console.error('[Chat] error:', err)
      ElMessage.error('无法连接后端服务，请确认后端已启动（默认 http://localhost:8080）')
      messages.value[msgIdx].content = (statusLines.join('\n') + '\n\n' + rawContent + '\n\n⚠️ 无法连接后端服务').trim()
    } else {
      console.error('[Chat] error:', err)
      messages.value[msgIdx].content = (statusLines.join('\n') + '\n\n' + rawContent + '\n\n⚠️ 对话异常中断').trim()
    }
  } finally {
    loading.value = false
    abortCtrl = null
  }
}

function stop() {
  abortCtrl?.abort()
  loading.value = false
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
        <div class="msg-content" v-html="renderMd(m.content)"></div>
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
          @change="userTouched = true"
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
      <button :disabled="!loading && !input.trim()" @click="send" v-if="!loading">{{ '发送' }}</button>
      <button @click="stop" v-if="loading" style="background:#f56c6c">⏹ 停止</button>
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
.msg-content { font-size: 13px; line-height: 1.6; }
.msg-content :deep(p) { margin: 0 0 8px; }
.msg-content :deep(strong) { font-weight: 600; }
.msg-content :deep(h1), .msg-content :deep(h2), .msg-content :deep(h3) { margin: 12px 0 6px; font-weight: 600; }
.msg-content :deep(h2) { font-size: 15px; }
.msg-content :deep(h3) { font-size: 14px; }
.msg-content :deep(ul), .msg-content :deep(ol) { margin: 4px 0; padding-left: 20px; }
.msg-content :deep(li) { margin: 2px 0; }
.msg-content :deep(table) { border-collapse: collapse; margin: 8px 0; font-size: 12px; width: 100%; }
.msg-content :deep(th), .msg-content :deep(td) { border: 1px solid #ddd; padding: 4px 8px; text-align: left; }
.msg-content :deep(th) { background: #f5f5f5; font-weight: 600; }
.msg-content :deep(code) { background: #f0f0f0; padding: 1px 4px; border-radius: 3px; font-size: 12px; }
.msg-content :deep(pre) { margin: 8px 0; padding: 10px; border-radius: 6px; background: #f8f8f8; font-size: 12px; white-space: pre-wrap; word-break: break-all; overflow-x: auto; }
.msg-content :deep(blockquote) { border-left: 3px solid #409EFF; padding-left: 10px; margin: 8px 0; color: #666; }
.msg-content :deep(hr) { border: none; border-top: 1px solid #eee; margin: 12px 0; }
.msg.user .msg-content { }
.chat-input { flex-shrink: 0; display: flex; flex-direction: column; padding: 10px; border-top: 1px solid #eee; gap: 8px; }
.chat-input textarea { width: 100%; border: 1px solid #ddd; border-radius: 4px; padding: 8px 10px; font-size: 13px; outline: none; resize: vertical; font-family: inherit; min-height: 40px; box-sizing: border-box; }
.chat-input button { align-self: flex-end; padding: 8px 14px; background: #409EFF; color: #fff; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
.chat-input button:disabled { background: #ccc; cursor: not-allowed; }
.device-select { width: 100%; }
</style>
