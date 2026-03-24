<template>
  <el-container class="main-layout">
    <el-aside width="220px" class="aside">
      <div class="logo">📈 量化交易 · 离线版</div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#1e3a5f"
        text-color="#93c5fd"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>行情看板</span>
        </el-menu-item>
        <el-menu-item index="/strategy">
          <el-icon><Setting /></el-icon>
          <span>策略管理</span>
        </el-menu-item>
        <el-menu-item index="/strategy/backtest">
          <el-icon><TrendCharts /></el-icon>
          <span>回测分析</span>
        </el-menu-item>
        <el-menu-item index="/trading">
          <el-icon><Monitor /></el-icon>
          <span>实盘监控</span>
        </el-menu-item>
        <el-menu-item index="/market/stock">
          <el-icon><TrendCharts /></el-icon>
          <span>股票历史</span>
        </el-menu-item>
        <el-menu-item index="/account">
          <el-icon><Wallet /></el-icon>
          <span>账户资产</span>
        </el-menu-item>
        <el-sub-menu index="data">
          <template #title>
            <el-icon><DataAnalysis /></el-icon>
            <span>数据中心</span>
          </template>
          <el-menu-item index="/data/overview">数据管理</el-menu-item>
          <el-menu-item index="/data/import">导入导出</el-menu-item>
          <el-menu-item index="/data/stocks">股票浏览器</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="market">
          <template #title>
            <el-icon><TrendCharts /></el-icon>
            <span>行情分析</span>
          </template>
          <el-menu-item index="/market/stock">股票历史</el-menu-item>
          <el-menu-item index="/market/kline">K线图</el-menu-item>
          <el-menu-item index="/market/stock-picker">选股器</el-menu-item>
          <el-menu-item index="/market/realtime">实时盯盘</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="strategy">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span>策略中心</span>
          </template>
          <el-menu-item index="/strategy/list">策略列表</el-menu-item>
          <el-menu-item index="/strategy/editor">策略编辑</el-menu-item>
          <el-menu-item index="/strategy/backtest">回测分析</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="trade">
          <template #title>
            <el-icon><Wallet /></el-icon>
            <span>交易管理</span>
          </template>
          <el-menu-item index="/trade/records">交易记录</el-menu-item>
          <el-menu-item index="/trade/positions">持仓分析</el-menu-item>
          <el-menu-item index="/trade/profit">盈亏分析</el-menu-item>
        </el-sub-menu>
        <el-menu-item index="/portfolio">
          <el-icon><FolderOpened /></el-icon>
          <span>投资组合</span>
        </el-menu-item>
        <el-menu-item index="/report">
          <el-icon><Document /></el-icon>
          <span>报表中心</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Tools /></el-icon>
          <span>系统设置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ $route.meta?.title || '量化交易' }}</span>
        <div class="user-area">
          <el-tag size="small" type="info">离线模式</el-tag>
          <el-dropdown>
            <span class="user-name">
              {{ userStore.username }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleLogout">退出</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { DataAnalysis, DataLine, TrendCharts, Monitor, Setting, Wallet, FolderOpened, Document, Tools, ArrowDown } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()

const handleLogout = () => {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.main-layout {
  height: 100vh;
  background: #eef2f7;
}
.aside {
  background: #1e3a5f;
}
.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  font-size: 14px;
  font-weight: bold;
  color: #93c5fd;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}
.header {
  background: #26496b;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
.title {
  font-size: 18px;
  color: #1e293b;
  font-weight: 600;
}
.user-area {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-name {
  color: #475569;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
}
.main {
  background: #eef2f7;
  padding: 20px;
  overflow-y: auto;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
