import { createRouter, createWebHistory, isNavigationFailure, NavigationFailureType } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/data/overview',
    children: [
      { path: 'data/overview', name: 'DataOverview', component: () => import('@/views/data/DataManagement.vue'), meta: { title: '数据管理' } },
      { path: 'data/quality', name: 'DataQuality', component: () => import('@/views/data/DataQuality.vue'), meta: { title: '数据质量' } },
      { path: 'data/stocks', name: 'StockBrowser', component: () => import('@/views/data/StockBrowser.vue'), meta: { title: '股票浏览器' } },
      { path: 'report/daily', name: 'DailyReport', component: () => import('@/views/report/DailyReport.vue'), meta: { title: '选股日报' } },
      { path: 'compare/strategies', name: 'StrategyCompare', component: () => import('@/views/compare/StrategyCompare.vue'), meta: { title: '策略对比' } },
      { path: 'watchlist', name: 'Watchlist', component: () => import('@/views/watchlist/WatchlistView.vue'), meta: { title: '自选股池' } },
      { path: 'market/stock', name: 'StockDetail', component: () => import('@/views/market/StockDetailView.vue'), meta: { title: '股票详情' } },
      { path: 'market/kline', name: 'KLine', component: () => import('@/views/market/KLineView.vue'), meta: { title: 'K线图' } },
      { path: 'market/stock-picker', name: 'StockPicker', component: () => import('@/views/market/StockPicker.vue'), meta: { title: '选股打分' } },
      { path: 'market/t1-candidate', name: 'T1Candidate', component: () => import('@/views/market/T1Candidate.vue'), meta: { title: 'T+1 次日候选' } },
      { path: 'screener', name: 'Screener', component: () => import('@/views/screener/ScreenerView.vue'), meta: { title: '条件选股' } },
      { path: 'factor/research', name: 'FactorResearch', component: () => import('@/views/factor/FactorResearch.vue'), meta: { title: '因子研究' } },
      { path: 'research/probability', name: 'ProbabilityResearch', component: () => import('@/views/research/ProbabilityResearch.vue'), meta: { title: '概率研究' } },
      { path: 'research/tasks', name: 'ResearchTasks', component: () => import('@/views/research/ResearchTasks.vue'), meta: { title: '研究任务工作台' } },
      { path: 'research/rolling-walk-forward', name: 'RollingWalkForward', component: () => import('@/views/research/RollingWalkForward.vue'), meta: { title: '滚动 Walk-Forward' } },
      { path: 'backtest/scoring', name: 'ScoringBacktest', component: () => import('@/views/backtest/ScoringBacktest.vue'), meta: { title: '打分回测' } },
      { path: 'backtest/strategy', name: 'StrategyBacktest', component: () => import('@/views/backtest/StrategyBacktest.vue'), meta: { title: '策略回测' } },
      { path: 'backtest/portfolio', name: 'PortfolioBacktest', component: () => import('@/views/backtest/PortfolioBacktest.vue'), meta: { title: '组合回测' } },
      { path: 'backtest/walk-forward', name: 'WalkForward', component: () => import('@/views/backtest/WalkForward.vue'), meta: { title: '分段稳定性（非样本外）' } },
      { path: 'account/overview', name: 'AccountOverview', component: () => import('@/views/account/AccountView.vue'), meta: { title: '模拟账户' } },
      { path: 'reconcile/position', name: 'ReconcilePosition', component: () => import('@/views/reconcile/ReconcileView.vue'), meta: { title: '持仓对账' } }
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
  const isPublic = to.path === '/login' || to.meta?.public === true
  if (isPublic) {
    next()
    return
  }
  const token = localStorage.getItem('quant_token')
  if (!token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
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