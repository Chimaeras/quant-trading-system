<template>
  <div class="watch-monitor terminal-page">
    <!-- ① 状态条 -->
    <el-card shadow="hover" class="status-bar">
      <div class="status-row">
        <div class="status-item">
          <span class="label">交易日</span>
          <el-select v-model="selectedDate" :options="days" style="width: 140px" size="small" @change="onDateChange">
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

    <div class="main-grid">
      <!-- ② 观察池 -->
      <el-card shadow="never" class="panel universe-panel">
        <template #header><span>观察池（最新快照）</span></template>
        <el-table :data="universeRows" size="small" height="100%" @row-click="openDetail" highlight-current-row>
          <el-table-column prop="code" label="代码" width="80">
            <template #default="{ row }">
              <el-link type="primary" :underline="false">{{ row.code }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="105" show-overflow-tooltip />
          <el-table-column label="最新价" width="80" align="right">
            <template #default="{ row }">{{ row.last?.toFixed(2) ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="涨跌幅" width="80" align="right">
            <template #default="{ row }">
              <span v-if="row.pct !== undefined" :class="row.pct >= 0 ? 'up' : 'down'">
                {{ row.pct >= 0 ? '+' : '' }}{{ row.pct.toFixed(2) }}%
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="90">
            <template #default="{ row }">
              <el-tooltip v-if="row.disabled" :content="row.disabled" placement="top">
                <el-tag type="info" size="small">禁用</el-tag>
              </el-tooltip>
              <el-tag v-else type="success" size="small">盯盘中</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- ③ 分析过程流 -->
      <el-card shadow="never" class="panel events-panel">
        <template #header>
          <div class="panel-header">
            <span>分析过程流（{{ filteredEvents.length }} 条事件）</span>
            <div class="filters">
              <el-select v-model="typeFilter" clearable placeholder="事件类型" size="small" style="width: 130px">
                <el-option v-for="t in typeOptions" :key="t" :label="t" :value="t" />
              </el-select>
              <el-input v-model="codeFilter" placeholder="代码" clearable size="small" style="width: 100px" />
              <el-switch v-model="tradesOnly" active-text="只看买卖" size="small" />
            </div>
          </div>
        </template>
        <el-table :data="filteredEvents" size="small" height="100%" border :row-key="(r: any) => r.seq">
          <el-table-column prop="ts" label="时间" width="96">
            <template #default="{ row }">{{ row.ts.slice(11) }}</template>
          </el-table-column>
          <el-table-column prop="seq" label="#" width="64" sortable />
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag :type="typeTagType(row.type)" size="small">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="code" label="代码" width="80" />
          <el-table-column prop="name" label="名称" width="105" show-overflow-tooltip />
          <el-table-column label="内容" min-width="240">
            <template #default="{ row }">
              <span :class="eventSummaryClass(row)">{{ eventSummary(row) }}</span>
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
                <pre>{{ JSON.stringify(row.detail, null, 2) }}</pre>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <div class="right-column">
        <!-- ④ 交易动作 -->
        <el-card shadow="never" class="panel submits-panel">
          <template #header><span>交易动作（{{ submitEvents.length }}）</span></template>
          <el-table :data="submitEvents" size="small" height="100%">
            <el-table-column prop="ts" label="时间" width="90">
              <template #default="{ row }">{{ row.ts.slice(11) }}</template>
            </el-table-column>
            <el-table-column prop="code" label="代码" width="76" />
            <el-table-column prop="name" label="名称" width="100" show-overflow-tooltip />
            <el-table-column label="方向" width="56">
              <template #default="{ row }">
                <span :class="(row.detail as any).side === 'buy' ? 'up' : 'down'">
                  {{ (row.detail as any).side === 'buy' ? '买' : '卖' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="数量/价格" min-width="110">
              <template #default="{ row }">
                {{ (row.detail as any).qty }} 股 @ {{ (row.detail as any).price?.toFixed(2) }}
              </template>
            </el-table-column>
            <el-table-column label="结果" min-width="110">
              <template #default="{ row }">
                <el-tag :type="submitTagType((row.detail as any).status)" size="small">
                  {{ (row.detail as any).status }}
                </el-tag>
                <div v-if="(row.detail as any).error_code" class="error-code">{{ (row.detail as any).error_code }}</div>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- ⑤ 账户快照 -->
        <el-card shadow="never" class="panel account-panel">
          <template #header><span>账户快照</span></template>
          <div class="account-grid">
            <div class="account-item">
              <span class="label">总资产</span>
              <span class="value">{{ fmtMoney(accountBefore.total_assets) }}</span>
            </div>
            <div class="account-item">
              <span class="label">可用资金</span>
              <span class="value">{{ fmtMoney(accountBefore.avail_balance) }}</span>
            </div>
            <div class="account-item">
              <span class="label">持仓数</span>
              <span class="value">{{ accountBefore.positions ?? '—' }}</span>
            </div>
            <div class="account-item">
              <span class="label">在途委托</span>
              <span class="value">{{ accountBefore.open_orders ?? '—' }}</span>
            </div>
          </div>
          <div v-if="accountAfter" class="account-after">
            提交后：总资产 {{ fmtMoney(accountAfter.total_assets) }} / 可用 {{ fmtMoney(accountAfter.avail_balance) }}
          </div>
        </el-card>
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
import { computed, onBeforeUnmount, onMounted, ref, nextTick } from 'vue'
import { echarts, CHART_THEME } from '@/utils/echartsTheme'
import {
  fetchDays,
  fetchEvents,
  fetchState,
  type WatchEvent
} from '@/api/watch'

const MAX_EVENTS = 3000
const REFRESH_MS = 3000

const days = ref<string[]>([])
const selectedDate = ref('')
const events = ref<WatchEvent[]>([])
const state = ref<Awaited<ReturnType<typeof fetchState>> | null>(null)
const serviceUp = ref(true)
const autoRefresh = ref(true)

const typeFilter = ref('')
const codeFilter = ref('')
const tradesOnly = ref(false)
const typeOptions = ['CYCLE', 'QUOTE', 'QUOTE_FAIL', 'BAR_CLOSED', 'SIGNAL', 'SIZING', 'RISK_GATE', 'SUBMIT', 'ACCOUNT', 'ERROR']

const detailVisible = ref(false)
const detailCode = ref('')
const detailName = ref('')
const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let timer: number | null = null
let cursor = 0

const lastCycle = computed(() => {
  const cycles = events.value.filter((e) => e.type === 'CYCLE' && (e.detail as any).phase === 'end')
  return cycles.length ? cycles[cycles.length - 1] : null
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

const universeRows = computed(() => {
  const rows = new Map<string, { code: string; name?: string; last?: number; pct?: number; disabled?: string }>()
  for (const e of events.value) {
    if (e.type === 'QUOTE' && e.code) {
      rows.set(e.code, {
        code: e.code,
        name: e.name || String((e.detail as any).stock_name || ''),
        last: (e.detail as any).last,
        pct: (e.detail as any).pct_chg
      })
    } else if (e.type === 'ERROR' && e.code && (e.detail as any).scope === 'KLINE_DISABLED' && !rows.has(e.code)) {
      rows.set(e.code, { code: e.code, name: e.name, disabled: String((e.detail as any).message || 'KLINE_DISABLED') })
    }
  }
  return [...rows.values()]
})

const filteredEvents = computed(() => {
  let list = events.value
  if (typeFilter.value) list = list.filter((e) => e.type === typeFilter.value)
  if (codeFilter.value) list = list.filter((e) => e.code === codeFilter.value)
  if (tradesOnly.value) list = list.filter((e) => ['SIGNAL', 'SIZING', 'RISK_GATE', 'SUBMIT'].includes(e.type))
  return [...list].reverse()
})

const submitEvents = computed(() =>
  events.value.filter((e) => e.type === 'SUBMIT').map((e) => ({ ...e, code: e.code })).reverse()
)

const accountBefore = computed<Record<string, any>>(() => {
  const list = events.value.filter((e) => e.type === 'ACCOUNT' && (e.detail as any).phase === 'before')
  return list.length ? (list[list.length - 1].detail as any) : {}
})
const accountAfter = computed<Record<string, any> | null>(() => {
  const list = events.value.filter((e) => e.type === 'ACCOUNT' && (e.detail as any).phase === 'after')
  return list.length ? (list[list.length - 1].detail as any) : null
})

function eventSummary(row: WatchEvent): string {
  const d = row.detail as any
  switch (row.type) {
    case 'QUOTE':
      return `${Number(d.last).toFixed(2)} ${d.pct_chg >= 0 ? '+' : ''}${Number(d.pct_chg).toFixed(2)}%`
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
    case 'ACCOUNT':
      return `${d.phase}：总资产 ${d.total_assets} 可用 ${d.avail_balance} 持仓 ${d.positions} 委托 ${d.open_orders}`
    case 'CYCLE':
      return d.phase === 'start' ? `轮次开始（${d.live_requested ? '请求 live' : 'dry-run'}）` : `状态 ${d.status}，bar ${d.bars} 信号 ${d.signals} 提交 ${d.submits} 错误 ${d.errors}`
    case 'ERROR':
      return `${d.scope}: ${d.message ?? (d.messages || []).join('; ')}`
    default:
      return JSON.stringify(d)
  }
}
function eventSummaryClass(row: WatchEvent): string {
  if (row.type === 'SIGNAL' && (row.detail as any).action === 'buy') return 'up'
  if (row.type === 'SIGNAL' && (row.detail as any).action === 'sell') return 'down'
  if (row.type === 'RISK_GATE' && (row.detail as any).result === 'REJECT') return 'down'
  if (row.type === 'QUOTE' && Number((row.detail as any).pct_chg) < 0) return 'down'
  if (row.type === 'QUOTE' && Number((row.detail as any).pct_chg) > 0) return 'up'
  return ''
}
function typeTagType(type: string): string {
  const map: Record<string, string> = {
    SIGNAL: 'warning', SUBMIT: 'danger', RISK_GATE: 'primary', SIZING: 'primary',
    BAR_CLOSED: 'info', QUOTE: 'info', QUOTE_FAIL: 'danger', ACCOUNT: 'success', CYCLE: '', ERROR: 'danger'
  }
  return map[type] ?? 'info'
}
function submitTagType(status: string): string {
  if (status === 'SUBMITTED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'DRY_RUN') return 'info'
  return 'warning'
}
function fmtMoney(value: unknown): string {
  if (value === undefined || value === null) return '—'
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function codeSignals(code: string): WatchEvent[] {
  return events.value.filter((e) => e.code === code && e.type === 'SIGNAL' && (e.detail as any).action !== 'hold')
}
function signalDetailText(row: WatchEvent): string {
  const d = (row.detail as any).detail || {}
  return Object.entries(d).map(([k, v]) => `${k}=${v}`).join('，') || '—'
}

function onDateChange() {
  events.value = []
  cursor = 0
  refresh(true)
}

async function refresh(full = false) {
  if (!selectedDate.value) return
  try {
    if (full) {
      const [{ events: all }, st] = await Promise.all([fetchEvents(selectedDate.value, 0, { limit: MAX_EVENTS }), fetchState(selectedDate.value)])
      events.value = all
      cursor = all.length ? all[all.length - 1].seq : 0
      state.value = st
    } else {
      const { events: inc } = await fetchEvents(selectedDate.value, cursor, { limit: MAX_EVENTS })
      if (inc.length) {
        events.value = [...events.value, ...inc].slice(-MAX_EVENTS)
        cursor = inc[inc.length - 1].seq
      }
      state.value = await fetchState(selectedDate.value)
    }
    serviceUp.value = true
    if (detailVisible.value && chart) renderChart()
  } catch {
    serviceUp.value = false
  }
}

async function initialLoad() {
  try {
    const { days: list } = await fetchDays()
    days.value = list
    serviceUp.value = true
    if (list.length) {
      selectedDate.value = list[0]
      await refresh(true)
    }
  } catch {
    serviceUp.value = false
  }
}

function openDetail(row: { code: string; name?: string }) {
  detailCode.value = row.code
  detailName.value = row.name || ''
  detailVisible.value = true
  nextTick(() => renderChart())
}

function renderChart() {
  if (!chartRef.value) return
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
    chart.setOption({ title: { text: `${detailCode.value}（快照 ${quotes.length} 次）`, left: 4, top: 2, textStyle: { fontSize: 12 } } })
  }
}

onMounted(() => {
  initialLoad()
  timer = window.setInterval(() => {
    if (autoRefresh.value) refresh()
  }, REFRESH_MS)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.watch-monitor {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-height: 0;
}

.status-bar :deep(.el-card__body) {
  padding: var(--sp-2) var(--sp-3);
}

.status-row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
}

.status-item {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  white-space: nowrap;
}

.status-item .label {
  color: var(--text-secondary, #909399);
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
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 260px 1fr 360px;
  gap: var(--sp-3);
}

.right-column {
  display: grid;
  grid-template-rows: 1fr auto;
  gap: var(--sp-3);
  min-height: 0;
}

.panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.panel :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  padding: var(--sp-2);
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filters {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.event-detail pre {
  margin: var(--sp-1) 0 0;
  padding: var(--sp-2);
  background: var(--bg-secondary, #f5f7fa);
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

.account-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-3);
}

.account-item .label {
  display: block;
  color: var(--text-secondary, #909399);
  font-size: 12px;
}

.account-item .value {
  font-size: 15px;
  font-weight: 600;
}

.account-after {
  margin-top: var(--sp-2);
  padding-top: var(--sp-2);
  border-top: 1px dashed var(--border-color, #dcdfe6);
  font-size: 12px;
  color: var(--text-secondary, #909399);
}

.detail-chart {
  width: 100%;
  height: 420px;
}

.signal-table {
  margin-top: var(--sp-3);
}

.up {
  color: var(--up);
}

.down {
  color: var(--down);
}
</style>
