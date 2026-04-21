<template>
  <div class="page-wrap analysis-dashboard">
    <EmptyState
      v-if="!route.query.code"
      title="未选择股票"
      description="请从股票浏览器选择标的，或直接在下方输入代码。"
    >
      <UiButton variant="primary" @click="$router.push('/data/stocks')">去选股</UiButton>
    </EmptyState>

    <template v-else>
      <Panel elevated>
        <div class="dash-header">
          <div class="title-block">
            <span class="name">{{ detail.stock_name || "--" }}</span>
            <Badge>{{ detail.stock_code || "--" }}</Badge>
          </div>
          <div class="price-block" :class="priceChangeClass">
            <span class="price">{{ formatPrice(detail.close_price) }}</span>
            <span v-if="detail.change_pct != null" class="chg">
              {{ formatChange(detail.close_price, detail.pre_close) }} ({{ formatPct(detail.change_pct) }}%)
            </span>
          </div>
        </div>
        <div class="toolbar">
          <UiInput v-model="quickCode" placeholder="股票代码" @enter="goStock" />
          <UiButton variant="primary" @click="goStock">切换</UiButton>
          <UiInput v-model="startDate" placeholder="开始日期 YYYY-MM-DD" />
          <UiInput v-model="endDate" placeholder="结束日期 YYYY-MM-DD" />
          <UiButton variant="ghost" :loading="loading" @click="loadAll">刷新</UiButton>
          <UiButton variant="primary" :loading="analysisLoading" @click="runAnalysis">生成分析</UiButton>
        </div>
      </Panel>

      <div class="dash-body">
        <Panel title="K线图" class="chart-col">
          <div ref="chartRef" class="kline-chart" />
          <div class="ma-legend" v-if="klineData.length">
            <span v-for="ma in maList" :key="ma.key" :style="{ color: ma.color }">MA{{ ma.period }}: {{ ma.value }}</span>
          </div>
        </Panel>

        <div class="panel-col">
          <Panel v-if="report" title="状态">
            <div class="stat-cards">
              <Card label="评分" :value="report.confidence ?? '--'" />
              <Card label="决策" :value="actionLabel(report.action)" />
              <Card label="置信度" :value="confidenceBand(report.confidence)" />
              <Card label="趋势" :value="trendLabel(report)" />
            </div>
          </Panel>
          <Panel v-if="report" title="接口调用与指标">
            <ul class="bullet-list">
              <li>趋势强度：{{ report.score_breakdown?.trend ?? "--" }} / 100</li>
              <li>RSI：{{ rsiLabel(report) }}</li>
              <li>均线：{{ maStructureLabel(report) }}</li>
              <li>MACD：{{ macdLabel(report) }}</li>
              <li>量能：{{ volumeLabel(report) }}</li>
            </ul>
          </Panel>
          <Panel v-if="report && planSafe" title="交易执行建议">
            <ul class="bullet-list">
              <li>参考入场：{{ planSafe.entry || "--" }}</li>
              <li>止损：{{ fmtNum(planSafe.stopLoss) }}</li>
              <li>止盈：{{ fmtNum(planSafe.takeProfit) }}</li>
              <li>支撑/阻力：{{ fmtNum(planSafe.support) }} / {{ fmtNum(planSafe.resistance) }}</li>
              <li>目标仓位：{{ planSafe.targetPosition || report.target_position }}</li>
            </ul>
          </Panel>
        </div>
      </div>

      <div class="grid-2">
        <Panel v-if="report" title="输出摘要">
          <p class="summary-text">{{ displaySummary }}</p>
        </Panel>
        <Panel v-if="report" title="风险与机会">
          <ul class="bullet-list risk">
            <li v-for="(x, i) in displayRisks" :key="'r' + i">{{ x }}</li>
          </ul>
          <ul class="bullet-list opp">
            <li v-for="(x, i) in displayOpps" :key="'o' + i">{{ x }}</li>
          </ul>
        </Panel>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import {
  marketApi,
  type StockDetail,
  type KLineItem,
  type StockAnalysisReport
} from '@/api/market'
import Panel from '@/components/ui/Panel.vue'
import Card from '@/components/ui/Card.vue'
import Badge from '@/components/ui/Badge.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import UiButton from '@/components/ui/Button.vue'
import UiInput from '@/components/ui/Input.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const analysisLoading = ref(false)
const detail = ref<Partial<StockDetail>>({})
const klineData = ref<KLineItem[]>([])
const startDate = ref('')
const endDate = ref('')
const quickCode = ref('')
const report = ref<StockAnalysisReport | null>(null)
const chartRef = ref<HTMLElement>()
let chart: echarts.ECharts | null = null

