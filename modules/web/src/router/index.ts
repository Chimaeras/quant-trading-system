import { createRouter, createWebHistory, isNavigationFailure, NavigationFailureType } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/data/overview',
    children: [
      { path: 'data/overview', name: 'DataOverview', component: () => import('@/views/data/DataManagement.vue'), meta: { title: '数据管理' } },
      { path: 'data/stocks', name: 'StockBrowser', component: () => import('@/views/data/StockBrowser.vue'), meta: { title: '股票浏览器' } },
      { path: 'market/stock', name: 'StockDetail', component: () => import('@/views/market/StockDetailView.vue'), meta: { title: '股票详情' } },
      { path: 'market/kline', name: 'KLine', component: () => import('@/views/market/KLineView.vue'), meta: { title: 'K线图' } },
      { path: 'market/stock-picker', name: 'StockPicker', component: () => import('@/views/market/StockPicker.vue'), meta: { title: '选股打分' } },
      { path: 'backtest/scoring', name: 'ScoringBacktest', component: () => import('@/views/backtest/ScoringBacktest.vue'), meta: { title: '打分回测' } },
      { path: 'backtest/strategy', name: 'StrategyBacktest', component: () => import('@/views/backtest/StrategyBacktest.vue'), meta: { title: '策略回测' } },
      { path: 'account/overview', name: 'AccountOverview', component: () => import('@/views/account/AccountView.vue'), meta: { title: '模拟账户' } }
    ]
  },
  { path: '/login', name: 'Login', component: () => import('@/views/auth/Login.vue'), meta: { title: '登录' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, _from, next) => {
  document.title = `${to.meta?.title || '量化交易'}` as string
  next()
})

const rawPush = router.push.bind(router)
router.push = (to) =>
  rawPush(to).catch((err) => {
    if (isNavigationFailure(err, NavigationFailureType.duplicated)) {
      return Promise.resolve()
    }
    return Promise.reject(err)
  })

export default router