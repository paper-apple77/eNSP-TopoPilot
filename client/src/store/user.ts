import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as loginApi, register as registerApi, logout as logoutApi } from '../api/user'

/**
 * 用户状态管理 (Pinia)
 *
 * token 和 email 同时存 Pinia 内存 + localStorage：
 *   Pinia → 页面响应式（导航栏显示用户名）
 *   localStorage → 刷新页面后 token 还在
 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const email = ref(localStorage.getItem('email') || '')

  /** 保存登录态 */
  function setAuth(t: string, e: string) {
    token.value = t
    email.value = e
    localStorage.setItem('token', t)
    localStorage.setItem('email', e)
  }

  /** 清除登录态 */
  function clearAuth() {
    token.value = ''
    email.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('email')
  }

  async function login(emailVal: string, password: string) {
    const res = await loginApi(emailVal, password)
    setAuth(res.data, emailVal)
  }

  async function register(emailVal: string, password: string) {
    const res = await registerApi(emailVal, password)
    setAuth(res.data, emailVal)
  }

  async function logout() {
    await logoutApi()
    clearAuth()
  }

  return { token, email, login, register, logout, clearAuth }
})