const maPeriods = [5, 10, 20, 30]
const maColors: Record<number, string> = { 5: '#f5a623', 10: '#2e7d32', 20: '#1976d2', 30: '#7b1fa2' }

const priceChangeClass = computed(() => {
  const pct = detail.value.change_pct
  if (pct == null) return ''
  return pct >= 0 ? 'up' : 'down'
})

const maList = computed(() => {
  const data = klineData.value
  if (data.length === 0) return []
  return maPeriods.map(period => {
    const slice = data.slice(-period)
    const sum = slice.reduce((a, b) => a + (b.close ?? 0), 0)
    const avg = slice.length ? sum / slice.length : 0
    return { key: period, period, value: avg.toFixed(2), color: maColors[period] }
  })
})

const planSafe = computed(() => report.value?.plan ?? null)

const displayTags = computed(() => {
  const r = report.value
  if (!r) return []
  if (r.tags?.length) return r.tags
  const snap = r.indicator_snapshot || {}
  const tags: string[] = []
  if (snap.ma20 != null && snap.ma60 != null) {
    tags.push(snap.ma20 > snap.ma60 ? 'MA20>MA60' : 'MA20≤MA60')
  }
  if (snap.rsi14 != null) tags.push('RSI ' + String(snap.rsi14))
  return tags.length ? tags : ['—']
})

const displaySummary = computed(() => {
  const r = report.value
  if (!r) return ''
  if (r.summary) return r.summary
  if (r.reasons?.length) return r.reasons.join(' ')
  return '暂无摘要'
})

const displayRisks = computed(() => {
  const r = report.value
  if (!r) return []
  if (r.risk_points?.length) return r.risk_points
  const out: string[] = []
  const v = r.indicator_snapshot?.volatility20
  if (v != null && v > 4) out.push('波动偏高（约 ' + v.toFixed(2) + '%）')
  if (r.reasons?.length) out.push(...r.reasons.slice(0, 2))
  return out.length ? out : ['暂无额外风险提示']
})

const displayOpps = computed(() => {
  const r = report.value
  if (!r) return []
  if (r.opportunity_points?.length) return r.opportunity_points
  return ['数据或规则未生成机会点，请查看技术指标与摘要']
})

function formatPrice(v: number | undefined) {
  return v != null ? v.toFixed(2) : '--'
}
function formatPct(v: number | undefined) {
  return v != null ? v.toFixed(2) : '--'
}
function formatChange(close?: number, pre?: number) {
  if (close == null || pre == null || pre === 0) return ''
  const ch = close - pre
  return (ch >= 0 ? '+' : '') + ch.toFixed(2)
}
function fmtNum(v: number | undefined) {
  if (v == null || v === 0) return '--'
  return v.toFixed(2)
}

function actionLabel(a: string) {
  const m: Record<string, string> = { ADD: '偏多 / 加仓', REDUCE: '偏空 / 减仓', HOLD: '观望' }
  return m[a] || a
}

function confidenceBand(c: number | undefined) {
  if (c == null) return '--'
  if (c < 40) return '低'
  if (c < 70) return '中'
  return '高'
}

function trendLabel(r: StockAnalysisReport) {
  const s = r.signals?.ma
  if (s === 'bullish') return '偏多'
  if (s === 'bearish') return '偏空'
  const snap = r.indicator_snapshot
  if (snap?.ma20 != null && snap?.ma60 != null && snap?.close != null) {
    if (snap.close > snap.ma20 && snap.ma20 > snap.ma60) return '盘整偏多'
    if (snap.close < snap.ma20 && snap.ma20 < snap.ma60) return '盘整偏空'
  }
  return '盘整'
}

function rsiLabel(r: StockAnalysisReport) {
  const sig = r.signals?.rsi
  const v = r.indicator_snapshot?.rsi14
  const base = v != null ? v.toFixed(2) : '--'
  if (sig === 'overbought') return '偏热 (' + base + ')'
  if (sig === 'oversold') return '偏冷 (' + base + ')'
  return '中性 (' + base + ')'
}

