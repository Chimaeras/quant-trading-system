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
        <el-sub-menu index="more">
          <template #title>
            <el-icon><Menu /></el-icon>
            <span>不常用/更多</span>
          </template>

          <el-menu-item index="/dashboard">
            <el-icon><DataLine /></el-icon>
            <span>行情看板</span>
          </el-menu-item>
          <el-menu-item index="/account">
            <el-icon><Wallet /></el-icon>
            <span>账户资产</span>
          </el-menu-item>
          <el-menu-item index="/market/stock-picker">
            <el-icon><TrendCharts /></el-icon>
            <span>选股器</span>
          </el-menu-item>
          <el-menu-item index="/backtest">
            <el-icon><Histogram /></el-icon>
            <span>回测分析</span>
          </el-menu-item>
          <el-menu-item index="/trading">
            <el-icon><Monitor /></el-icon>
            <span>实盘监控</span>
          </el-menu-item>

          <el-sub-menu index="more-market">
            <template #title>
              <el-icon><TrendCharts /></el-icon>
              <span>行情分析</span>
            </template>
            <el-menu-item index="/market/stock">股票详情</el-menu-item>
            <el-menu-item index="/market/kline">K线图</el-menu-item>
            <el-menu-item index="/market/realtime">实时盯盘</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="more-strategy">
            <template #title>
              <el-icon><Setting /></el-icon>
              <span>策略中心</span>
            </template>
            <el-menu-item index="/strategy/list">策略列表</el-menu-item>
            <el-menu-item index="/strategy/editor">策略编辑</el-menu-item>
            <el-menu-item index="/backtest">回测分析</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="more-data">
            <template #title>
              <el-icon><DataAnalysis /></el-icon>
              <span>数据中心</span>
            </template>
            <el-menu-item index="/data/overview">数据管理</el-menu-item>
            <el-menu-item index="/data/import">导入导出</el-menu-item>
            <el-menu-item index="/data/stocks">股票浏览器</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="more-trade">
            <template #title>
              <el-icon><Wallet /></el-icon>
              <span>交易分析</span>
            </template>
            <el-menu-item disabled>功能待实现</el-menu-item>
          </el-sub-menu>

          <!-- 投资组合/报表/设置：后端未接入，先隐藏入口 -->
        </el-sub-menu>
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
import { DataAnalysis, DataLine, TrendCharts, Monitor, Setting, Wallet, ArrowDown, Menu, Histogram } from '@element-plus/icons-vue'

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
