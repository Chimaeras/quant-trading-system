import { createRouter, createWebHistory } from 'vue-router'

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
    redirect: '/data/overview',
    children: [
      { path: 'data/overview', name: 'DataOverview', component: () => import('@/views/data/DataManagement.vue'), meta: { title: '数据管理' } },
      { path: 'data/import', name: 'DataImport', component: () => import('@/views/data/DataImport.vue'), meta: { title: '数据导入导出' } },
      { path: 'data/stocks', name: 'StockBrowser', component: () => import('@/views/data/StockBrowser.vue'), meta: { title: '股票浏览器' } },
      { path: 'market/kline', name: 'KLine', component: () => import('@/views/market/KLineView.vue'), meta: { title: 'K线图' } },
      { path: 'market/stock-picker', name: 'StockPicker', component: () => import('@/views/market/StockPicker.vue'), meta: { title: '选股器' } },
      { path: 'strategy/list', name: 'StrategyList', component: () => import('@/views/strategy/StrategyList.vue'), meta: { title: '策略列表' } },
      { path: 'strategy/editor', name: 'StrategyEditor', component: () => import('@/views/strategy/StrategyEditor.vue'), meta: { title: '策略编辑' } },
      { path: 'strategy/backtest', name: 'Backtest', component: () => import('@/views/strategy/BacktestView.vue'), meta: { title: '回测分析' } },
      { path: 'trade/records', name: 'TradeRecords', component: () => import('@/views/trade/TradeRecords.vue'), meta: { title: '交易记录' } },
      { path: 'trade/positions', name: 'Positions', component: () => import('@/views/trade/PositionAnalysis.vue'), meta: { title: '持仓分析' } },
      { path: 'trade/profit', name: 'ProfitAnalysis', component: () => import('@/views/trade/ProfitAnalysis.vue'), meta: { title: '盈亏分析' } },
      { path: 'portfolio', name: 'Portfolio', component: () => import('@/views/portfolio/PortfolioManage.vue'), meta: { title: '投资组合' } },
      { path: 'report', name: 'Report', component: () => import('@/views/report/ReportCenter.vue'), meta: { title: '报表中心' } },
      { path: 'settings', name: 'Settings', component: () => import('@/views/system/Settings.vue'), meta: { title: '系统设置' } },
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/dashboard/Index.vue'), meta: { title: '概览' } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, _from, next) => {
  document.title = `${to.meta?.title || '量化交易'} - 离线版`
  const token = localStorage.getItem('token')
  if (!to.meta?.noAuth && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
