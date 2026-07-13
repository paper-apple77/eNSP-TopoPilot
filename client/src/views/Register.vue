<script setup lang="ts">
/**
 * 注册页
 *
 * 前端校验：邮箱非空、密码 >= 6 位、两次密码一致
 * 后端校验：邮箱唯一性（重复注册会收到 500 错误）
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()

const form = ref({ email: '', password: '', confirmPassword: '' })
const loading = ref(false)

async function handleRegister() {
  if (!form.value.email || !form.value.password) {
    ElMessage.warning('请填写完整信息')
    return
  }
  if (form.value.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (form.value.password !== form.value.confirmPassword) {
    ElMessage.warning('两次密码不一致')
    return
  }
  loading.value = true
  try {
    await userStore.register(form.value.email, form.value.password)
    ElMessage.success('注册成功')
    router.push('/')
  } catch {
    // 错误已在拦截器处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="register-container">
    <div class="register-card">
      <h1>📝 注册账号</h1>
      <p class="subtitle">加入 AI 网络拓扑助手</p>

      <el-form label-position="top" @submit.prevent="handleRegister">
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱" size="large" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="至少 6 位"
            size="large"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="再次输入密码"
            size="large"
            show-password
          />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" class="reg-btn" @click="handleRegister">
          注 册
        </el-button>
      </el-form>

      <p class="login-link">
        已有账号？<router-link to="/login">去登录</router-link>
      </p>
    </div>
  </div>
</template>

<style scoped>
.register-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);
}

.register-card {
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

.reg-btn {
  width: 100%;
  margin-top: 8px;
}

.login-link {
  text-align: center;
  margin-top: 20px;
  color: #999;
  font-size: 14px;
}

.login-link a {
  color: #43e97b;
}
</style>
