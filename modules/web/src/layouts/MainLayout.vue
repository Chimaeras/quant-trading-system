<template>
  <el-container class="main-layout">
    <el-aside width="240px" class="aside">
      <div class="logo">📈 量化交易</div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#1e3a5f"
        text-color="#93c5fd"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>行情看板</span>
        </el-menu-item>
        <el-menu-item index="/account">
          <el-icon><Wallet /></el-icon>
          <span>账户资产</span>
        </el-menu-item>
        <el-menu-item index="/data/overview">
          <el-icon><FolderOpened /></el-icon>
          <span>数据管理</span>
        </el-menu-item>
        <el-menu-item index="/data/stocks">
          <el-icon><List /></el-icon>
          <span>股票浏览器</span>
        </el-menu-item>
        <el-menu-item index="/market/stock">
          <el-icon><TrendCharts /></el-icon>
          <span>股票详情</span>
        </el-menu-item>
        <el-menu-item index="/market/analysis-dashboard">
          <el-icon><Histogram /></el-icon>
          <span>股票分析看板</span>
        </el-menu-item>
        <el-menu-item index="/market/kline">
          <el-icon><DataAnalysis /></el-icon>
          <span>K线图</span>
        </el-menu-item>
        <el-menu-item index="/market/realtime">
          <el-icon><Odometer /></el-icon>
          <span>实时盯盘</span>
        </el-menu-item>
        <el-menu-item index="/market/stock-picker">
          <el-icon><Search /></el-icon>
          <span>打分选股</span>
        </el-menu-item>
        <el-menu-item index="/backtest">
          <el-icon><Cpu /></el-icon>
          <span>回测分析</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ $route.meta?.title || '量化交易' }}</span>
        <div class="user-area">
          <!-- removed offline badge -->
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
        <!-- 不使用 transition + out-in：避免返回上一页时进入动画异常导致白屏 -->
        <router-view v-slot="{ Component }">
          <component :is="Component" :key="routeKey" />
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import {
  ArrowDown,
  Cpu,
  DataAnalysis,
  DataLine,
  FolderOpened,
  Histogram,
  List,
  Odometer,
  Search,
  TrendCharts,
  Wallet
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeMenu = computed(() => route.path)

/** 路由名 + 路径 + 查询：切换页面时强制重建，避免复用导致二次进入不渲染 */
const routeKey = computed(() => {
  const name = route.name != null ? String(route.name) : ''
  return `${name}|${route.fullPath}`
})

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
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
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
</style>
