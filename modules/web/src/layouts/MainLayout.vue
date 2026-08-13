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
        <!-- V2-MP-028：健康状态只来自 readiness API（db/日历/行情时效/数据门禁），不来自本机时间。 -->
        <el-tooltip :content="healthTip" placement="bottom-end" :disabled="!healthTip">
          <span class="health-chip" :class="healthClass">
            <span class="status-dot" :class="healthClass" />
            <span class="status-label">{{ healthLabel }}</span>
          </span>
        </el-tooltip>
        <el-tag v-if="sessionLabel" size="small" effect="plain" class="session-pill">
          {{ sessionLabel }}
        </el-tag>
        <el-button size="small" :loading="readinessLoading" @click="loadReadiness">刷新</el-button>
      </div>
    </header>
    <nav class="tabs glass">
      <div v-for="group in navGroups" :key="group.title" class="nav-group">
        <span class="group-title">{{ group.title }}</span>
        <template v-for="item in group.items" :key="item.key || item.path">
          <button
            v-if="!item.visible || item.visible()"
            class="tab-item"
            :class="{ active: item.path && route.path === item.path }"
            @click="handleNav(item)"
          >
            {{ item.label }}
          </button>
        </template>
      </div>
    </nav>
    <main class="page-main">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { useRoute, useRouter } from "vue-router"
import { getSystemReadiness, type SystemReadiness } from "@/api/system"
import { errorText } from "@/api/request"
import { authApi } from "@/api/auth"

const route = useRoute()
const router = useRouter()

// 登录态：响应用户主动登出后的导航刷新
const loggedIn = ref(authApi.isLoggedIn())

interface NavItem {
  key?: string
  path?: string
  label: string
  action?: 'logout'
  visible?: () => boolean
}

function handleNav(item: NavItem) {
  if (item.action === 'logout') {
    authApi.logout()
    loggedIn.value = false
    router.push('/login')
    return
  }
  if (item.path) router.push(item.path)
}

// ── V2-MP-028：系统就绪度（后端聚合，绝不以本机时间充当健康）──────────────────────
const readiness = ref<SystemReadiness | null>(null)
const readinessError = ref('')
const readinessLoading = ref(false)

async function loadReadiness() {
  readinessLoading.value = true
  readinessError.value = ''
  try {
    readiness.value = await getSystemReadiness()
  } catch (e: any) {
    readiness.value = null
    readinessError.value = errorText(e)
  } finally {
    readinessLoading.value = false
  }
}

const healthClass = computed(() => {
  if (readinessError.value) return 'block'
  if (!readiness.value) return 'loading'
  const s = readiness.value.status
  if (s === 'PASS') return 'ok'
  if (s === 'DEGRADED') return 'warn'
  if (s === 'BLOCKED') return 'block'
  return 'loading'
})

const healthLabel = computed(() => {
  if (readinessError.value) return '服务不可达'
  if (!readiness.value) return '检测中…'
  const s = readiness.value.status
  if (s === 'PASS') return '系统正常'
  if (s === 'DEGRADED') return '系统降级'
  if (s === 'BLOCKED') return '系统故障'
  return '检测中…'
})

const healthTip = computed(() => {
  if (readinessError.value) return `就绪度获取失败：${readinessError.value}（点击刷新重试）`
  const r = readiness.value
  if (!r) return ''
  const lines = [
    `数据库：${r.db_health?.status ?? '?'} ${r.db_health?.detail ?? ''}`,
    `日历：${r.calendar?.status ?? '?'} ${r.calendar?.detail ?? ''}`,
    `行情时效：${r.data_freshness?.status ?? '?'} ${r.data_freshness?.detail ?? ''}`,
    `数据门禁：${r.data_gate?.status ?? '?'} ${r.data_gate?.detail ?? ''}`
  ]
  return lines.filter((l) => l.trim()).join('\n')
})

/** 市场时段为告知性信息（交易日以日历表判定），不参与系统健康判定。 */
const sessionLabel = computed(() => readiness.value?.market_session?.label ?? '')

const navGroups = computed(() => {
  const groups: { title: string; items: NavItem[] }[] = [
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
      { path: "/backtest/portfolio", label: "组合回测" },
      { path: "/backtest/walk-forward", label: "分段稳定性" },
      { path: "/factor/research", label: "因子研究" },
      { path: "/research/probability", label: "概率研究" },
      { path: "/research/rolling-walk-forward", label: "滚动 Walk-Forward" },
      { path: "/research/tasks", label: "研究任务工作台" }
    ]
  },
  {
    title: "账户",
    items: [
      { path: "/account/overview", label: "模拟账户" },
      { path: "/reconcile/position", label: "持仓对账" },
      { key: "auth-logout", label: "退出登录", action: "logout", visible: () => loggedIn.value },
      { key: "auth-login", path: "/login", label: "登录", visible: () => !loggedIn.value }
    ]
  }
  ]
  return groups
})

onMounted(loadReadiness)
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

.health-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px;
  border-radius: var(--r-pill);
  border: 1px solid transparent;
  cursor: default;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--r-pill);
  background: var(--success);
  box-shadow: 0 0 8px var(--success);
  animation: pulse-dot 2s ease-in-out infinite;
}

/* V2-MP-028：三态语义（正常/降级/故障）分别着色，且文本标签同时区分，不靠单色。 */
.status-dot.ok,
.health-chip.ok { background: var(--success); box-shadow: 0 0 8px var(--success); color: var(--success); border-color: rgba(39, 194, 76, 0.35); }
.status-dot.warn,
.health-chip.warn { background: var(--warning); box-shadow: 0 0 8px var(--warning); color: var(--warning); border-color: rgba(230, 162, 60, 0.45); }
.status-dot.block,
.health-chip.block { background: var(--danger); box-shadow: 0 0 8px var(--danger); color: var(--danger); border-color: rgba(245, 108, 108, 0.5); }
.status-dot.loading,
.health-chip.loading { background: var(--text-dim); box-shadow: none; color: var(--text-dim); border-color: transparent; animation: none; }

.status-label {
  white-space: nowrap;
}

.session-pill {
  margin-left: 2px;
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
