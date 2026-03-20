import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/store/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', noAuth: true }
  },
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/dashboard/Index.vue'),
    meta: { title: '行情看板' }
  },
  {
    path: '/strategy',
    name: 'Strategy',
    component: () => import('@/views/strategy/Index.vue'),
    meta: { title: '策略管理' }
  },
  {
    path: '/backtest',
    name: 'Backtest',
    component: () => import('@/views/backtest/Index.vue'),
    meta: { title: '回测分析' }
  },
  {
    path: '/trading',
    name: 'Trading',
    component: () => import('@/views/trading/Index.vue'),
    meta: { title: '实盘监控' }
  },
  {
    path: '/account',
    name: 'Account',
    component: () => import('@/views/account/Index.vue'),
    meta: { title: '账户资产' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const userStore = useUserStore()
  if (!to.meta?.noAuth && !userStore.token) {
    next('/login')
  } else {
    next()
  }
})

export default router
