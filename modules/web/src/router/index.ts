import { createRouter, createWebHistory, isNavigationFailure, NavigationFailureType } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', noAuth: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'account', name: 'Account', component: () => import('@/views/account/Index.vue'), meta: { title: '账户资产' } },
      { path: 'data/overview', name: 'DataOverview', component: () => import('@/views/data/DataManagement.vue'), meta: { title: '数据管理' } },
      { path: 'data/stocks', name: 'StockBrowser', component: () => import('@/views/data/StockBrowser.vue'), meta: { title: '股票浏览器' } },
      { path: 'market/stock', name: 'StockDetail', component: () => import('@/views/market/StockDetailView.vue'), meta: { title: '股票详情' } },
      {
        path: 'market/analysis-dashboard',
        name: 'StockAnalysisDashboard',
        component: () => import('@/views/market/StockAnalysisDashboard.vue'),
        meta: { title: 'AI分析' }
      },
      { path: 'market/kline', name: 'KLine', component: () => import('@/views/market/KLineView.vue'), meta: { title: 'K线图' } },
      { path: 'market/stock-picker', name: 'StockPicker', component: () => import('@/views/market/StockPicker.vue'), meta: { title: '打分选股' } },
      { path: 'market/realtime', name: 'RealtimeWatch', component: () => import('@/views/market/RealtimeWatch.vue'), meta: { title: '实时盯盘' } },
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/dashboard/Index.vue'), meta: { title: '总览' } },
      { path: 'backtest', name: 'Backtest', component: () => import('@/views/strategy/BacktestView.vue'), meta: { title: '回测分析' } },
      { path: 'trading', name: 'Trading', component: () => import('@/views/trading/Index.vue'), meta: { title: '交易执行' } },
      { path: 'ai-chat', name: 'AiChat', component: () => import('@/views/chat/Index.vue'), meta: { title: 'AI聊天' } },
      { path: 'scheduler', name: 'Scheduler', component: () => import('@/views/scheduler/Index.vue'), meta: { title: '定时设置' } },
      { path: 'settings', name: 'Settings', component: () => import('@/views/settings/Index.vue'), meta: { title: '设置' } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, _from, next) => {
  document.title = `${to.meta?.title || '量化交易'}`
  const token = localStorage.getItem('token')
  if (!to.meta?.noAuth && !token) {
    next('/login')
  } else {
    next()
  }
})

/** 重复导航（同一路由再点一次）时 Vue Router 会 reject，避免控制台未处理 Promise + 侧栏状态异常 */
const rawPush = router.push.bind(router)
router.push = (to) =>
  rawPush(to).catch((err) => {
    if (isNavigationFailure(err, NavigationFailureType.duplicated)) {
      return Promise.resolve()
    }
    return Promise.reject(err)
  })

export default router