function maStructureLabel(r: StockAnalysisReport) {
  const snap = r.indicator_snapshot
  if (snap?.ma20 == null || snap?.ma60 == null || snap?.close == null) return '--'
  const c = snap.close
  const m20 = snap.ma20
  const m60 = snap.ma60
  if (c > m20 && m20 > m60) return 'C > MA20 > MA60（偏强）'
  if (c < m20 && m20 < m60) return 'C < MA20 < MA60（偏弱）'
  return '均线交织 / 震荡'
}

function macdLabel(r: StockAnalysisReport) {
  const sig = r.signals?.macd
  const h = r.indicator_snapshot?.macd_hist
  const t = h != null ? String(h) : '--'
  if (sig === 'bullish') return '多头 (' + t + ')'
  if (sig === 'bearish') return '空头 (' + t + ')'
  return '震荡 (' + t + ')'
}

function volumeLabel(r: StockAnalysisReport) {
  const sig = r.signals?.volume
  const vr = r.indicator_snapshot?.volume_ratio
  const t = vr != null ? '量比 ' + vr.toFixed(2) : '--'
  if (sig === 'strong') return '放量 (' + t + ')'
  if (sig === 'weak') return '缩量 (' + t + ')'
  return '量能正常 (' + t + ')'
}

async function loadDetail() {
  const code = route.query.code as string
  if (!code) return
  try {
    const res = await marketApi.getStockDetail(code, route.query.exchange as string)
    detail.value = (res as { data?: StockDetail }).data ?? {}
  } catch (e) {
    console.error(e)
  }
}

async function loadKline() {
  const code = route.query.code as string
  if (!code) return
  const start = startDate.value || dayjs().subtract(1, 'year').format('YYYY-MM-DD')
  const end = endDate.value || dayjs().format('YYYY-MM-DD')
  try {
    const res = await marketApi.getKline(code, {
      exchange: route.query.exchange as string,
      startDate: start,
      endDate: end,
      limit: 500
    })
    const data = (res as { data?: KLineItem[] }).data ?? []
    klineData.value = Array.isArray(data) ? data : []
    renderChart()
  } catch (e) {
    console.error(e)
    klineData.value = []
  }
}

async function runAnalysis() {
  const code = route.query.code as string
  if (!code) return
  analysisLoading.value = true
  try {
    const res = await marketApi.analyzeStock({
      stockCode: code,
      exchange: route.query.exchange as string
    })
    report.value = (res as { data?: StockAnalysisReport }).data ?? null
    renderChart()
  } finally {
    analysisLoading.value = false
  }
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadDetail(), loadKline()])
    await runAnalysis()
  } finally {
    loading.value = false
  }
}

