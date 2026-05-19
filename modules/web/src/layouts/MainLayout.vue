<template>
  <div class="main-layout">
    <header class="topbar glass">
      <div class="brand">
        <strong>QUANT TERMINAL</strong>
        <span>量化选股系统</span>
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

const route = useRoute()
const router = useRouter()

const navGroups = computed(() => [
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
      { path: "/market/stock-picker", label: "选股打分" },
      { path: "/backtest/scoring", label: "打分回测" }
    ]
  }
])
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