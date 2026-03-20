<template>
  <el-config-provider :locale="zhCn">
    <el-container class="app-container">
      <el-aside width="200px" class="aside">
        <div class="logo">📈 量化交易</div>
        <el-menu
          :default-active="route.path"
          router
          background-color="#1d1e1f"
          text-color="#aaa"
          active-text-color="#409eff"
        >
          <el-menu-item index="/dashboard">
            <el-icon><DataLine /></el-icon>
            <span>行情看板</span>
          </el-menu-item>
          <el-menu-item index="/strategy">
            <el-icon><Setting /></el-icon>
            <span>策略管理</span>
          </el-menu-item>
          <el-menu-item index="/backtest">
            <el-icon><TrendCharts /></el-icon>
            <span>回测分析</span>
          </el-menu-item>
          <el-menu-item index="/trading">
            <el-icon><Monitor /></el-icon>
            <span>实盘监控</span>
          </el-menu-item>
          <el-menu-item index="/account">
            <el-icon><Wallet /></el-icon>
            <span>账户资产</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="header">
          <span class="title">{{ route.meta?.title || '量化交易系统' }}</span>
          <div class="user-area">
            <el-badge :value="3" class="notification">
              <el-icon :size="20"><Bell /></el-icon>
            </el-badge>
            <el-dropdown>
              <span class="user-name">
                {{ userStore.username || 'admin' }}
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
  </el-config-provider>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import { useUserStore } from '@/store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const handleLogout = () => {
  userStore.logout()
  router.push('/login')
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}
html, body, #app {
  height: 100%;
}
.app-container {
  height: 100%;
  background: #0d0d0e;
}
.aside {
  background: #1d1e1f;
  color: #fff;
}
.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  font-size: 16px;
  font-weight: bold;
  color: #409eff;
  border-bottom: 1px solid #333;
}
.header {
  background: #161718;
  border-bottom: 1px solid #2a2b2c;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
.title {
  font-size: 18px;
  color: #e4e7ed;
}
.user-area {
  display: flex;
  align-items: center;
  gap: 16px;
}
.notification {
  cursor: pointer;
}
.user-name {
  color: #aaa;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
}
.main {
  background: #0d0d0e;
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
