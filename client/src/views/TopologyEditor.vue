<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTopology, updateTopology } from '../api/topology'
import { ElMessage } from 'element-plus'
import TopologyCanvas from '../components/topology/TopologyCanvas.vue'

/**
 * 拓扑编辑器页
 *
 * 布局：左侧设备面板 | 中间 LogicFlow 画布 | 右侧 AI 对话面板（占位）
 *
 * 数据流：
 *   后端 → topologyJson → TopologyCanvas 渲染
 *   画布编辑 → emit change → topologyJson 更新 → 点保存写回后端
 */
const route = useRoute()
const router = useRouter()

const topologyId = ref<number | null>(null)
const topologyName = ref('新建拓扑')
const topologyJson = ref('{"devices":[],"connections":[]}')
const loading = ref(false)
const saving = ref(false)
const canvasRef = ref<InstanceType<typeof TopologyCanvas>>()

onMounted(async () => {
  const id = route.params.id
  if (id) {
    topologyId.value = Number(id)
    loading.value = true
    try {
      const res = await getTopology(topologyId.value)
      topologyName.value = res.data.name
      topologyJson.value = res.data.topologyJson
    } finally {
      loading.value = false
    }
  }
})

/** 保存拓扑 */
async function handleSave() {
  if (!topologyId.value) return
  saving.value = true
  try {
    await updateTopology(topologyId.value, topologyName.value, topologyJson.value)
    ElMessage.success('保存成功')
  } catch {
    // 错误已在拦截器处理
  } finally {
    saving.value = false
  }
}

/** 画布变更回调：更新本地 JSON */
function onCanvasChange(json: string) {
  topologyJson.value = json
}

</script>

<template>
  <div class="editor-page">
    <!-- 顶部工具栏 -->
    <header class="editor-header">
      <el-button text @click="router.push('/')">← 返回列表</el-button>
      <el-input
        v-model="topologyName"
        class="name-input"
        placeholder="拓扑名称"
        size="default"
      />
      <el-tag v-if="topologyId" type="info" size="small">
        {{ topologyJson ? JSON.parse(topologyJson).devices?.length || 0 : 0 }} 个设备
      </el-tag>
      <el-button type="primary" :loading="saving" @click="handleSave">
        保存
      </el-button>
    </header>

    <!-- 主区域 -->
    <div class="editor-body" v-loading="loading">
      <!-- 中间：LogicFlow 画布 -->
      <div class="canvas-wrapper">
        <TopologyCanvas
          ref="canvasRef"
          :topology-json="topologyJson"
          @change="onCanvasChange"
        />
      </div>

      <!-- 右侧：AI 对话面板（占位） -->
      <aside class="chat-panel">
        <div class="chat-placeholder">
          <p>💬 AI 配置助手</p>
          <p style="font-size:12px;color:#999;">选中设备后<br>可对话生成配置<br>（下一步完成）</p>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.editor-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.editor-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
  flex-shrink: 0;
}

.name-input {
  width: 260px;
}

.editor-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.canvas-wrapper {
  flex: 1;
  background: #fafafa;
  position: relative;
  overflow: hidden;
  min-height: 0; /* flex child 需要 min-height:0 才能正确收缩 */
}

.chat-panel {
  width: 260px;
  background: #fff;
  border-left: 1px solid #eee;
  flex-shrink: 0;
}

.chat-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
  color: #666;
  font-size: 14px;
}
</style>
