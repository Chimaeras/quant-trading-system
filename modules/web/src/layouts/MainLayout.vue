<template>
  <div class="shell">
    <div class="ambient-grid" />
    <div class="panel">
      <AppTopBar
        :nav-items="primaryGroups"
        :active-key="activeGroupKey"
        @select="onSelectGroup"
      >
        <!-- V2-MP-028：健康状态只来自 readiness API（db/日历/行情时效/数据门禁），不来自本机时间。 -->
        <el-tooltip :content="healthTip" placement="bottom-end" :disabled="!healthTip">
          <span class="chip" :class="chipClass">
            <span class="chip-dot" :class="{ live: healthClass === 'ok' }" />
            <span class="chip-label">{{ healthLabel }}</span>
          </span>
        </el-tooltip>
        <el-tag v-if="sessionLabel" size="small" effect="plain" class="session-pill">
          {{ sessionLabel }}
        </el-tag>
        <el-button size="small" :loading="readinessLoading" @click="loadReadiness">刷新</el-button>
      </AppTopBar>

      <SubNav :items="secondaryItems" @select="handleNav" />

      <main class="viewStage" :class="{ 'stage-watch': route.name === 'WatchMonitor' }">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { useRoute, useRouter } from "vue-router"
import AppTopBar from "@/components/ui/AppTopBar.vue"
import SubNav, { type SubNavItem } from "@/components/ui/SubNav.vue"
import { getSystemReadiness, type SystemReadiness } from "@/api/system"
import { errorText } from "@/api/request"
import { authApi } from "@/api/auth"
import { useDataStore } from "@/stores/data"

const route = useRoute()
const router = useRouter()
const dataStore = useDataStore()

// 登录态：响应用户主动登出后的导航刷新
const loggedIn = ref(authApi.isLoggedIn())

interface NavItem extends SubNavItem {}

interface GroupDef {
  key: string
  title: string
  items: NavItem[]
}

function handleNav(item: NavItem) {
  if (item.action === 'logout') {
    authApi.logout()
    // 登出不重建应用实例，Pinia 里的上一用户数据必须显式清掉。
    dataStore.reset()
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

const chipClass = computed(() => {
  const h = healthClass.value
  if (h === 'ok') return 'chip-ok'
  if (h === 'warn') return 'chip-warn'
  if (h === 'block') return 'chip-block'
  return 'chip-muted'
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

// ── 导航数据（保留原路由 path/name；仅重构展示结构）───────────────────────────────
const navGroups = computed<GroupDef[]>(() => [
  {
    key: "data",
    title: "数据",
    items: [
      { key: "data-overview", path: "/data/overview", label: "数据管理" },
      { key: "data-quality", path: "/data/quality", label: "数据质量" },
      { key: "data-stocks", path: "/data/stocks", label: "股票浏览器" }
    ]
  },
  {
    key: "daily",
    title: "日报",
    items: [
      { key: "report-daily", path: "/report/daily", label: "选股日报" },
      { key: "compare-strategies", path: "/compare/strategies", label: "策略对比" },
      { key: "watchlist", path: "/watchlist", label: "自选股池" }
    ]
  },
  {
    key: "market",
    title: "行情",
    items: [
      { key: "market-stock", path: "/market/stock", label: "股票详情" },
      { key: "market-kline", path: "/market/kline", label: "K线图" },
      { key: "market-picker", path: "/market/stock-picker", label: "选股打分" },
      { key: "market-t1", path: "/market/t1-candidate", label: "T+1 次日候选" },
      { key: "screener", path: "/screener", label: "条件选股" }
    ]
  },
  {
    key: "backtest",
    title: "回测",
    items: [
      { key: "bt-scoring", path: "/backtest/scoring", label: "打分回测" },
      { key: "bt-strategy", path: "/backtest/strategy", label: "策略回测" },
      { key: "bt-portfolio", path: "/backtest/portfolio", label: "组合回测" },
      { key: "bt-wf", path: "/backtest/walk-forward", label: "分段稳定性" },
      { key: "factor", path: "/factor/research", label: "因子研究" },
      { key: "research-prob", path: "/research/probability", label: "概率研究" },
      { key: "research-rwf", path: "/research/rolling-walk-forward", label: "滚动 Walk-Forward" },
      { key: "research-tasks", path: "/research/tasks", label: "研究任务工作台" }
    ]
  },
  {
    key: "account",
    title: "账户",
    items: [
      { key: "account-overview", path: "/account/overview", label: "模拟账户" },
      { key: "reconcile", path: "/reconcile/position", label: "持仓对账" },
      { key: "watch-monitor", path: "/watch/monitor", label: "盘中盯盘监控" },
      { key: "auth-logout", label: "退出登录", action: "logout", visible: () => loggedIn.value },
      { key: "auth-login", path: "/login", label: "登录", visible: () => !loggedIn.value }
    ]
  }
])

const primaryGroups = computed(() => navGroups.value.map((g) => ({ key: g.key, label: g.title })))

/** 依据当前路由反查其所属一级分组（fallback 到「数据」组）。 */
function groupKeyForPath(path: string): string {
  for (const g of navGroups.value) {
    if (g.items.some((i) => i.path === path)) return g.key
  }
  return "data"
}

const activeGroupKey = computed(() => groupKeyForPath(route.path))

const activeGroup = computed(() => navGroups.value.find((g) => g.key === activeGroupKey.value))

/** 二级子导航：仅当前一级分组下的页面按钮（含可见性 visible()）。 */
const secondaryItems = computed<NavItem[]>(() =>
  (activeGroup.value?.items ?? []).map((i) => ({ ...i, active: !!i.path && route.path === i.path }))
)

/** 一级切换：若当前路由不在该组，跳到该组第一个页面；已在该组则仅保持二级高亮。 */
function onSelectGroup(key: string) {
  if (key === activeGroupKey.value) return
  const g = navGroups.value.find((x) => x.key === key)
  const first = g?.items.find((i) => i.path)
  if (first?.path) router.push(first.path)
}

onMounted(loadReadiness)
</script>

<style scoped>
.session-pill {
  margin-left: 2px;
}

.chip-label {
  white-space: nowrap;
}
</style>
