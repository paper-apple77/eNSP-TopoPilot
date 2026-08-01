<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import TopologyCanvas from '../components/topology/TopologyCanvas.vue'
import ChatPanel from '../components/topology/ChatPanel.vue'
import DeviceConnector from '../components/topology/DeviceConnector.vue'
import { exportTopo } from '../api/index'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()
const topologyJson = ref('{"devices":[],"connections":[]}')
const designMode = ref(false)
const connectedDevices = ref<string[]>([])

async function handleLogout() {
  await userStore.logout()
  router.push('/login')
}

/** 切换到智能配网 */
function switchToConnect() {
  designMode.value = false
}

/** 切换到拓扑设计（清空画布） */
function switchToDesign() {
  designMode.value = true
  topologyJson.value = '{"devices":[],"connections":[]}'
}

/** 导出 .topo — 仅在拓扑设计模式使用 */
async function handleExport() {
  try {
    const topo = JSON.parse(topologyJson.value)
    const name = prompt('输入项目名称:', 'my_topology')
    if (!name) return
    const res = await exportTopo(topo, name)
    const data = res.data || res
    if (data.topoXml) {
      const blob = new Blob([data.topoXml], { type: 'application/xml' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url; a.download = data.filename || (name + '.topo'); a.click()
      URL.revokeObjectURL(url)
      ElMessage.success('导出成功')
    }
  } catch { ElMessage.error('导出失败') }
}

function onCanvasChange(json: string) {
  topologyJson.value = json
}

function onConnectedChange(devices: string[]) {
  connectedDevices.value = devices
}

function deviceCount(): number {
  try { return JSON.parse(topologyJson.value).devices?.length || 0 }
  catch { return 0 }
}
</script>

<template>
  <div class="editor-page">
    <!-- 顶部：纯导航切换 -->
    <header class="editor-header">
      <span class="logo">TopoPilot</span>
      <div class="toolbar-actions">
        <el-button :type="!designMode ? 'primary' : 'default'" @click="switchToConnect">
          🔌 智能配网
        </el-button>
        <el-button :type="designMode ? 'warning' : 'default'" @click="switchToDesign">
          🤖 拓扑设计
        </el-button>
      </div>
      <div class="toolbar-info">
        <el-tag v-if="!designMode" type="success" size="small">智能配网</el-tag>
        <el-tag v-if="designMode" type="warning" size="small">拓扑设计</el-tag>
        <el-tag v-if="deviceCount() > 0" type="info" size="small">{{ deviceCount() }} 台设备</el-tag>
        <el-button v-if="designMode && deviceCount() > 0" @click="handleExport" type="success" plain size="small">
          📤 导出 .topo
        </el-button>
        <el-button @click="handleLogout" type="danger" plain size="small">
          🚪 退出
        </el-button>
      </div>
    </header>

    <!-- 主区域 -->
    <div class="editor-body">
      <div class="canvas-wrapper">
        <TopologyCanvas :topology-json="topologyJson" @change="onCanvasChange" />
      </div>
      <div class="right-panel">
        <DeviceConnector v-if="!designMode" :topology-json="topologyJson" @topology-update="onCanvasChange" @connected-change="onConnectedChange" />
        <ChatPanel :key="designMode ? 'design' : 'connect'" :topology-json="topologyJson" :mode="designMode ? 'design' : 'connect'" :connected-devices="connectedDevices" @topo-update="onCanvasChange" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.editor-page { height: 100vh; display: flex; flex-direction: column; overflow: hidden; }
.editor-header { display: flex; align-items: center; gap: 16px; padding: 10px 16px; background: #fff; border-bottom: 1px solid #eee; flex-shrink: 0; }
.logo { font-weight: 700; font-size: 16px; color: #409EFF; }
.toolbar-actions { flex: 1; display: flex; gap: 8px; }
.toolbar-info { display: flex; align-items: center; gap: 6px; }
.editor-body { flex: 1; display: flex; overflow: hidden; min-height: 0; }
.canvas-wrapper { flex: 1; background: #fafafa; position: relative; overflow: hidden; min-height: 0; }
.right-panel { width: 360px; display: flex; flex-direction: column; flex-shrink: 0; border-left: 1px solid #eee; overflow: hidden; }
</style>
