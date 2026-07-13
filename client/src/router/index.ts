import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由配置 + 登录守卫
 *
 * meta.requiresAuth: true → 需要登录才能访问
 * 未登录访问时自动跳转 /login
 * 已登录访问 /login 时自动跳转首页
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/Login.vue'),
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('../views/Register.vue'),
    },
    {
      path: '/',
      name: 'Home',
      component: () => import('../views/TopologyList.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/editor/:id?',   // :id 可选：不带 id 是新建，带 id 是编辑已有拓扑
      name: 'Editor',
      component: () => import('../views/TopologyEditor.vue'),
      meta: { requiresAuth: true },
    },
  ],
})

router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else if ((to.path === '/login' || to.path === '/register') && token) {
    next('/')
  } else {
    next()
  }
})

export default router