function renderChart() {
  const data = klineData.value
  if (!chartRef.value || data.length === 0) return
  if (!chart) chart = echarts.init(chartRef.value)

  const dates = data.map(d => d.date)
  const ohlc = data.map(d => [d.open, d.close, d.low, d.high])
  const volumes = data.map(d => d.volume ?? 0)
  const closes = data.map(d => d.close ?? 0)

  const maSeries = maPeriods.map(period => {
    const vals: (number | string)[] = []
    for (let i = 0; i < data.length; i++) {
      const slice = closes.slice(Math.max(0, i - period + 1), i + 1)
      vals.push(slice.length ? slice.reduce((a, b) => a + b, 0) / slice.length : '-')
    }
    return { period, vals, color: maColors[period] }
  })

  const rl = report.value?.risk_lines
  const markLineData: echarts.MarkLineComponentOption['data'] = []
  if (rl && !report.value?.data_insufficient) {
    if (rl.support) markLineData.push({ name: '支撑', yAxis: rl.support, lineStyle: { type: 'dashed', color: '#26a69a' } })
    if (rl.resistance) markLineData.push({ name: '阻力', yAxis: rl.resistance, lineStyle: { type: 'dashed', color: '#ef5350' } })
  }

  const candleSeries: echarts.SeriesOption = {
    type: 'candlestick',
    data: ohlc,
    xAxisIndex: 0,
    yAxisIndex: 0,
    itemStyle: {
      color: '#ef5350',
      color0: '#26a69a',
      borderColor: '#ef5350',
      borderColor0: '#26a69a'
    }
  }
  if (markLineData.length) {
    ;(candleSeries as { markLine?: echarts.MarkLineComponentOption }).markLine = {
      symbol: 'none',
      data: markLineData,
      label: { show: true, formatter: '{b}' }
    }
  }

  const option: echarts.EChartsOption = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params: unknown) => {
        const arr = params as { dataIndex?: number }[]
        const i = arr[0]?.dataIndex ?? -1
        if (i < 0 || !data[i]) return ''
        const d = data[i]
        const maStr = maSeries
          .map(s => `MA${s.period}: ${typeof s.vals[i] === 'number' ? (s.vals[i] as number).toFixed(2) : '-'}`)
          .join('<br/>')
        return `${d.date}<br/>开/高/低/收: ${d.open?.toFixed(2)} / ${d.high?.toFixed(2)} / ${d.low?.toFixed(2)} / ${d.close?.toFixed(2)}<br/>${maStr}`
      }
    },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: 56, right: 40, top: 20, height: '54%' },
      { left: 56, right: 40, top: '72%', height: '16%' }
    ],
    xAxis: [
      { type: 'category', data: dates, gridIndex: 0, axisLabel: { color: '#64748b' } },
      { type: 'category', data: dates, gridIndex: 1, axisLabel: { color: '#64748b' } }
    ],
    yAxis: [
      { type: 'value', gridIndex: 0, splitLine: { lineStyle: { color: '#e2e8f0' } }, axisLabel: { color: '#64748b' }, scale: true },
      { type: 'value', gridIndex: 1, splitLine: { show: false }, axisLabel: { color: '#64748b' } }
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: Math.max(0, 100 - 25000 / data.length), end: 100 },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 4, height: 18, start: Math.max(0, 100 - 25000 / data.length), end: 100 }
    ],
    series: [
      candleSeries,
      ...maSeries.map(s => ({
        type: 'line' as const,
        data: s.vals,
        xAxisIndex: 0,
        yAxisIndex: 0,
        symbol: 'none',
        lineStyle: { color: s.color, width: 1.2 },
        smooth: false
      })),
      {
        type: 'bar',
        data: volumes.map((v, i) => ({
          value: v,
          itemStyle: { color: (closes[i] >= (data[i]?.open ?? 0) ? '#ef5350' : '#26a69a') + '55' }
        })),
        xAxisIndex: 1,
        yAxisIndex: 1
      }
    ]
  }
  chart.setOption(option, true)
}

function goStock() {
  const code = quickCode.value?.trim()
  if (!code) return
  router.push({ path: '/market/analysis-dashboard', query: { code } })
}

onMounted(() => {
  quickCode.value = (route.query.code as string) || ''
  startDate.value = dayjs().subtract(1, 'year').format('YYYY-MM-DD')
  endDate.value = dayjs().format('YYYY-MM-DD')
  if (route.query.code) loadAll()
})

watch(
  () => route.query.code,
  code => {
    quickCode.value = (code as string) || ''
    report.value = null
    if (code) loadAll()
  }
)

watch([startDate, endDate], () => {
  if (route.query.code) loadKline()
})

onBeforeUnmount(() => {
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.analysis-dashboard {
  gap: var(--sp-4);
}
.dash-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}
.title-block {
  display: flex;
  align-items: center;
  gap: 10px;
}
.title-block .name {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
}
.title-block .code {
  font-size: 13px;
  color: var(--text-secondary);
}
.price-block {
  font-size: 24px;
  font-weight: 600;
}
.price-block.up {
  color: #ef5350;
}
.price-block.down {
  color: #26a69a;
}
.price-block .chg {
  font-size: 14px;
  margin-left: 10px;
}
.toolbar {
  display: grid;
  grid-template-columns: minmax(120px, 160px) auto repeat(4, minmax(110px, 1fr));
  gap: 8px;
}
.dash-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.chart-col {
  flex: 1;
  min-width: 0;
  position: relative;
}
.kline-chart {
  height: 500px;
}
.ma-legend {
  position: absolute;
  top: 4px;
  left: 56px;
  font-size: 11px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.panel-col {
  width: 360px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.stat-cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.summary-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-secondary);
}
.bullet-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.55;
}
.bullet-list.risk li::marker {
  color: #ef4444;
}
.bullet-list.opp li::marker {
  color: #22c55e;
}
@media (max-width: 1100px) {
  .toolbar {
    grid-template-columns: 1fr 1fr;
  }
  .dash-body {
    flex-direction: column;
  }
  .panel-col {
    width: 100%;
  }
}
</style>
