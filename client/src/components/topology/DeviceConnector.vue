<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { scanDevices, connectAllDevices, disconnectDevice, getConnectedDevices } from '../../api/index'
import request from '../../api/request'
import { ElMessage } from 'element-plus'

const props = defineProps<{ topologyJson?: string }>()
const emit = defineEmits<{ topologyUpdate: [json: string] }>()

const scanning = ref(false)
const connecting = ref(false)
const connectedDevices = ref<string[]>([])
const authFailedDevices = ref<any[]>([])

/** 导入 .topo */
function handleImport() {
  const input = document.createElement('input')
  input.type = 'file'; input.accept = '.topo'
  input.onchange = async (e: any) => {
    const file = e.target.files?.[0]
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    try {
      const res = await request.post('/chat/import-topo', form, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      const data = res.data || res
      if (data.topologyJson) {
        emit('topologyUpdate', data.topologyJson)
        ElMessage.success(`导入成功，${data.deviceCount} 台设备`)
      }
    } catch (e: any) {
      ElMessage.error('导入失败: ' + (e.message || '文件格式错误'))
    }
  }
  input.click()
}

async function handleScan() {
  scanning.value = true
  try {
    const res = await scanDevices(2000, 2050)
    const ports = res.data || res.ports || []
    if (ports.length > 0) {
      ElMessage.success(`发现 ${ports.length} 个设备端口`)
    } else {
      ElMessage.warning('未发现运行中的 eNSP 设备')
    }
  } finally { scanning.value = false }
}

async function handleConnectAll() {
  connecting.value = true
  try {
    const res = await connectAllDevices(props.topologyJson)
    const data = res.data || res
    const hasDevices = props.topologyJson && JSON.parse(props.topologyJson).devices?.length > 0
    if (data.topologyJson && !hasDevices) {
      emit('topologyUpdate', data.topologyJson)
    }
    ElMessage.success(`已连接 ${data.connected} 台设备`)
    if (data.pwdChanged?.length > 0) {
      ElMessage.warning(`防火墙密码已重置: ${data.pwdChanged.join(', ')} 的新密码为 admin@123`)
    }
    if (data.authFailed?.length > 0) {
      authFailedDevices.value = data.authFailed
      ElMessage.warning(`${data.authMsg || '部分设备需要密码验证'}`)
    }
    refreshConnected()
  } catch (e: any) {
    ElMessage.error('连接失败: ' + (e.message || '请先启动 eNSP 中的拓扑'))
  } finally { connecting.value = false }
}

async function refreshConnected() {
  try {
    const res = await getConnectedDevices()
    connectedDevices.value = res.data || res.devices || []
  } catch {}
}

const showAuthDialog = ref(false)
const authTarget = ref<any>(null)
const authOption = ref<'new' | 'existing'>('new')
const authPassword = ref('')
const authConnecting = ref(false)

function openAuthDialog(dev: any) {
  authTarget.value = dev
  authOption.value = 'new'
  authPassword.value = ''
  showAuthDialog.value = true
}

async function handleConnectFirewall() {
  const dev = authTarget.value
  if (!dev) return
  if (authOption.value === 'existing' && !authPassword.value) { ElMessage.warning('请输入密码'); return }
  authConnecting.value = true
  try {
    const body: any = { deviceName: dev.name, port: dev.port, option: authOption.value }
    if (authOption.value === 'existing') body.password = authPassword.value
    const res = await request.post('/chat/devices/connect-firewall', body)
    if (res.code === 200) {
      ElMessage.success(`${dev.name} 连接成功`)
      if (res.data?.pwdChanged) ElMessage.warning(`${dev.name} 密码已重置为 admin@123`)
      authFailedDevices.value = authFailedDevices.value.filter((d: any) => d.name !== dev.name)
      showAuthDialog.value = false
      refreshConnected()
    } else {
      ElMessage.error(res.message || '登录失败')
    }
  } catch (e: any) { ElMessage.error('连接失败: ' + (e.message || '')) }
  finally { authConnecting.value = false }
}

async function handleDisconnect(name: string) {
  await disconnectDevice(name)
  refreshConnected()
}
</script>

<template>
  <div class="device-connector">
    <div class="connector-header">eNSP 设备</div>
    <div class="connector-body">
      <div class="hint">先导入拓扑 → 启动 eNSP 设备 → 扫描端口 → 一键连接</div>

      <div class="btn-group">
        <button @click="handleImport" class="btn">
          📂 导入拓扑
        </button>
        <button @click="handleScan" :disabled="scanning" class="btn">
          {{ scanning ? '扫描中...' : '🔍 扫描端口' }}
        </button>
        <button @click="handleConnectAll" :disabled="connecting" class="btn primary">
          {{ connecting ? '连接中...' : '🔌 一键连接' }}
        </button>
      </div>

      <!-- 需要密码的设备 -->
      <div v-if="authFailedDevices.length > 0" class="auth-section">
        <div class="list-title">🔒 需要密码:</div>
        <div v-for="d in authFailedDevices" :key="d.name" class="auth-item">
          <span>{{ d.name }}</span>
          <button @click="openAuthDialog(d)" class="btn-auth">🔑 登录</button>
        </div>
      </div>

      <!-- 防火墙登录弹窗 -->
      <div v-if="showAuthDialog" class="auth-overlay" @click.self="showAuthDialog = false">
        <div class="auth-dialog">
          <div class="auth-title">{{ authTarget?.name }} 需要登录</div>
          <label class="auth-radio"><input type="radio" v-model="authOption" value="new" /> 初始密码（未修改过密码）</label>
          <label class="auth-radio"><input type="radio" v-model="authOption" value="existing" /> 已设置过密码</label>
          <input v-if="authOption === 'existing'" v-model="authPassword" type="password" placeholder="输入密码" class="pwd-input" />
          <div v-if="authOption === 'new'" class="hint">使用默认密码 Admin@123，自动改为 admin@123</div>
          <div class="auth-btns">
            <button @click="showAuthDialog = false" class="btn-cancel">取消</button>
            <button @click="handleConnectFirewall" :disabled="authConnecting" class="btn-submit">{{ authConnecting ? '连接中...' : '连接' }}</button>
          </div>
        </div>
      </div>

      <div v-if="connectedDevices.length > 0" class="list">
        <div v-for="d in connectedDevices" :key="d" class="item">
          🟢 {{ d }}
          <button @click="handleDisconnect(d)" class="disconnect">✕</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.device-connector { margin: 8px; background: #fff; border: 1px solid #eee; border-radius: 6px; }
.connector-header { padding: 8px 12px; font-size: 13px; font-weight: 600; border-bottom: 1px solid #eee; background: #fafafa; }
.connector-body { padding: 10px; }
.hint { font-size: 11px; color: #999; margin-bottom: 10px; text-align: center; }
.btn-group { display: flex; flex-direction: column; gap: 6px; }
.btn { padding: 8px 12px; border: 1px solid #ddd; background: #fff; border-radius: 4px; cursor: pointer; font-size: 13px; text-align: center; }
.btn.primary { border-color: #409EFF; color: #409EFF; font-weight: 500; }
.btn:hover { background: #f5f5f5; }
.btn.primary:hover { background: #409EFF; color: #fff; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.list { margin-top: 10px; border-top: 1px solid #eee; padding-top: 8px; }
.item { display: flex; justify-content: space-between; align-items: center; padding: 4px 0; font-size: 12px; }
.disconnect { border: none; background: none; color: #f56c6c; cursor: pointer; font-size: 14px; }
.auth-section { margin-top: 10px; border-top: 1px solid #eee; padding-top: 8px; }
.auth-item { display: flex; align-items: center; gap: 6px; padding: 4px 0; font-size: 12px; }
.pwd-input { flex: 1; border: 1px solid #ddd; border-radius: 3px; padding: 3px 6px; font-size: 12px; width: 120px; }
.btn-auth { padding: 3px 10px; border: 1px solid #E6A23C; background: #fff; color: #E6A23C; border-radius: 3px; cursor: pointer; font-size: 11px; }
.btn-auth:hover { background: #E6A23C; color: #fff; }
.auth-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,.3); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.auth-dialog { background: #fff; border-radius: 8px; padding: 20px; width: 340px; box-shadow: 0 4px 20px rgba(0,0,0,.2); }
.auth-title { font-size: 15px; font-weight: 600; margin-bottom: 14px; }
.auth-radio { display: block; margin: 8px 0; font-size: 13px; cursor: pointer; }
.auth-radio input { margin-right: 6px; }
.pwd-input { width: 100%; border: 1px solid #ddd; border-radius: 4px; padding: 6px 10px; font-size: 13px; margin-top: 8px; box-sizing: border-box; }
.hint { font-size: 11px; color: #999; margin-top: 6px; }
.auth-btns { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.btn-cancel { padding: 6px 16px; border: 1px solid #ddd; background: #fff; border-radius: 4px; cursor: pointer; font-size: 13px; }
.btn-submit { padding: 6px 16px; border: none; background: #409EFF; color: #fff; border-radius: 4px; cursor: pointer; font-size: 13px; }
.btn-submit:hover { background: #337ECC; }
</style>
