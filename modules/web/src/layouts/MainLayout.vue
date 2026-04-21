<template>
  <div class="main-layout">
    <header class="topbar glass">
      <div class="brand">
        <strong>QUANT TERMINAL</strong>
        <span>深色专业交易终端</span>
      </div>
      <div class="toolbar">
        <StatusChip status="running" label="系统运行中" />
        <UiButton variant="ghost" @click="router.push('/ai-chat')">AI聊天</UiButton>
        <UiButton variant="danger" @click="handleLogout">退出</UiButton>
      </div>
    </header>
    <nav class="tabs glass">
      <div v-for="group in navGroups" :key="group.title" class="nav-group">
        <span class="group-title">{{ group.title }}</span>
        <button
          v-for="item in group.items"
          :key="item.path"
          class="tab-item"
          :class="{ active: route.path === item.path }"
          @click="router.push(item.path)"
        >
          {{ item.label }}
        </button>
      </div>
    </nav>
    <main class="page-main">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue"
import { useRoute, useRouter } from "vue-router"
import { useUserStore } from "@/stores/user"
import StatusChip from "@/components/ui/StatusChip.vue"
import UiButton from "@/components/ui/Button.vue"

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const navGroups = computed(() => [
  {
    title: "核心",
    items: [
      { path: "/dashboard", label: "总览" },
      { path: "/account", label: "账户" },
      { path: "/trading", label: "交易执行" },
      { path: "/backtest", label: "回测分析" }
    ]
  },
  {
    title: "数据",
    items: [
      { path: "/data/overview", label: "数据管理" },
      { path: "/data/stocks", label: "股票浏览器" }
    ]
  },
  {
    title: "行情",
    items: [
      { path: "/market/stock", label: "股票详情" },
      { path: "/market/kline", label: "K线图" },
      { path: "/market/stock-picker", label: "选股工作台" },
      { path: "/market/realtime", label: "实时盯盘" },
      { path: "/market/analysis-dashboard", label: "AI分析" }
    ]
  },
  {
    title: "扩展",
    items: [
      { path: "/ai-chat", label: "AI聊天" },
      { path: "/scheduler", label: "定时设置" },
      { path: "/settings", label: "设置" }
    ]
  }
])

function handleLogout() {
  userStore.logout()
  router.push("/login")
}
</script>

<style scoped>
.main-layout {
  height: 100vh;
  padding: var(--sp-4);
  display: grid;
  grid-template-rows: var(--topbar-h) var(--tab-h) 1fr;
  gap: var(--sp-3);
}

.topbar {
  border-radius: var(--r-md);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 var(--sp-5);
}

.brand {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.brand strong {
  font-size: var(--fz-md);
  letter-spacing: 1px;
}

.brand span {
  font-size: var(--fz-xs);
  color: var(--text-secondary);
}

.toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.tabs {
  border-radius: var(--r-md);
  padding: 8px;
  display: flex;
  gap: 12px;
  overflow: auto;
  align-items: center;
}

.nav-group {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: max-content;
}

.group-title {
  font-size: var(--fz-xs);
  color: var(--text-muted);
  margin-right: 4px;
}

.tab-item {
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--text-secondary);
  padding: 0 12px;
  min-width: max-content;
  cursor: pointer;
  transition: all var(--dur-mid);
}

.tab-item:hover {
  color: var(--text-primary);
  background: rgba(88, 185, 255, 0.1);
}

.tab-item.active {
  color: var(--text-primary);
  border-color: rgba(88, 185, 255, 0.5);
  background: rgba(88, 185, 255, 0.16);
}

.page-main {
  min-height: 0;
  overflow: auto;
}
</style>
