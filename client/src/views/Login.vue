<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'

/**
 * 登录页
 *
 * 登录成功 → Pinia store 保存 token + email → 跳转首页
 * 错误在 Axios 拦截器统一处理（见 api/request.ts），这里 catch 空即可
 */
const router = useRouter()
const userStore = useUserStore()

const form = ref({ email: '', password: '' })
const loading = ref(false)

async function handleLogin() {
  if (!form.value.email || !form.value.password) {
    ElMessage.warning('请填写邮箱和密码')
    return
  }
  loading.value = true
  try {
    await userStore.login(form.value.email, form.value.password)
    ElMessage.success('登录成功')
    router.push('/')  // 跳转拓扑列表
  } catch {
    // 错误已在 Axios 响应拦截器处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-container">
    <div class="login-card">
      <h1>🔗 AI 网络拓扑助手</h1>
      <p class="subtitle">eNSP 拓扑识别与智能配置平台</p>

      <el-form label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱" size="large" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
          />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" class="login-btn" @click="handleLogin">
          登 录
        </el-button>
      </el-form>

      <p class="register-link">
        还没有账号？<router-link to="/register">立即注册</router-link>
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  width: 420px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

h1 {
  text-align: center;
  font-size: 24px;
  margin-bottom: 4px;
}

.subtitle {
  text-align: center;
  color: #999;
  font-size: 14px;
  margin-bottom: 32px;
}

.login-btn {
  width: 100%;
  margin-top: 8px;
}

.register-link {
  text-align: center;
  margin-top: 20px;
  color: #999;
  font-size: 14px;
}

.register-link a {
  color: #667eea;
}
</style>
