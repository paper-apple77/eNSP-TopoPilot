<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import TopologyCanvas from '../components/topology/TopologyCanvas.vue'
import ChatPanel from '../components/topology/ChatPanel.vue'
import DeviceConnector from '../components/topology/DeviceConnector.vue'
import { exportTopo } from '../api/index'
import { listTopology, getTopology, saveTopology, deleteTopology } from '../api/topology'
import { useUserStore } from '../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()
const topologyJson = ref('{"devices":[],"connections":[]}')
const designMode = ref(false)
const connectedDevices = ref<string[]>([])

// ===== 拓扑持久化状态 =====
const currentTopologyId = ref<number | null>(null)
const currentTopologyName = ref('')
const saving = ref(false)
const topoListVisible = ref(false)
const topoList = ref<any[]>([])
const listLoading = ref(false)

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
  currentTopologyId.value = null
  currentTopologyName.value = ''
}

/** 保存拓扑到 MySQL */
async function handleSaveTopology() {
  if (deviceCount.value === 0) {
    ElMessage.warning('画布还是空的，先添加几台设备再保存')
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('给拓扑起个名字，方便以后找回', '保存拓扑', {
      inputValue: currentTopologyName.value,
      inputPlaceholder: '如：园区网三层架构',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim() ? true : '请输入名称'),
    })
    saving.value = true
    const res = await saveTopology({
      id: currentTopologyId.value,
      name: value.trim(),
      topologyJson: topologyJson.value,
      sourceType: 'manual',
    })
    currentTopologyId.value = res.data
    currentTopologyName.value = value.trim()
    ElMessage.success('保存成功')
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 打开"我的拓扑"列表 */
async function openTopoList() {
  topoListVisible.value = true
  listLoading.value = true
  try {
    const res = await listTopology()
    topoList.value = res.data || []
  } finally {
    listLoading.value = false
  }
}

/** 从列表加载拓扑到画布 */
async function handleLoadTopology(row: any) {
  try {
    if (deviceCount.value > 0) {
      await ElMessageBox.confirm('加载会覆盖当前画布内容，继续吗？', '加载拓扑', {
        type: 'warning',
        confirmButtonText: '加载',
        cancelButtonText: '取消',
      })
    }
    const res = await getTopology(row.id)
    topologyJson.value = res.data.topologyJson
    currentTopologyId.value = row.id
    currentTopologyName.value = row.name
    topoListVisible.value = false
    ElMessage.success('已加载')
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '加载失败')
  }
}

/** 删除拓扑 */
async function handleDeleteTopology(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.name}」吗？删除后无法恢复`, '删除拓扑', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await deleteTopology(row.id)
    if (currentTopologyId.value === row.id) {
      currentTopologyId.value = null
      currentTopologyName.value = ''
    }
    const res = await listTopology()
    topoList.value = res.data || []
    ElMessage.success('删除成功')
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '删除失败')
  }
}

function formatTime(t: any): string {
  if (!t) return '-'
  const s = typeof t === 'string' ? t : String(t)
  return s.replace('T', ' ').slice(0, 16)
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

const deviceCount = computed(() => {
  try { return JSON.parse(topologyJson.value).devices?.length || 0 }
  catch { return 0 }
})
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
        <el-tag v-if="currentTopologyId" type="info" size="small">{{ currentTopologyName }}</el-tag>
        <el-tag v-if="!designMode" type="success" size="small">智能配网</el-tag>
        <el-tag v-if="designMode" type="warning" size="small">拓扑设计</el-tag>
        <el-tag v-if="deviceCount > 0" type="info" size="small">{{ deviceCount }} 台设备</el-tag>
        <el-button size="small" :disabled="deviceCount === 0" :loading="saving" @click="handleSaveTopology">
          💾 保存
        </el-button>
        <el-button size="small" plain @click="openTopoList">📂 我的拓扑</el-button>
        <el-button v-if="designMode && deviceCount > 0" @click="handleExport" type="success" plain size="small">
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
        <ChatPanel :key="designMode ? 'design' : 'connect'" :topology-json="topologyJson" :mode="designMode ? 'design' : 'connect'" :connected-devices="connectedDevices" :topology-id="currentTopologyId" @topo-update="onCanvasChange" />
      </div>
    </div>

    <!-- 我的拓扑列表 -->
    <el-dialog v-model="topoListVisible" title="我的拓扑" width="640px">
      <el-table :data="topoList" v-loading="listLoading" size="small" empty-text="还没有保存过拓扑">
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="设备数" width="80">
          <template #default="{ row }">{{ row.deviceCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleLoadTopology(row)">加载</el-button>
            <el-button link type="danger" size="small" @click="handleDeleteTopology(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
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
