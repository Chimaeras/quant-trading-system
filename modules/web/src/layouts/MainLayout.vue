<template>
  <div class="main-layout">
    <header class="topbar glass">
      <div class="brand">
        <span class="brand-mark">Q</span>
        <div class="brand-text">
          <strong>QUANT TERMINAL</strong>
          <span>量化选股系统</span>
        </div>
      </div>
      <div class="topbar-meta">
        <span class="status-dot" />
        <span class="status-label">{{ statusLabel }}</span>
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

const statusLabel = computed(() => {
  const h = new Date().getHours()
  if (h < 5) return "夜盘"
  if (h < 9) return "盘前"
  if (h < 15) return "交易中"
  if (h < 17) return "盘后"
  return "休市"
})

const navGroups = computed(() => [
  {
    title: "数据",
    items: [
      { path: "/data/overview", label: "数据管理" },
      { path: "/data/quality", label: "数据质量" },
      { path: "/data/stocks", label: "股票浏览器" }
    ]
  },
  {
    title: "日报",
    items: [
      { path: "/report/daily", label: "选股日报" },
      { path: "/compare/strategies", label: "策略对比" },
      { path: "/watchlist", label: "自选股池" }
    ]
  },
  {
    title: "行情",
    items: [
      { path: "/market/stock", label: "股票详情" },
      { path: "/market/kline", label: "K线图" },
      { path: "/market/stock-picker", label: "选股打分" },
      { path: "/market/t1-candidate", label: "T+1 次日候选" },
      { path: "/screener", label: "条件选股" }
    ]
  },
  {
    title: "回测",
    items: [
      { path: "/backtest/scoring", label: "打分回测" },
      { path: "/backtest/strategy", label: "策略回测" },
      { path: "/backtest/walk-forward", label: "分段稳定性" },
      { path: "/factor/research", label: "因子研究" },
      { path: "/research/probability", label: "概率研究" }
    ]
  },
  {
    title: "账户",
    items: [
      { path: "/account/overview", label: "模拟账户" },
      { path: "/reconcile/position", label: "持仓对账" },
      { path: "/login", label: "登录" }
    ]
  }
])
</script>

<style scoped>
.main-layout {
  height: 100vh;
  padding: var(--sp-3);
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
  align-items: center;
  gap: var(--sp-3);
}

.brand-mark {
  width: 36px;
  height: 36px;
  border-radius: var(--r-sm);
  background: var(--accent-grad);
  color: #032237;
  font-weight: 800;
  font-size: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(88, 185, 255, 0.3);
}

.brand-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.brand-text strong {
  font-size: var(--fz-md);
  letter-spacing: 1.5px;
}

.brand-text span {
  font-size: var(--fz-xs);
  color: var(--text-secondary);
}

.topbar-meta {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  color: var(--text-secondary);
  font-size: var(--fz-xs);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--r-pill);
  background: var(--success);
  box-shadow: 0 0 8px var(--success);
  animation: pulse-dot 2s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.tabs {
  border-radius: var(--r-md);
  padding: 6px var(--sp-3);
  display: flex;
  gap: var(--sp-4);
  overflow: auto;
  align-items: center;
}

.nav-group {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: max-content;
}

.group-title {
  font-size: var(--fz-xs);
  color: var(--text-dim);
  margin-right: 6px;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.tab-item {
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--text-secondary);
  padding: 0 var(--sp-3);
  height: 30px;
  min-width: max-content;
  cursor: pointer;
  font-size: var(--fz-sm);
  transition: all var(--dur-fast) var(--ease);
}

.tab-item:hover {
  color: var(--text-primary);
  background: var(--accent-soft);
}

.tab-item.active {
  color: var(--text-primary);
  border-color: var(--glass-border-strong);
  background: var(--accent-soft);
  font-weight: 600;
}

.page-main {
  min-height: 0;
  overflow: auto;
  border-radius: var(--r-md);
}
</style>
