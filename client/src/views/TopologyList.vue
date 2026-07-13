<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { listTopologies, deleteTopology, importTopoFile, importZipFile } from '../api/topology'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from 'element-plus'

/**
 * 拓扑列表页（首页）
 *
 * 功能：
 *   1. 展示当前用户的拓扑卡片网格
 *   2. 新建拓扑（空画布 + 默认 JSON）
 *   3. 导入 .topo 文件（Element Plus Upload + 后端解析）
 *   4. 删除拓扑（确认弹窗）
 *   5. 点击卡片进入编辑器
 */
const router = useRouter()
const userStore = useUserStore()

interface TopologyItem {
  id: number
  name: string
  sourceType: string
  updatedAt: string
}

const topologies = ref<TopologyItem[]>([])
const loading = ref(false)

onMounted(() => fetchList())

/** 从后端获取当前用户的拓扑列表 */
async function fetchList() {
  loading.value = true
  try {
    const res = await listTopologies()
    topologies.value = res.data || []
  } finally {
    loading.value = false
  }
}

/** 删除拓扑：先弹确认框，确认后执行软删除 */
async function handleDelete(id: number) {
  await ElMessageBox.confirm('确定删除该拓扑？', '确认', { type: 'warning' })
  await deleteTopology(id)
  ElMessage.success('已删除')
  fetchList()
}

// ===== .topo 文件导入 =====

const importLoading = ref(false)

/** 文件选中后自动上传（auto-upload=false，由 on-change 触发上传逻辑） */
function handleFileChange(file: any) {
  const rawFile = file.raw || file
  if (!rawFile) return
  importLoading.value = true
  const isZip = rawFile.name.endsWith('.zip')
  const importFn = isZip ? importZipFile : importTopoFile
  importFn(rawFile)
    .then(() => {
      ElMessage.success('导入成功')
      fetchList()
    })
    .catch(() => {})
    .finally(() => { importLoading.value = false })
}

function beforeUpload(file: File) {
  if (file.name.endsWith('.topo') || file.name.endsWith('.zip')) return true
  ElMessage.warning('请选择 .topo 或 .zip 文件')
  return false
}

function handleLogout() {
  userStore.clearAuth()
  router.push('/login')
}

/** 来源类型 → 展示标签 */
function sourceLabel(type: string) {
  const map: Record<string, string> = {
    ensp_topo_file: '📄 .topo 导入',
    ensp_zip: '📦 工程导入',
    screenshot: '📷 截图识别',
    manual: '✏️ 手动绘制',
  }
  return map[type] || type
}
</script>

<template>
  <div class="home">
    <header class="header">
      <h1>🔗 AI 网络拓扑助手</h1>
      <div class="header-right">
        <span class="user-email">{{ userStore.email }}</span>
        <el-button text @click="handleLogout">退出</el-button>
      </div>
    </header>

    <main class="main">
      <div class="toolbar">
        <el-upload
          :before-upload="beforeUpload"
          :on-change="handleFileChange"
          :show-file-list="false"
          :auto-upload="false"
          accept=".topo,.zip"
        >
          <el-button :loading="importLoading" type="success">
            📄 导入 .topo / .zip
          </el-button>
        </el-upload>
      </div>

      <div v-if="loading" class="loading">加载中...</div>

      <div v-else-if="topologies.length === 0" class="empty">
        <p>📭 还没有拓扑项目</p>
        <p>点击"新建拓扑"开始</p>
      </div>

      <div v-else class="grid">
        <div
          v-for="t in topologies"
          :key="t.id"
          class="card"
          @click="router.push(`/editor/${t.id}`)"
        >
          <div class="card-body">
            <h3>{{ t.name }}</h3>
            <p class="source">{{ sourceLabel(t.sourceType) }}</p>
            <p class="time">{{ t.updatedAt?.slice(0, 16) }}</p>
          </div>
          <div class="card-actions">
            <el-button text type="danger" size="small" @click.stop="handleDelete(t.id)">
              删除
            </el-button>
          </div>
        </div>
      </div>
    </main>

  </div>
</template>

<style scoped>
.home {
  min-height: 100vh;
  background: #f5f7fa;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 32px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.header h1 {
  font-size: 20px;
  margin: 0;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-email {
  color: #666;
  font-size: 14px;
}

.main {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px;
}

.toolbar {
  margin-bottom: 24px;
  display: flex;
  gap: 12px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.card {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  cursor: pointer;
  transition: box-shadow 0.2s;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
}

.card-body h3 {
  margin: 0 0 8px;
  font-size: 16px;
}

.source, .time {
  margin: 0;
  font-size: 13px;
  color: #999;
}

.empty {
  text-align: center;
  padding: 80px 0;
  color: #999;
}

.loading {
  text-align: center;
  padding: 40px;
  color: #999;
}
</style>
