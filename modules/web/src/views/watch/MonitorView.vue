<template>
  <div class="watch-monitor">
    <!-- ① 状态条 -->
    <el-card shadow="hover" class="status-bar">
      <div class="status-row">
        <div class="status-item">
          <span class="label">交易日</span>
          <el-select v-model="selectedDate" style="width: 140px" size="small" @change="onDateChange">
            <el-option v-for="d in days" :key="d" :label="d" :value="d" />
          </el-select>
        </div>
        <div class="status-item">
          <span class="label">模式</span>
          <el-tag :type="isLive ? 'danger' : 'info'" size="small">{{ isLive ? '模拟盘 live' : 'dry-run' }}</el-tag>
        </div>
        <div class="status-item">
          <span class="label">轮次</span>
          <span class="value">{{ lastCycle?.cycle ?? '—' }}</span>
        </div>
        <div class="status-item">
          <span class="label">最近状态</span>
          <el-tag :type="cycleStatusType" size="small">{{ (lastCycle?.detail as any)?.status ?? '—' }}</el-tag>
        </div>
        <div class="status-item">
          <span class="label">今日笔数</span>
          <span class="value">{{ state?.state?.day_trade_count ?? 0 }} / {{ maxTrades }}</span>
        </div>
        <div class="status-item">
          <span class="label">最后更新</span>
          <span class="value">{{ lastCycle?.ts ?? '—' }}</span>
        </div>
        <div class="status-item">
          <span class="label">L1覆盖</span>
          <span class="value">{{ l1Coverage }}</span>
        </div>
        <div class="status-item">
          <span class="label">L2活跃池</span>
          <span class="value">{{ marketSnapshot?.active_pool?.length ?? 0 }}</span>
        </div>
        <div class="status-item">
          <span class="label">策略扫描</span>
          <span class="value">{{ strategyScanCount }}</span>
        </div>
        <div class="status-item">
          <span class="label">行情覆盖</span>
          <span class="value">{{ poolQuoteCoverage }}</span>
        </div>
        <div class="status-item grow">
          <el-alert v-if="!serviceUp" type="warning" :closable="false" show-icon
            title="watch-serve 未启动：请在 compute-python 下运行 python -m paper.cli watch-serve" />
          <el-alert v-else-if="halted" type="error" :closable="false" show-icon
            title="STOP_RUN 已触发：程序已自动停止真实提交，仅 dry-run" />
        </div>
        <div class="status-item">
          <el-switch v-model="autoRefresh" active-text="自动刷新" size="small" />
        </div>
      </div>
    </el-card>

    <!-- ② 策略推荐：置顶全宽，避免被右侧账户区挤没 -->
    <el-card shadow="never" class="panel recommend-panel">
      <template #header>
        <div class="panel-header">
          <div class="panel-title">
            <span>策略推荐（{{ recommendRows.length }}）</span>
            <span class="panel-hint">买卖信号始终展示；仓位/板块拦截只标状态，不隐藏</span>
          </div>
          <el-tag v-if="adviceError" type="danger" size="small">推荐数据加载失败，将自动重试</el-tag>
          <el-tag v-else-if="!recommendRows.length && serviceUp" type="info" size="small">暂无买卖信号</el-tag>
        </div>
      </template>
      <div ref="recommendHostRef" class="table-host">
        <el-table
          class="watch-table recommend-table"
          :height="hostHeights.recommend"
          :data="recommendRows"
          size="small"
          empty-text="暂无买卖信号（有信号时会显示在这里）"
          @row-click="(row: any) => openDetail(row)"
        >
          <el-table-column prop="ts" label="时间" min-width="96">
            <template #default="{ row }">{{ row.ts.slice(11) }}</template>
          </el-table-column>
          <el-table-column prop="code" label="代码" min-width="78">
            <template #default="{ row }">
              <el-link type="primary" :underline="false">{{ row.code }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="100" show-overflow-tooltip />
          <el-table-column label="方向" min-width="56">
            <template #default="{ row }">
              <span :class="row.action === 'buy' ? 'up' : 'down'">{{ row.action === 'buy' ? '买' : '卖' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="strategy" label="策略" min-width="88" show-overflow-tooltip />
          <el-table-column label="建议量" min-width="72" align="right">
            <template #default="{ row }">{{ row.qty ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="参考价" min-width="78" align="right">
            <template #default="{ row }">{{ fmtPrice(row.price) }}</template>
          </el-table-column>
          <el-table-column label="参考状态" min-width="150">
            <template #default="{ row }">
              <el-tag :type="row.statusType" size="small">{{ row.statusLabel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="触发" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reasons }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <div class="main-grid">
      <!-- ③ 观察池 -->
      <el-card shadow="never" class="panel universe-panel">
        <template #header>
          <div class="panel-header pool-header">
            <div class="panel-title">
              <span>观察池（{{ watchPool?.total ?? 0 }}）</span>
              <span class="panel-hint">横向滚动查看完整字段</span>
            </div>
            <div class="pool-add">
              <el-input v-model="addCode" maxlength="6" placeholder="证券代码" size="small"
                @keyup.enter="addSecurity" />
              <el-button type="primary" size="small" :loading="adding" @click="addSecurity">添加</el-button>
            </div>
          </div>
        </template>
        <div ref="poolHostRef" class="table-host">
          <el-table class="watch-table pool-table" :height="hostHeights.pool" :data="universeRows" size="small" @row-click="openDetail" highlight-current-row>
          <el-table-column prop="code" label="代码" min-width="84">
            <template #default="{ row }">
              <el-link type="primary" :underline="false">{{ row.code }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="pool-name" :title="row.name || ''">{{ row.name || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最新价" min-width="90" align="right">
            <template #default="{ row }">{{ row.last?.toFixed(2) ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="涨跌幅" min-width="90" align="right">
            <template #default="{ row }">
              <span v-if="row.pct !== undefined" :class="row.pct >= 0 ? 'up' : 'down'">
                {{ row.pct > 0 ? '+' : '' }}{{ row.pct.toFixed(2) }}%
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <!-- D-QUOTE-01：行情数据时间与来源。缓存兜底的标的必须与实时行情区分显示，
               否则使用者无法判断页面上的价格是「现在」还是「上一轮」。 -->
          <el-table-column label="数据时间" min-width="96" align="center">
            <template #default="{ row }">
              <span v-if="row.marketTime" class="quote-time">{{ hhmmss(row.marketTime) }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="来源" min-width="88" align="center">
            <template #default="{ row }">
              <el-tooltip v-if="row.sourceKind === 'cache'" content="源站本轮未返回该标的，价格来自上一轮快照兜底（不是本轮实时行情）" placement="top">
                <el-tag type="warning" size="small">缓存兜底</el-tag>
              </el-tooltip>
              <el-tag v-else-if="row.sourceKind === 'realtime'" type="success" size="small">实时</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="100">
            <template #default="{ row }">
              <el-tooltip v-if="row.disabled" :content="row.disabled" placement="top">
                <el-tag type="info" size="small">禁用</el-tag>
              </el-tooltip>
              <el-tag v-else-if="row.active" type="success" size="small">L2分析</el-tag>
              <el-tag v-else type="info" size="small">L1扫描</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源" min-width="78">
            <template #default="{ row }">
              <el-tag :type="row.manual ? 'warning' : 'info'" size="small">
                {{ row.manual ? '手动' : '热门' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="64" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.manual" link type="danger" size="small" @click.stop="removeSecurity(row.code)">移除</el-button>
            </template>
          </el-table-column>
          </el-table>
        </div>
      </el-card>

      <!-- ④ 分析过程流 -->
      <el-card shadow="never" class="panel events-panel">
        <template #header>
          <div class="panel-header">
            <div class="panel-title">
              <span>分析过程流（显示 {{ filteredEvents.length }} / 缓存 {{ events.length }}）</span>
              <span class="panel-hint">横向滚动 / 展开箭头查看完整字段</span>
            </div>
            <div class="filters">
              <el-select v-model="typeFilter" clearable placeholder="事件类型" size="small" style="width: 130px">
                <el-option v-for="t in typeOptions" :key="t" :label="t" :value="t" />
              </el-select>
              <el-input v-model="codeFilter" placeholder="代码" clearable size="small" style="width: 100px" />
              <el-switch v-model="hideNoise" active-text="隐藏行情噪音" size="small" />
              <el-switch v-model="signalsOnly" active-text="只看买卖信号" size="small" />
              <el-switch v-model="tradesOnly" active-text="只看成交" size="small" />
            </div>
          </div>
        </template>
        <div ref="eventsHostRef" class="table-host">
          <el-table class="watch-table events-table" :height="hostHeights.events" :data="filteredEvents" size="small" border :row-key="(r: any) => r.seq">
          <el-table-column prop="ts" label="时间" min-width="106">
            <template #default="{ row }">{{ row.ts.slice(11) }}</template>
          </el-table-column>
          <el-table-column prop="seq" label="#" min-width="64" sortable />
          <el-table-column label="类型" min-width="100">
            <template #default="{ row }">
              <el-tag :type="typeTagType(row.type, row.detail)" size="small">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="code" label="代码" min-width="84" />
          <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
          <el-table-column label="内容" min-width="360" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="event-summary" :class="eventSummaryClass(row)">{{ eventSummary(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="event-detail">
                <template v-if="row.type === 'RISK_GATE'">
                  <div class="gates">
                    <el-tag v-for="(g, i) in ((row.detail as any).gates || [])" :key="i" size="small"
                      :type="g.result === 'PASS' ? 'success' : 'danger'">
                      {{ g.gate }}:{{ g.result }}
                    </el-tag>
                  </div>
                </template>
                <pre>{{ formatEventDetail(row) }}</pre>
              </div>
            </template>
          </el-table-column>
          </el-table>
        </div>
      </el-card>

      <div class="right-column">
        <!-- ⑤ 成交记录 -->
        <el-card shadow="never" class="panel submits-panel">
          <template #header>
            <div class="panel-header">
              <div class="panel-title">
                <span>成交记录（{{ tradeActionRows.length }}）</span>
                <span class="panel-hint">仅最终成交；不含信号、风控拦截与 dry-run</span>
              </div>
            </div>
          </template>
          <div ref="submitsHostRef" class="table-host">
            <el-table class="watch-table submits-table" :height="hostHeights.submits" :data="tradeActionRows" size="small">
            <el-table-column prop="ts" label="时间" min-width="100">
              <template #default="{ row }">{{ row.ts.slice(11) }}</template>
            </el-table-column>
            <el-table-column prop="code" label="代码" min-width="84" />
            <el-table-column prop="name" label="名称" min-width="112" show-overflow-tooltip />
            <el-table-column label="动作" min-width="68">
              <template #default="{ row }">
                <el-tag :type="row.type === 'FILL' ? 'success' : 'primary'" size="small">
                  {{ row.type === 'FILL' ? '成交' : '委托' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="方向" min-width="60">
              <template #default="{ row }">
                <span :class="(row.detail as any).side === 'buy' ? 'up' : 'down'">
                  {{ (row.detail as any).side === 'buy' ? '买' : '卖' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" min-width="78" align="right" />
            <el-table-column label="委托价" min-width="88" align="right">
              <template #default="{ row }">{{ fmtPrice(row.orderPrice) }}</template>
            </el-table-column>
            <el-table-column label="成交价" min-width="88" align="right">
              <template #default="{ row }">{{ fmtPrice(row.fillPrice) }}</template>
            </el-table-column>
            <el-table-column label="结果" min-width="120">
              <template #default="{ row }">
                <el-tag :type="submitTagType((row.detail as any).status)" size="small">
                  {{ (row.detail as any).status }}
                </el-tag>
                <div v-if="(row.detail as any).error_code" class="error-code">{{ (row.detail as any).error_code }}</div>
              </template>
            </el-table-column>
            </el-table>
          </div>
        </el-card>

        <!-- ⑥ 统一盯盘账户投影 -->
        <WatchAccountPanel :trade-date="selectedDate" />
      </div>
    </div>

    <!-- ⑥ 标的明细 -->
    <el-drawer v-model="detailVisible" size="62%" :title="`${detailCode} ${detailName} 盘中分析`">
      <div ref="chartRef" class="detail-chart" />
      <el-table :data="codeSignals(detailCode)" size="small" border class="signal-table">
        <el-table-column prop="ts" label="时间" width="100">
          <template #default="{ row }">{{ row.ts.slice(11) }}</template>
        </el-table-column>
        <el-table-column label="信号" width="90">
          <template #default="{ row }">
            <el-tag :type="(row.detail as any).action === 'buy' ? 'danger' : 'success'" size="small">
              {{ (row.detail as any).action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指标值" min-width="240">
          <template #default="{ row }">{{ signalDetailText(row) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import WatchAccountPanel from '@/components/WatchAccountPanel.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, nextTick } from 'vue'
import {
  addWatchPoolMember,
  fetchDays,
  fetchEvents,
  fetchMarket,
  fetchState,
  fetchTicks,
  fetchWatchPool,
  removeWatchPoolMember,
  type WatchEvent,
  type WatchMarketSnapshot,
  type WatchPoolResponse,
  type WatchTickSummary
} from '@/api/watch'
import { inferExchangeType, resolveSecurityName } from '@/utils/security'
import { ElMessage, ElMessageBox } from 'element-plus'

const MAX_EVENTS = 500
/** 表格只渲染最近 N 条，避免 el-table 全量重绘卡死主线程 */
const DISPLAY_EVENTS = 80
const DISPLAY_SUBMITS = 100
const DISPLAY_RECOMMENDS = 80
const MAX_ADVICE = 3000
const REFRESH_MS = 5000
const POOL_REFRESH_TICKS = 6
const MARKET_QUOTE_LIMIT = 200
const NAME_LOOKUP_RETRY_MS = 5 * 60 * 1000
/** 行情类事件默认折叠，减少过程流噪音与 DOM 压力 */
const NOISE_TYPES = new Set(['QUOTE', 'QUOTE_FAIL', 'QUOTE_QUALITY'])
const TRADE_EVENT_QUERY = 'SUBMIT,FILL'
const ADVICE_EVENT_QUERY = 'SIGNAL,SIZING,RISK_GATE'
const ADVICE_TYPES = new Set(['SIGNAL', 'SIZING', 'RISK_GATE'])

const days = shallowRef<string[]>([])
const selectedDate = ref('')
// 接口数据按快照整体替换；使用 shallowRef 避免 Vue 深度代理大型事件 detail。
const events = shallowRef<WatchEvent[]>([])
const tradeEvents = shallowRef<WatchEvent[]>([])
/** 独立缓存买卖信号链路，避免被行情噪音事件挤出 MAX_EVENTS 窗口 */
const adviceEvents = shallowRef<WatchEvent[]>([])
const adviceError = ref(false)
const state = shallowRef<Awaited<ReturnType<typeof fetchState>> | null>(null)
const marketSnapshot = shallowRef<WatchMarketSnapshot | null>(null)
const watchPool = shallowRef<WatchPoolResponse | null>(null)
const ticks = shallowRef<WatchTickSummary[]>([])
const serviceUp = ref(true)
const autoRefresh = ref(true)
const addCode = ref('')
const adding = ref(false)

const typeFilter = ref('')
const codeFilter = ref('')
const tradesOnly = ref(false)
const signalsOnly = ref(false)
const hideNoise = ref(true)
const typeOptions = ['CYCLE', 'READINESS', 'MARKET_DATA', 'ACTIVE_POOL', 'QUOTE', 'QUOTE_FAIL', 'QUOTE_QUALITY', 'BAR_CLOSED', 'SIGNAL', 'SIZING', 'RISK_GATE', 'ORDER', 'SUBMIT', 'FILL', 'CANCEL', 'ACCOUNT', 'ERROR']
let refreshTick = 0

const detailVisible = ref(false)
const detailCode = ref('')
const detailName = ref('')
const securityNames = shallowRef<Record<string, string>>({})
const nameLookupAt = new Map<string, number>()
const chartRef = ref<HTMLElement | null>(null)
const recommendHostRef = ref<HTMLElement | null>(null)
const poolHostRef = ref<HTMLElement | null>(null)
const eventsHostRef = ref<HTMLElement | null>(null)
const submitsHostRef = ref<HTMLElement | null>(null)
const hostHeights = reactive({
  recommend: 200,
  pool: 320,
  events: 320,
  submits: 180,
})
const hostObservers: ResizeObserver[] = []
type ChartRuntime = typeof import('@/utils/echartsTheme')
type ChartInstance = ReturnType<ChartRuntime['echarts']['init']>
let chartRuntimePromise: Promise<ChartRuntime> | null = null
let chart: ChartInstance | null = null
let timer: number | null = null
let cursor = 0
let refreshEpoch = 0
let refreshSequence = 0
let lastAppliedRefresh = 0
let disposed = false

function bindHostHeight(el: HTMLElement | null, key: keyof typeof hostHeights, fallback: number) {
  if (!el || typeof ResizeObserver === 'undefined') {
    hostHeights[key] = fallback
    return
  }
  const apply = () => {
    const next = Math.max(120, Math.floor(el.clientHeight))
    if (next > 0 && next !== hostHeights[key]) hostHeights[key] = next
  }
  apply()
  const observer = new ResizeObserver(apply)
  observer.observe(el)
  hostObservers.push(observer)
}

const lastCycle = computed(() => {
  for (let i = events.value.length - 1; i >= 0; i -= 1) {
    const event = events.value[i]
    if (event.type === 'CYCLE' && (event.detail as any).phase === 'end') return event
  }
  return null
})
const isLive = computed(() => Boolean((lastCycle.value?.detail as any)?.live))
const cycleStatusType = computed(() => {
  const status = (lastCycle.value?.detail as any)?.status as string | undefined
  if (status === 'OK') return 'success'
  if (status === 'DEGRADED' || status === 'SLEEPING' || status === 'NO_UNIVERSE') return 'warning'
  return 'danger'
})
const halted = computed(() =>
  events.value.some((e) => e.type === 'ERROR' && String((e.detail as any).scope) === 'STOP_RUN')
)
const maxTrades = 20
const l1Coverage = computed(() => {
  const value = Number(marketSnapshot.value?.batch?.coverage)
  return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '—'
})
const strategyScanCount = computed(() => {
  const total = Number(watchPool.value?.total || 0)
  if (!total) return '—'
  return `${total} 只`
})
const poolQuoteCoverage = computed(() => {
  const items = watchPool.value?.items || []
  if (!items.length) return '—'
  const poolCodes = new Set(items.map((item) => String(item.code)))
  const covered = (marketSnapshot.value?.quotes || []).filter((quote) => {
    if (!poolCodes.has(String(quote.code))) return false
    const quality = String(quote.quality || quote.validity || '').toUpperCase()
    return !['INVALID', 'STALE', 'MISSING'].includes(quality)
  }).length
  return `${covered}/${items.length}`
})
const poolSecurityNames = computed<Record<string, string>>(() =>
  Object.fromEntries((watchPool.value?.items || []).map((item) => [item.code, item.name]))
)

/**
 * D-QUOTE-01：判断一条报价是否「本轮源站数据」。
 * 依据两条独立证据：(1) source 带 "+cache" 后缀（_apply_last_good_cache 的标记）；
 * (2) market_time 明显早于本轮批次 received_at（同一轮里拿到旧时间戳）。
 * 两条都不满足才认为是实时；证据缺失时不冒充实时（返回 null → 显示「—」）。
 */
function quoteSourceKind(quote: any): 'realtime' | 'cache' | 'unknown' {
  const source = String(quote?.source || '')
  if (source.includes('+cache')) return 'cache'
  const marketTime = Date.parse(String(quote?.market_time || quote?.last_market_time || ''))
  const batchAt = Date.parse(String(marketSnapshot.value?.batch?.received_at || ''))
  if (Number.isFinite(marketTime) && Number.isFinite(batchAt) && batchAt - marketTime > 90_000) {
    return 'cache'
  }
  if (source) return 'realtime'
  return 'unknown'
}

const universeRows = computed(() => {
  const rows = new Map<string, { code: string; name?: string; last?: number; pct?: number; disabled?: string; active?: boolean; manual?: boolean }>()
  const active = new Set((marketSnapshot.value?.active_pool || []).map((item: any) => String(item.code)))
  for (const item of watchPool.value?.items || []) {
    rows.set(item.code, {
      code: item.code,
      name: item.name,
      active: active.has(item.code),
      manual: Boolean(item.manual)
    })
  }
  for (const quote of marketSnapshot.value?.quotes || []) {
    if (watchPool.value?.available && !rows.has(String(quote.code))) continue
    const existing = rows.get(String(quote.code))
    rows.set(String(quote.code), {
      code: String(quote.code),
      name: String(quote.name || existing?.name || ''),
      last: ['INVALID', 'STALE', 'MISSING'].includes(String(quote.quality))
        ? undefined : Number(quote.last),
      pct: quote.pct_chg == null ? undefined : Number(quote.pct_chg),
      disabled: ['INVALID', 'STALE', 'MISSING'].includes(String(quote.quality))
        ? String(quote.quality_reason || quote.quality) : undefined,
      // D-QUOTE-01 可追溯性：源数据时间与「本轮是否来自源站」
      marketTime: (quote as any).last_market_time || (quote as any).market_time || undefined,
      sourceKind: quoteSourceKind(quote),
      active: active.has(String(quote.code)),
      manual: existing?.manual
    })
  }
  for (const e of events.value) {
    if (watchPool.value?.available && e.code && !rows.has(e.code)) continue
    if (e.type === 'QUOTE' && e.code && !rows.has(e.code)) {
      rows.set(e.code, {
        code: e.code,
        name: eventName(e),
        last: (e.detail as any).last,
        pct: (e.detail as any).pct_chg == null ? undefined : Number((e.detail as any).pct_chg),
        marketTime: (e.detail as any).market_time || undefined,
        sourceKind: quoteSourceKind(e.detail)
      })
    } else if (e.type === 'ERROR' && e.code && (e.detail as any).scope === 'KLINE_DISABLED' && !rows.has(e.code)) {
      rows.set(e.code, { code: e.code, name: eventName(e), disabled: String((e.detail as any).message || 'KLINE_DISABLED') })
    }
  }
  return [...rows.values()]
})

/** 「只看成交」：仅最终真实成交，不含信号/定仓/风控拦截/dry-run 委托。 */
function isFinalFillEvent(event: WatchEvent): boolean {
  if (event.type === 'FILL') return true
  // 少数路径把完全成交记在 SUBMIT.status=FILLED，同样视为成交结果。
  if (event.type === 'SUBMIT') {
    const status = String((event.detail as any).status || '').trim().toUpperCase()
    return status === 'FILLED' || status === 'PARTIAL'
  }
  return false
}

function isBuyOrSellSignal(event: WatchEvent): boolean {
  if (event.type !== 'SIGNAL') return true
  const action = String((event.detail as any).action || '').trim().toLowerCase()
  return action === 'buy' || action === 'sell'
}

function mergeEventsBySeq(existing: WatchEvent[], incoming: WatchEvent[], limit: number): WatchEvent[] {
  const bySeq = new Map<number, WatchEvent>()
  for (const event of existing) bySeq.set(event.seq, event)
  for (const event of incoming) bySeq.set(event.seq, event)
  return [...bySeq.values()].sort((left, right) => left.seq - right.seq).slice(-limit)
}

function riskStatusMeta(result: string, reason: string): { label: string; type: 'success' | 'warning' | 'info' | 'danger' } {
  const normalized = String(result || '').toUpperCase()
  const code = String(reason || '').trim()
  if (normalized === 'PASS') return { label: '风控通过', type: 'success' }
  const labels: Record<string, string> = {
    TOTAL_WEIGHT_EXCEEDED: '仓位已满·仅供参考',
    BOARD_BLOCKED_CHINEXT: '创业板受限·仅供参考',
    BOARD_BLOCKED_STAR: '科创板受限·仅供参考',
    LIMIT_UP_NOT_BUYABLE: '涨停买不进·仅供参考',
    NEAR_LIMIT_UP: '临近涨停·仅供参考',
    FUNDS: '资金不足·仅供参考',
    CASH_RESERVE_FLOOR: '现金留存·仅供参考',
    MAX_TRADES_PER_DAY: '日成交上限·仅供参考',
    MAX_TRADES_PER_CYCLE: '轮次上限·仅供参考',
    COOLDOWN: '冷却中·仅供参考',
    HALT: '停牌/熔断·仅供参考',
  }
  if (code && labels[code]) return { label: labels[code], type: 'warning' }
  if (code) return { label: `${code}·仅供参考`, type: 'warning' }
  return { label: '风控拦截·仅供参考', type: 'warning' }
}

const filteredEvents = computed(() => {
  const result: Array<WatchEvent & { name: string }> = []
  for (let i = events.value.length - 1; i >= 0 && result.length < DISPLAY_EVENTS; i -= 1) {
    const event = events.value[i]
    if (isSupersededKlineEvent(event)) continue
    if (hideNoise.value && NOISE_TYPES.has(event.type)) continue
    if (typeFilter.value && event.type !== typeFilter.value) continue
    if (codeFilter.value && event.code !== codeFilter.value) continue
    if (tradesOnly.value && !isFinalFillEvent(event)) continue
    if (signalsOnly.value && !(event.type === 'SIGNAL' && isBuyOrSellSignal(event))) continue
    result.push({ ...event, name: eventName(event) })
  }
  return result
})

const latestKlineEvents = computed(() => {
  const latest = new Map<string, WatchEvent>()
  for (const event of events.value) {
    if (event.type !== 'ERROR' || !event.code) continue
    const scope = String((event.detail as any).scope || '')
    if (!scope.startsWith('KLINE')) continue
    const previous = latest.get(event.code)
    if (!previous || event.seq > previous.seq) latest.set(event.code, event)
  }
  return latest
})

function isSupersededKlineEvent(event: WatchEvent): boolean {
  if (event.type !== 'ERROR' || !event.code) return false
  const scope = String((event.detail as any).scope || '')
  if (!scope.startsWith('KLINE')) return false
  const latest = latestKlineEvents.value.get(event.code)
  return Boolean(latest && latest.seq !== event.seq)
}

const recommendRows = computed(() => {
  // 优先用独立 advice 缓存；若首次加载失败，回退到主事件流中的买卖信号
  const primary = adviceEvents.value
  const fallback = events.value.filter((event) => ADVICE_TYPES.has(event.type))
  const list = primary.length ? primary : fallback
  const rows: Array<{
    seq: number
    ts: string
    code: string
    name: string
    action: string
    strategy: string
    qty?: number
    price?: number
    statusLabel: string
    statusType: 'success' | 'warning' | 'info' | 'danger'
    reasons: string
  }> = []
  for (let i = list.length - 1; i >= 0 && rows.length < DISPLAY_RECOMMENDS; i -= 1) {
    const event = list[i]
    if (event.type !== 'SIGNAL' || !event.code) continue
    const detail = event.detail as any
    const action = String(detail.action || '').trim().toLowerCase()
    if (action !== 'buy' && action !== 'sell') continue

    let sizing: WatchEvent | null = null
    let risk: WatchEvent | null = null
    for (let j = i + 1; j < list.length && j <= i + 40; j += 1) {
      const next = list[j]
      if (next.code !== event.code || next.cycle !== event.cycle) continue
      if (next.type === 'SIZING' && !sizing) sizing = next
      if (next.type === 'RISK_GATE' && !risk) risk = next
      if (sizing && risk) break
    }

    const sizingDetail = (sizing?.detail || {}) as any
    const riskDetail = (risk?.detail || {}) as any
    const qtyRaw = Number(sizingDetail.final_qty ?? riskDetail.qty)
    const priceRaw = Number(sizingDetail.last ?? riskDetail.limit_price)
    const status = risk
      ? riskStatusMeta(String(riskDetail.result || ''), String(riskDetail.reject_reason || ''))
      : { label: '待风控确认', type: 'info' as const }
    const reasons = Array.isArray(detail.reason_codes)
      ? detail.reason_codes.join(',')
      : String(detail.reason || detail.strategy || '—')

    rows.push({
      seq: event.seq,
      ts: event.ts,
      code: event.code,
      name: eventName(event),
      action,
      strategy: String(detail.strategy || '—'),
      qty: Number.isFinite(qtyRaw) && qtyRaw > 0 ? qtyRaw : undefined,
      price: Number.isFinite(priceRaw) && priceRaw > 0 ? priceRaw : undefined,
      statusLabel: status.label,
      statusType: status.type,
      reasons: reasons || '—',
    })
  }
  return rows
})

const tradeActionRows = computed(() => {
  const orderPrices = new Map<string, number>()
  for (const event of tradeEvents.value) {
    if (event.type !== 'SUBMIT') continue
    const detail = event.detail as any
    const orderId = String(detail.order_id || '')
    const price = Number(detail.price)
    if (orderId && Number.isFinite(price)) orderPrices.set(orderId, price)
  }
  return tradeEvents.value
    // 右侧「交易动作」也只展示真实成交，排除 DRY_RUN / 被拒委托等半途记录。
    .filter((event) => isFinalFillEvent(event))
    .sort((left, right) => right.seq - left.seq)
    .slice(0, DISPLAY_SUBMITS)
    .map((event) => {
      const detail = event.detail as any
      const orderId = String(detail.order_id || detail.client_order_id || '')
      const orderPrice = event.type === 'SUBMIT'
        ? Number(detail.price)
        : orderPrices.get(orderId)
      return {
        ...event,
        name: eventName(event),
        qty: Number(detail.qty ?? detail.filled_qty ?? 0),
        orderPrice: Number.isFinite(orderPrice) ? orderPrice : undefined,
        fillPrice: event.type === 'FILL' && Number.isFinite(Number(detail.price))
          ? Number(detail.price) : undefined,
      }
    })
})

function eventSummary(row: WatchEvent): string {
  const d = row.detail as any
  switch (row.type) {
    case 'QUOTE': {
      const last = Number(d.last)
      const chg = Number(d.pct_chg)
      if (!Number.isFinite(last)) return '—'
      const chgText = Number.isFinite(chg) ? `${chg > 0 ? '+' : ''}${chg.toFixed(2)}%` : '—'
      return `${last.toFixed(2)} ${chgText}`
    }
    case 'QUOTE_FAIL':
      return String(d.message || '行情失败')
    case 'BAR_CLOSED':
      return `O ${d.open} C ${d.close} V ${d.volume}`
    case 'SIGNAL': {
      const ind = d.detail || {}
      const parts = Object.entries(ind).map(([k, v]) => `${k}=${v}`)
      return `${d.strategy} → ${d.action}${parts.length ? '（' + parts.join(', ') + '）' : ''}`
    }
    case 'SIZING':
      return d.side === 'buy'
        ? `可用 ${d.avail_balance} × 权重 ${d.weight} → 目标 ${d.target_qty} / 可负担 ${d.affordable_qty} → 定 ${d.final_qty} 股`
        : `可用 ${d.available_qty} 股 → 整手卖出 ${d.lot_qty} 股`
    case 'RISK_GATE':
      return d.result === 'PASS' ? `${d.intent} 通过全部闸门` : `${d.intent} 被拦：${d.reject_reason}`
    case 'SUBMIT':
      return `${d.side} ${d.qty} 股 @ ${d.price?.toFixed(2)}`
    case 'ORDER':
      return `${d.phase || '状态更新'}：${d.status || ''} ${d.client_order_id || ''}`
    case 'FILL':
      return `${d.side} 成交 ${d.qty} 股 @ ${Number(d.price).toFixed(3)}，费用 ${Number(d.total_fee || 0).toFixed(2)}，${d.status}`
    case 'CANCEL':
      return `${d.ok ? '撤单请求成功' : '撤单结果未知'}：${d.client_order_id || d.broker_order_id || ''} ${d.status || ''}`
    case 'ACCOUNT':
      return `${d.phase}：总资产 ${d.total_assets} 可用 ${d.avail_balance} 持仓 ${d.positions} 委托 ${d.open_orders}`
    case 'READINESS': {
      const warmup = (d.checks || []).find((item: any) => item.name === 'strategy_warmup')
      return `${d.ready ? '已就绪' : '未就绪'}，策略预热 ${warmup?.ready ?? 0}/${warmup?.total ?? 0}`
    }
    case 'MARKET_DATA':
      return `L1 ${d.returned_count}/${d.requested_count}，覆盖率 ${(Number(d.coverage || 0) * 100).toFixed(2)}%，耗时 ${d.elapsed_ms}ms`
    case 'ACTIVE_POOL':
      return `L2 ${d.active_count}，强制 ${d.mandatory_count}，晋级 ${(d.promoted || []).length}，淘汰 ${(d.demoted || []).length}`
    case 'CYCLE':
      return d.phase === 'start' ? `轮次开始（${d.live_requested ? '请求 live' : 'dry-run'}）` : `状态 ${d.status}，bar ${d.bars} 信号 ${d.signals} 提交 ${d.submits} 错误 ${d.errors}`
    case 'ERROR': {
      const scope = String(d.scope || 'ERROR')
      if (scope.startsWith('KLINE') && d.status === 'RECOVERED') {
        return `${scope}: 已恢复（${d.bars ?? 0} 根）`
      }
      const messages = Array.isArray(d.messages) ? d.messages.map(String) : []
      if (messages.length > 2) {
        return `${scope}: ${messages.slice(0, 2).join('; ')} …共 ${messages.length} 条`
      }
      const message = String(d.message || messages.join('; ') || '')
      return message.length > 160 ? `${scope}: ${message.slice(0, 160)}…` : `${scope}: ${message}`
    }
    default:
      return JSON.stringify(d).slice(0, 200)
  }
}
function formatEventDetail(row: WatchEvent): string {
  const detail = row.detail as any
  if (row.type === 'ERROR' && Array.isArray(detail?.messages) && detail.messages.length > 20) {
    return JSON.stringify({
      ...detail,
      messages: [...detail.messages.slice(0, 20), `…另有 ${detail.messages.length - 20} 条已省略`]
    }, null, 2)
  }
  if (row.type === 'READINESS' && Array.isArray(detail?.checks)) {
    return JSON.stringify({
      ready: detail.ready,
      strategy_ready_coverage: detail.strategy_ready_coverage,
      checks: detail.checks.map((item: any) => ({
        name: item.name,
        ok: item.ok,
        ready: item.ready,
        total: item.total,
        coverage: item.coverage
      }))
    }, null, 2)
  }
  const text = JSON.stringify(detail, null, 2)
  return text.length > 4000 ? `${text.slice(0, 4000)}\n…(已截断)` : text
}
function eventName(event: WatchEvent): string {
  return inlineEventName(event) || securityNames.value[event.code] || poolSecurityNames.value[event.code] || ''
}
function inlineEventName(event: WatchEvent): string {
  return event.name?.trim() || String((event.detail as any).stock_name || '').trim()
}
async function ensureSecurityNames(list: WatchEvent[]) {
  const now = Date.now()
  const missing = new Set<string>()
  for (const event of list) {
    const code = event.code
    if (!code || inlineEventName(event) || securityNames.value[code] || poolSecurityNames.value[code]) continue
    if (now - (nameLookupAt.get(code) || 0) >= NAME_LOOKUP_RETRY_MS) missing.add(code)
  }
  if (!missing.size) return
  for (const code of missing) nameLookupAt.set(code, now)
  const resolved = await Promise.all([...missing].map(async (code) => [code, await resolveSecurityName(code)] as const))
  const found = Object.fromEntries(resolved.filter(([, name]) => name))
  if (Object.keys(found).length) securityNames.value = { ...securityNames.value, ...found }
}
function eventSummaryClass(row: WatchEvent): string {
  if (row.type === 'SIGNAL' && (row.detail as any).action === 'buy') return 'up'
  if (row.type === 'SIGNAL' && (row.detail as any).action === 'sell') return 'down'
  if (row.type === 'RISK_GATE' && (row.detail as any).result === 'REJECT') return 'down'
  if (row.type === 'QUOTE' && Number((row.detail as any).pct_chg) < 0) return 'down'
  if (row.type === 'QUOTE' && Number((row.detail as any).pct_chg) > 0) return 'up'
  return ''
}
type WatchTagType = 'success' | 'warning' | 'info' | 'primary' | 'danger'
function typeTagType(type: string, detail: Record<string, unknown> = {}): WatchTagType {
  if (
    type === 'ERROR'
    && String(detail.scope || '').startsWith('KLINE')
    && detail.status === 'RECOVERED'
  ) return 'success'
  const map: Record<string, WatchTagType> = {
    SIGNAL: 'warning', ORDER: 'primary', SUBMIT: 'danger', FILL: 'success', CANCEL: 'warning', RISK_GATE: 'primary', SIZING: 'primary',
    MARKET_DATA: 'primary', ACTIVE_POOL: 'success', QUOTE_QUALITY: 'warning',
    BAR_CLOSED: 'info', QUOTE: 'info', QUOTE_FAIL: 'danger', ACCOUNT: 'success', READINESS: 'success', CYCLE: 'info', ERROR: 'danger'
  }
  return map[type] ?? 'info'
}
function submitTagType(status: string): WatchTagType {
  if (status === 'SUBMITTED' || status === 'FILLED') return 'success'
  if (status === 'REJECTED' || status === 'UNKNOWN_SUBMIT') return 'danger'
  if (status === 'DRY_RUN') return 'info'
  return 'warning'
}
function fmtPrice(value: unknown): string {
  if (value === undefined || value === null || value === '' || !Number.isFinite(Number(value))) return '—'
  return Number(value).toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}
function codeSignals(code: string): WatchEvent[] {
  return events.value.filter((e) => e.code === code && e.type === 'SIGNAL' && isBuyOrSellSignal(e))
}
function signalDetailText(row: WatchEvent): string {
  const d = (row.detail as any).detail || {}
  return Object.entries(d).map(([k, v]) => `${k}=${v}`).join('，') || '—'
}

function onDateChange() {
  refreshEpoch += 1
  events.value = []
  tradeEvents.value = []
  adviceEvents.value = []
  cursor = 0
  void refresh(true)
}

async function refresh(full = false) {
  const requestDate = selectedDate.value
  if (!requestDate) return
  const requestEpoch = refreshEpoch
  const requestCursor = cursor
  const requestId = ++refreshSequence
  let eventsChanged = false
  try {
    refreshTick += 1
    if (full) {
      const [allRes, tradesRes, adviceRes, st, market, pool, tickData] = await Promise.all([
        fetchEvents(requestDate, 0, { limit: MAX_EVENTS, latest: true }),
        fetchEvents(requestDate, 0, { type: TRADE_EVENT_QUERY, limit: DISPLAY_SUBMITS, latest: true }),
        fetchEvents(requestDate, 0, { type: ADVICE_EVENT_QUERY, limit: MAX_ADVICE, latest: true })
          .then((res) => ({ ok: true as const, res }))
          .catch((error) => ({ ok: false as const, error })),
        fetchState(requestDate),
        fetchMarket(requestDate, MARKET_QUOTE_LIMIT),
        fetchWatchPool(),
        fetchTicks(requestDate).catch(() => ({ date: requestDate, ticks: ticks.value }))
      ])
      if (
        disposed || requestEpoch !== refreshEpoch || requestDate !== selectedDate.value
        || requestId < lastAppliedRefresh
      ) return
      events.value = allRes.events
      tradeEvents.value = tradesRes.events
      if (adviceRes.ok) {
        adviceEvents.value = adviceRes.res.events
        adviceError.value = false
        void ensureSecurityNames(adviceRes.res.events)
      } else {
        adviceError.value = true
      }
      eventsChanged = true
      cursor = allRes.events.length ? allRes.events[allRes.events.length - 1].seq : 0
      state.value = st
      marketSnapshot.value = market
      watchPool.value = pool
      ticks.value = tickData.ticks
      lastAppliedRefresh = requestId
      void ensureSecurityNames(allRes.events)
    } else {
      const refreshSnapshot = refreshTick % 2 === 0
      const refreshPool = refreshTick % POOL_REFRESH_TICKS === 0
      const refreshAdvice = refreshTick % 3 === 0 || adviceError.value || adviceEvents.value.length === 0
      // 事件与快照并发拉取，避免代理耗时串联；观察池低频刷新即可。
      const [{ events: inc }, adviceRes, st, market, pool, tickData] = await Promise.all([
        fetchEvents(requestDate, requestCursor, { limit: 200 }),
        refreshAdvice
          ? fetchEvents(requestDate, 0, { type: ADVICE_EVENT_QUERY, limit: MAX_ADVICE, latest: true })
              .then((res) => ({ ok: true as const, res }))
              .catch((error) => ({ ok: false as const, error }))
          : Promise.resolve(null),
        refreshSnapshot ? fetchState(requestDate) : Promise.resolve(state.value),
        refreshSnapshot ? fetchMarket(requestDate, MARKET_QUOTE_LIMIT) : Promise.resolve(marketSnapshot.value),
        refreshPool ? fetchWatchPool() : Promise.resolve(watchPool.value),
        refreshSnapshot
          ? fetchTicks(requestDate).catch(() => ({ date: requestDate, ticks: ticks.value }))
          : Promise.resolve({ date: requestDate, ticks: ticks.value })
      ])
      if (
        disposed || requestEpoch !== refreshEpoch || requestDate !== selectedDate.value
        || requestId < lastAppliedRefresh || requestCursor !== cursor
      ) return
      if (inc.length) {
        events.value = [...events.value, ...inc].slice(-MAX_EVENTS)
        const tradeInc = inc.filter((event) => event.type === 'SUBMIT' || event.type === 'FILL')
        if (tradeInc.length) {
          tradeEvents.value = mergeEventsBySeq(tradeEvents.value, tradeInc, DISPLAY_SUBMITS)
        }
        const adviceInc = inc.filter((event) => ADVICE_TYPES.has(event.type))
        if (adviceInc.length) {
          adviceEvents.value = mergeEventsBySeq(adviceEvents.value, adviceInc, MAX_ADVICE)
        }
        eventsChanged = true
        cursor = inc[inc.length - 1].seq
        void ensureSecurityNames(inc)
      }
      if (adviceRes) {
        if (adviceRes.ok) {
          adviceEvents.value = adviceRes.res.events
          adviceError.value = false
          eventsChanged = true
        } else {
          adviceError.value = true
        }
      }
      state.value = st
      marketSnapshot.value = market
      watchPool.value = pool
      if (tickData) ticks.value = tickData.ticks
      lastAppliedRefresh = requestId
    }
    serviceUp.value = true
    if (eventsChanged && detailVisible.value && chart) renderChart()
  } catch {
    if (
      !disposed && requestEpoch === refreshEpoch && requestDate === selectedDate.value
      && requestId >= lastAppliedRefresh
    ) serviceUp.value = false
  }
}

async function initialLoad() {
  try {
    const { days: list } = await fetchDays()
    days.value = list
    serviceUp.value = true
    if (list.length) {
      selectedDate.value = list[0]
      refreshEpoch += 1
      await refresh(true)
    } else {
      watchPool.value = await fetchWatchPool()
    }
  } catch {
    serviceUp.value = false
  }
}

function openDetail(row: { code: string; name?: string }) {
  detailCode.value = row.code
  detailName.value = row.name || securityNames.value[row.code] || ''
  detailVisible.value = true
  if (!detailName.value) {
    void resolveSecurityName(row.code).then((name) => {
      if (name) {
        securityNames.value = { ...securityNames.value, [row.code]: name }
        if (detailCode.value === row.code) {
          detailName.value = name
          renderChart()
        }
      }
    })
  }
  nextTick(() => renderChart())
}

async function addSecurity() {
  const code = addCode.value.trim()
  if (!/^\d{6}$/.test(code)) {
    ElMessage.warning('请输入 6 位证券代码')
    return
  }
  adding.value = true
  try {
    const exchangeType = inferExchangeType(code)
    const exchange = exchangeType === 'SSE' ? 'SH' : exchangeType === 'BSE' ? 'BJ' : 'SZ'
    const name = await resolveSecurityName(code, exchangeType)
    await addWatchPoolMember({ code, name: name || code, exchange })
    watchPool.value = await fetchWatchPool()
    addCode.value = ''
    ElMessage.success(`${code}${name ? ` ${name}` : ''} 已加入观察池，下一轮开始扫描`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加入观察池失败')
  } finally {
    adding.value = false
  }
}

async function removeSecurity(code: string) {
  try {
    await ElMessageBox.confirm(`确认将 ${code} 从观察池移除？`, '移除观察标的', { type: 'warning' })
    await removeWatchPoolMember(code)
    watchPool.value = await fetchWatchPool()
    ElMessage.success(`${code} 已移出观察池`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '移除观察标的失败')
    }
  }
}

async function renderChart() {
  if (!chartRef.value) return
  chartRuntimePromise ??= import('@/utils/echartsTheme')
  const { echarts, CHART_THEME } = await chartRuntimePromise
  if (disposed || !chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value, CHART_THEME)
  const bars = events.value
    .filter((e) => e.code === detailCode.value && e.type === 'BAR_CLOSED')
    .map((e) => e.detail as any)
  const quotes = events.value.filter((e) => e.code === detailCode.value && e.type === 'QUOTE')
  const marks: any[] = events.value
    .filter((e) => e.code === detailCode.value && e.type === 'SIGNAL' && ['buy', 'sell'].includes((e.detail as any).action))
    .map((e) => ({
      coord: [String((e.detail as any).bar_ts).slice(11, 16), (e.detail as any).detail?.close ?? 0],
      symbol: (e.detail as any).action === 'buy' ? 'triangle' : 'pin',
      symbolSize: 12,
      itemStyle: { color: (e.detail as any).action === 'buy' ? '#f23645' : '#089981' },
      label: { show: true, formatter: (e.detail as any).action === 'buy' ? 'B' : 'S', color: '#fff', fontSize: 9 }
    }))

  const closes = bars.map((b) => Number(b.close))
  const ma = (period: number) => closes.map((_, i) =>
    i < period - 1 ? null : Number((closes.slice(i - period + 1, i + 1).reduce((a: number, b: number) => a + b, 0) / period).toFixed(3))
  )

  chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { data: ['K线', 'MA5', 'MA20'] },
    grid: { left: 60, right: 20, top: 40, bottom: 60 },
    xAxis: { type: 'category', data: bars.map((b) => String(b.end_ts).slice(11, 16)) },
    yAxis: { scale: true },
    dataZoom: [{ type: 'inside' }, { type: 'slider' }],
    series: [
      {
        name: 'K线',
        type: 'candlestick',
        data: bars.map((b) => [b.open, b.close, b.low, b.high]),
        itemStyle: { color: '#f23645', color0: '#089981', borderColor: '#f23645', borderColor0: '#089981' },
        markPoint: { data: marks }
      },
      { name: 'MA5', type: 'line', data: ma(5), showSymbol: false, lineStyle: { width: 1 } },
      { name: 'MA20', type: 'line', data: ma(20), showSymbol: false, lineStyle: { width: 1 } }
    ]
  }, true)
  if (quotes.length) {
    chart.setOption({ title: { text: `${detailName.value || '--'}（${detailCode.value}，快照 ${quotes.length} 次）`, left: 4, top: 2, textStyle: { fontSize: 12 } } })
  }
}

function scheduleRefresh() {
  if (disposed) return
  timer = window.setTimeout(async () => {
    if (autoRefresh.value && document.visibilityState === 'visible') await refresh()
    scheduleRefresh()
  }, REFRESH_MS)
}

function onVisibilityChange() {
  if (!disposed && autoRefresh.value && document.visibilityState === 'visible') void refresh()
}

/** D-QUOTE-01：行情源时间只显示时分秒（同日），跨日补日期，缺失返回空串。 */
function hhmmss(value: unknown): string {
  const text = String(value || '')
  if (!text) return ''
  const m = text.match(/(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/)
  if (!m) return text
  const now = new Date()
  const sameDay =
    Number(m[1]) === now.getFullYear() &&
    Number(m[2]) === now.getMonth() + 1 &&
    Number(m[3]) === now.getDate()
  return sameDay ? `${m[4]}:${m[5]}:${m[6]}` : `${m[2]}-${m[3]} ${m[4]}:${m[5]}`
}

onMounted(async () => {
  await initialLoad()
  await nextTick()
  bindHostHeight(recommendHostRef.value, 'recommend', 200)
  bindHostHeight(poolHostRef.value, 'pool', 320)
  bindHostHeight(eventsHostRef.value, 'events', 320)
  bindHostHeight(submitsHostRef.value, 'submits', 180)
  document.addEventListener('visibilitychange', onVisibilityChange)
  scheduleRefresh()
})
onBeforeUnmount(() => {
  disposed = true
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (timer) window.clearTimeout(timer)
  while (hostObservers.length) hostObservers.pop()?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.watch-monitor {
  height: 100%;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-height: 0;
  /* 视口内铺满：滚动只发生在各 panel 的 table-host / el-table 内，避免拖到页底才见到横滚条 */
  overflow: hidden;
  overscroll-behavior: contain;
}

.status-bar {
  flex: 0 0 auto;
}

.recommend-panel {
  flex: 0 0 240px;
  height: 240px;
  max-height: 240px;
}

.status-bar :deep(.el-card__body) {
  padding: var(--sp-2) var(--sp-3);
}

.status-row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
  min-width: 0;
}

.status-item {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  white-space: nowrap;
}

.status-item .label {
  color: var(--text-secondary);
  font-size: 12px;
}

.status-item .value {
  font-size: 13px;
}

.status-item.grow {
  flex: 1;
  min-width: 200px;
}

.main-grid {
  flex: 1 1 0;
  min-height: 0;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  display: grid;
  /* 轨道允许收缩；表格的最小可读宽度由 table-host / el-table 内部横向滚动承接。 */
  grid-template-columns: minmax(0, 420px) minmax(0, 1fr) minmax(0, 430px);
  grid-template-rows: minmax(0, 1fr);
  gap: var(--sp-3);
  overflow: hidden;
  overscroll-behavior: contain;
}

.right-column {
  display: grid;
  grid-template-rows: minmax(0, 2fr) minmax(0, 5fr);
  gap: var(--sp-3);
  min-height: 0;
  min-width: 0;
  height: 100%;
  overflow: hidden;
}

.panel {
  display: flex !important;
  flex-direction: column;
  height: 100%;
  max-height: 100%;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

.panel :deep(.el-card__header) {
  flex: 0 0 auto;
}

.panel :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  flex: 1 1 0;
  min-height: 0;
  padding: var(--sp-2);
  min-width: 0;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
  flex-wrap: wrap;
}

.panel-title {
  display: inline-flex;
  align-items: baseline;
  gap: var(--sp-2);
  min-width: 0;
}

.pool-header {
  gap: var(--sp-2);
}

.pool-add {
  display: flex;
  gap: var(--sp-1);
  width: min(220px, 100%);
  min-width: 190px;
}

.filters {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  justify-content: flex-end;
}

.table-host {
  flex: 1 1 0;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  min-height: 0;
  position: relative;
  overflow: hidden;
  overscroll-behavior: contain;
}

.watch-table {
  width: 100%;
  --el-table-bg-color: #111827;
  --el-table-tr-bg-color: #111827;
  --el-table-header-bg-color: #1e293b;
  --el-table-text-color: #e5edf7;
  --el-table-header-text-color: #cbd5e1;
  --el-table-border-color: rgba(148, 163, 184, 0.22);
  --el-table-row-hover-bg-color: rgba(37, 99, 235, 0.28);
  --el-table-current-row-bg-color: rgba(37, 99, 235, 0.34);
  background: #111827 !important;
  color: #e5edf7 !important;
}

.watch-table :deep(.el-table__inner-wrapper),
.watch-table :deep(.el-table__header-wrapper),
.watch-table :deep(.el-table__body-wrapper),
.watch-table :deep(.el-table__footer-wrapper),
.watch-table :deep(.el-table__empty-block) {
  background: #111827 !important;
}

.watch-table :deep(.el-table__body-wrapper) {
  overflow-x: auto !important;
  overflow-y: auto !important;
  scrollbar-width: thin;
}

.watch-table :deep(.el-table__header-wrapper) {
  overflow-x: hidden !important;
}

.watch-table :deep(th.el-table__cell) {
  background: #1e293b !important;
  color: #cbd5e1 !important;
  border-bottom-color: rgba(148, 163, 184, 0.22) !important;
}

.watch-table :deep(td.el-table__cell) {
  background: #111827 !important;
  color: #e5edf7 !important;
  border-bottom-color: rgba(148, 163, 184, 0.14) !important;
}

.watch-table :deep(.cell) {
  color: inherit;
}

.watch-table :deep(tbody tr:hover > td.el-table__cell),
.watch-table :deep(tbody tr.current-row > td.el-table__cell) {
  background: rgba(37, 99, 235, 0.28) !important;
  color: #e5edf7 !important;
}

.watch-table :deep(.el-table__expanded-cell),
.signal-table :deep(td.el-table__cell),
.signal-table :deep(th.el-table__cell) {
  background: #1e293b !important;
  color: #e5edf7 !important;
}

/* 列最小宽度保证字段可读；超出面板宽度时在表格内横向滚动 */
.pool-table { min-width: 700px; }
.events-table { min-width: 930px; }
.submits-table { min-width: 820px; }
.recommend-table { min-width: 960px; }

.table-host :deep(.el-table__body-wrapper)::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

.table-host :deep(.el-table__body-wrapper)::-webkit-scrollbar-track {
  background: var(--surface-2);
  border-radius: var(--r-pill);
}

.table-host :deep(.el-table__body-wrapper)::-webkit-scrollbar-thumb {
  background: var(--accent-1);
  border: 2px solid var(--surface-2);
  border-radius: var(--r-pill);
}

.table-host :deep(.el-table__body-wrapper)::-webkit-scrollbar-thumb:hover {
  background: var(--accent-2);
}

.panel-hint {
  color: var(--text-dim);
  font-size: var(--fz-xs);
  font-weight: 400;
  white-space: nowrap;
}

.pool-name,
.event-summary {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  line-height: 1.35;
}

.event-detail pre {
  margin: var(--sp-1) 0 0;
  padding: var(--sp-2);
  background: var(--bg-2);
  border-radius: var(--r-md, 8px);
  font-size: 12px;
  max-height: 240px;
  overflow: auto;
}

.gates {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-1);
}

.error-code {
  font-size: 11px;
  color: var(--down);
}

.detail-chart {
  width: 100%;
  height: 420px;
}

.signal-table {
  margin-top: var(--sp-3);
  --el-table-bg-color: #111827;
  --el-table-tr-bg-color: #111827;
  --el-table-header-bg-color: #1e293b;
  background: #111827 !important;
  color: #e5edf7 !important;
}

.up {
  color: var(--up);
}

.down {
  color: var(--down);
}

/* 三列布局在窄屏下会让中间事件流失去可读宽度，改为纵向堆叠。 */
@media (max-width: 1100px) {
  .watch-monitor {
    height: 100%;
    min-width: 0;
    overflow: hidden;
  }

  .status-row {
    min-width: 0;
  }

  .status-item.grow {
    flex-basis: 100%;
    min-width: 0;
    order: 10;
  }

  .main-grid {
    display: flex;
    flex-direction: column;
    min-width: 0;
    overflow-x: hidden;
    overflow-y: auto;
  }

  .universe-panel,
  .events-panel,
  .submits-panel {
    flex: 0 0 auto;
    height: min(52vh, 520px);
    max-height: min(52vh, 520px);
  }

  .recommend-panel {
    flex: 0 0 220px;
    height: 220px;
    max-height: 220px;
  }

  .right-column {
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: auto;
    overflow: visible;
  }

  .filters {
    width: 100%;
    justify-content: flex-start;
  }

  .pool-header .pool-add {
    width: 100%;
    min-width: 0;
    flex-basis: 100%;
  }
}
</style>
