<template>
  <div class="stock-detail" v-loading="loading">
    <el-empty v-if="!route.query.code" description="请从股票浏览器选择股票查看详情" style="padding: 80px 0">
      <el-button type="primary" @click="$router.push('/data/stocks')">去选股</el-button>
    </el-empty>
    <template v-else>
    <!-- 顶部：股票名称、代码、最新价、涨跌幅 -->
    <div class="stock-header">
      <div class="stock-title">
        <span class="name">{{ detail.stock_name || '--' }}</span>
        <span class="code">{{ detail.stock_code }}</span>
        <el-tag size="small" type="info" class="exchange-tag">{{ detail.exchange_type_name || detail.exchange_type || '--' }}</el-tag>
      </div>
      <div class="stock-price" :class="priceChangeClass">
        <span class="price">{{ formatPrice(detail.close_price) }}</span>
        <span class="change" v-if="detail.change_pct != null">
          {{ formatChange(detail.close_price, detail.pre_close) }}
          <span class="pct">({{ formatPct(detail.change_pct) }}%)</span>
        </span>
      </div>
    </div>

    <!-- 快速查股 -->
    <div class="quick-search">
      <el-input
        v-model="quickCode"
        placeholder="输入股票代码快速切换"
        style="width: 180px"
        size="small"
        clearable
        @keyup.enter="goStock"
      >
        <template #append>
          <el-button @click="goStock">查询</el-button>
        </template>
      </el-input>
    </div>

    <!-- 周期标签 + 日期选择 -->
    <div class="chart-toolbar">
      <el-radio-group v-model="period" size="small">
        <el-radio-button value="day">日K</el-radio-button>
        <el-radio-button value="week">周K</el-radio-button>
        <el-radio-button value="month">月K</el-radio-button>
      </el-radio-group>
      <span class="adj-note">前复权</span>
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="开始"
        end-placeholder="结束"
        size="small"
        value-format="YYYY-MM-DD"
        :shortcuts="dateShortcuts"
        style="margin-left: 16px"
      />
      <el-button size="small" type="primary" @click="loadData" style="margin-left: 8px">刷新</el-button>
      <el-button size="small" @click="loadRealtime" :loading="realtimeLoading">获取实时</el-button>
    </div>

    <!-- 主内容区：K线图 + 右侧信息 -->
    <div class="chart-content">
      <div class="chart-main">
        <div ref="chartRef" class="kline-chart" />
        <div class="ma-legend" v-if="klineData.length">
          <span v-for="ma in maList" :key="ma.key" :style="{ color: ma.color }">MA{{ ma.period }}: {{ ma.value }}</span>
        </div>
      </div>
      <div class="info-panel">
        <div class="info-title">盘口数据</div>
        <div class="info-row"><span class="label">今开</span><span class="val">{{ formatPrice(detail.open_price) }}</span></div>
        <div class="info-row"><span class="label">昨收</span><span class="val">{{ formatPrice(detail.pre_close) }}</span></div>
        <div class="info-row"><span class="label">最高</span><span class="val highlight">{{ formatPrice(detail.high_price) }}</span></div>
        <div class="info-row"><span class="label">最低</span><span class="val highlight">{{ formatPrice(detail.low_price) }}</span></div>
        <div class="info-row"><span class="label">成交量</span><span class="val">{{ formatVolume(detail.volume) }}</span></div>
        <div class="info-row"><span class="label">成交额</span><span class="val">{{ formatAmount(detail.amount) }}</span></div>
        <div class="info-row"><span class="label">换手率</span><span class="val">{{ detail.turnover_rate != null ? detail.turnover_rate.toFixed(2) + '%' : '--' }}</span></div>
        <div class="info-row"><span class="label">涨跌幅</span><span class="val" :class="priceChangeClass">{{ formatPct(detail.change_pct) }}{{ detail.change_pct != null ? '%' : '' }}</span></div>
        <div class="info-row" v-if="detail.pe_ratio"><span class="label">市盈率</span><span class="val">{{ detail.pe_ratio }}</span></div>
        <div class="info-row" v-if="detail.pb_ratio"><span class="label">市净率</span><span class="val">{{ detail.pb_ratio }}</span></div>
      </div>
    </div>

    <!-- 历史数据表 -->
    <div class="history-section">
      <div class="section-title">近期行情</div>
      <el-table :data="klineData.slice(-20).reverse()" size="small" stripe max-height="320" class="history-table">
        <el-table-column prop="date" label="日期" min-width="100" />
        <el-table-column prop="open" label="开盘" min-width="85" align="right">
          <template #default="{ row }">{{ row.open != null ? row.open.toFixed(2) : '--' }}</template>
        </el-table-column>
        <el-table-column prop="high" label="最高" min-width="85" align="right">
          <template #default="{ row }">{{ row.high != null ? row.high.toFixed(2) : '--' }}</template>
        </el-table-column>
        <el-table-column prop="low" label="最低" min-width="85" align="right">
          <template #default="{ row }">{{ row.low != null ? row.low.toFixed(2) : '--' }}</template>
        </el-table-column>
        <el-table-column prop="close" label="收盘" min-width="85" align="right">
          <template #default="{ row }">{{ row.close != null ? row.close.toFixed(2) : '--' }}</template>
        </el-table-column>
        <el-table-column prop="change_pct" label="涨跌幅" min-width="85" align="right">
          <template #default="{ row }">
            <span :class="row.change_pct != null && row.change_pct >= 0 ? 'text-up' : 'text-down'">
              {{ row.change_pct != null ? row.change_pct.toFixed(2) + '%' : '--' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="volume" label="成交量" min-width="95" align="right">
          <template #default="{ row }">{{ formatVolume(row.volume) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <div class="analysis-section">
      <div class="section-title">持仓技术分析（次日建议）</div>
      <el-form inline>
        <el-form-item label="持仓股数">
          <el-input-number v-model="analysisForm.holdingQty" :min="0" :step="100" />
        </el-form-item>
        <el-form-item label="持仓成本">
          <el-input-number v-model="analysisForm.avgCost" :min="0" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="风险偏好">
          <el-select v-model="analysisForm.riskProfile" style="width: 120px">
            <el-option label="保守" value="conservative" />
            <el-option label="均衡" value="balanced" />
            <el-option label="激进" value="aggressive" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="analysisLoading" @click="analyzeStock">分析</el-button>
        </el-form-item>
      </el-form>
      <el-descriptions :column="3" border v-if="analysisResult">
        <el-descriptions-item label="建议动作">{{ analysisResult.action }}</el-descriptions-item>
        <el-descriptions-item label="目标仓位">{{ analysisResult.target_position }}</el-descriptions-item>
        <el-descriptions-item label="置信度">{{ analysisResult.confidence }}</el-descriptions-item>
        <el-descriptions-item label="止损位">{{ analysisResult.risk_lines?.stop_loss ?? '--' }}</el-descriptions-item>
        <el-descriptions-item label="止盈位">{{ analysisResult.risk_lines?.take_profit ?? '--' }}</el-descriptions-item>
        <el-descriptions-item label="关键支撑/阻力">
          {{ analysisResult.risk_lines?.support ?? '--' }} / {{ analysisResult.risk_lines?.resistance ?? '--' }}
        </el-descriptions-item>
        <el-descriptions-item label="分析理由" :span="3">
          <ul class="reasons">
            <li v-for="(r, idx) in analysisResult.reasons || []" :key="idx">{{ r }}</li>
          </ul>
        </el-descriptions-item>
      </el-descriptions>
    </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { echarts, CHART_THEME, CHART_C1, CHART_C2, CHART_C3, CHART_C4 } from '@/utils/echartsTheme'
import type { EChartsOption } from 'echarts'
import { marketApi, type StockDetail, type KLineItem, type StockAnalysisResult } from '@/api/market'
import dayjs from 'dayjs'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const loading = ref(false)
const realtimeLoading = ref(false)
const detail = ref<Partial<StockDetail>>({})
const klineData = ref<KLineItem[]>([])

/** 按周期重采样：日K原样；周/月K聚合 OHLCV（open=首、high=max、low=min、close=末、volume=和）。 */
const displayKline = computed(() => {
  const data = klineData.value
  if (period.value === 'day' || data.length === 0) return data
  const keyFn = period.value === 'week'
    ? (d: string) => {
        const dt = new Date(d + 'T00:00:00')
        const start = new Date(dt.getFullYear(), 0, 1)
        const week = Math.ceil((((dt.getTime() - start.getTime()) / 86400000) + start.getDay() + 1) / 7)
        return `${dt.getFullYear()}-W${String(week).padStart(2, '0')}`
      }
    : (d: string) => d.slice(0, 7)
  const map = new Map<string, KLineItem>()
  for (const bar of data) {
    const k = keyFn(bar.date)
    const agg = map.get(k)
    if (!agg) {
      map.set(k, { ...bar, volume: bar.volume ?? 0 })
    } else {
      agg.high = Math.max(agg.high, bar.high)
      agg.low = Math.min(agg.low, bar.low)
      agg.close = bar.close
      agg.volume = (agg.volume ?? 0) + (bar.volume ?? 0)
    }
  }
  return [...map.values()]
})
const dateRange = ref<[string, string] | null>(null)
const period = ref('day')
const quickCode = ref('')
const analysisLoading = ref(false)
const analysisResult = ref<StockAnalysisResult | null>(null)
const analysisForm = ref({
  holdingQty: 0,
  avgCost: 0,
  riskProfile: 'balanced' as 'conservative' | 'balanced' | 'aggressive'
})
const chartRef = ref<HTMLElement>()
let chart: echarts.ECharts | null = null

const dateShortcuts = [
  { label: '近1周', value: () => [dayjs().subtract(1, 'week').toDate(), dayjs().toDate()] },
  { label: '近1月', value: () => [dayjs().subtract(1, 'month').toDate(), dayjs().toDate()] },
  { label: '近3月', value: () => [dayjs().subtract(3, 'month').toDate(), dayjs().toDate()] },
  { label: '近半年', value: () => [dayjs().subtract(6, 'month').toDate(), dayjs().toDate()] },
  { label: '近1年', value: () => [dayjs().subtract(1, 'year').toDate(), dayjs().toDate()] }
]

const priceChangeClass = computed(() => {
  const pct = detail.value.change_pct
  if (pct == null) return ''
  return pct >= 0 ? 'up' : 'down'
})

// MA 均线计算
const maPeriods = [5, 10, 20, 30]
const maColors: Record<number, string> = { 5: CHART_C3, 10: CHART_C2, 20: CHART_C1, 30: CHART_C4 }
const maList = computed(() => {
  const data = displayKline.value
  if (data.length === 0) return []
  return maPeriods.map(period => {
    const slice = data.slice(-period)
    const sum = slice.reduce((a, b) => a + (b.close ?? 0), 0)
    const avg = slice.length ? sum / slice.length : 0
    return { key: period, period, value: avg.toFixed(2), color: maColors[period] }
  })
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
  const sign = ch >= 0 ? '+' : ''
  return sign + ch.toFixed(2)
}
function formatVolume(v: number | undefined) {
  if (v == null) return '--'
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(2) + '万'
  return String(v)
}
function formatAmount(v: number | undefined) {
  if (v == null) return '--'
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(2) + '万'
  return String(v)
}

async function loadDetail() {
  const code = route.query.code as string
  if (!code) return
  try {
    const res = await marketApi.getStockDetail(code, route.query.exchange as string)
    detail.value = res ?? {}
  } catch {
    toast.error('加载股票详情失败')
  }
}

async function loadRealtime() {
  const code = route.query.code as string
  if (!code) return
  realtimeLoading.value = true
  try {
    const data = await marketApi.getRealtimeQuote(code, route.query.exchange as string)
    if (data && Object.keys(data).length > 0) {
      detail.value = { ...detail.value, ...data }
    }
  } catch {
    toast.warning('实时行情获取失败')
  } finally {
    realtimeLoading.value = false
  }
}

async function loadKline() {
  const code = route.query.code as string
  if (!code) return
  const [start, end] = dateRange.value || [
    dayjs().subtract(1, 'year').format('YYYY-MM-DD'),
    dayjs().format('YYYY-MM-DD')
  ]
  try {
    const data = await marketApi.getKline(code, {
      exchange: route.query.exchange as string,
      startDate: start,
      endDate: end,
      limit: 500
    })
    klineData.value = Array.isArray(data) ? data : []
    renderChart()
  } catch {
    klineData.value = []
    toast.error('K线数据加载失败')
  }
}

async function loadData() {
  loading.value = true
  try {
    await Promise.all([loadDetail(), loadKline()])
    loadRealtime()
  } finally {
    loading.value = false
  }
}

async function analyzeStock() {
  const code = route.query.code as string
  if (!code) return
  analysisLoading.value = true
  try {
    analysisResult.value = await marketApi.analyzeStock({
      stockCode: code,
      exchange: route.query.exchange as string,
      holdingQty: analysisForm.value.holdingQty,
      avgCost: analysisForm.value.avgCost,
      riskProfile: analysisForm.value.riskProfile
    }) ?? null
  } finally {
    analysisLoading.value = false
  }
}

function renderChart() {
  const data = displayKline.value
  if (!chartRef.value || data.length === 0) return

  if (!chart) chart = echarts.init(chartRef.value, CHART_THEME)

  const dates = data.map(d => d.date)
  const ohlc = data.map(d => [d.open, d.close, d.low, d.high])
  const volumes = data.map(d => d.volume ?? 0)
  const closes = data.map(d => d.close ?? 0)

  // 计算 MA
  const maSeries = maPeriods.map(period => {
    const vals: (number | string)[] = []
    for (let i = 0; i < data.length; i++) {
      const slice = closes.slice(Math.max(0, i - period + 1), i + 1)
      vals.push(slice.length ? slice.reduce((a, b) => a + b, 0) / slice.length : '-')
    }
    return { period, vals, color: maColors[period] }
  })

  const option: EChartsOption = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params: any) => {
        const i = params[0]?.dataIndex ?? -1
        if (i < 0 || !data[i]) return ''
        const d = data[i]
        const maStr = maSeries.map(s => `MA${s.period}: ${typeof s.vals[i] === 'number' ? (s.vals[i] as number).toFixed(2) : '-'}`).join('<br/>')
        return `${d.date}<br/>开: ${d.open?.toFixed(2)} 高: ${d.high?.toFixed(2)} 低: ${d.low?.toFixed(2)} 收: ${d.close?.toFixed(2)}<br/>量: ${formatVolume(d.volume)}<br/>${maStr}`
      }
    },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: 60, right: 50, top: 24, height: '52%' },
      { left: 60, right: 50, top: '70%', height: '18%' }
    ],
    xAxis: [
      { type: 'category', data: dates, gridIndex: 0 },
      { type: 'category', data: dates, gridIndex: 1 }
    ],
    yAxis: [
      { type: 'value', gridIndex: 0, scale: true },
      { type: 'value', gridIndex: 1, splitLine: { show: false } }
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: Math.max(0, 100 - 25000 / data.length), end: 100 },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 8, height: 20, start: Math.max(0, 100 - 25000 / data.length), end: 100 }
    ],
    series: [
      {
        type: 'candlestick',
        data: ohlc,
        xAxisIndex: 0,
        yAxisIndex: 0
      },
      ...maSeries.map(s => ({
        type: 'line' as const,
        data: s.vals,
        xAxisIndex: 0,
        yAxisIndex: 0,
        symbol: 'none',
        lineStyle: { color: s.color, width: 1.5 },
        smooth: false
      })),
      {
        type: 'bar',
        data: volumes.map((v, i) => ({
          value: v,
          itemStyle: { color: (closes[i] >= (data[i]?.open ?? 0) ? '#f23645' : '#089981') + '40' }
        })),
        xAxisIndex: 1,
        yAxisIndex: 1
      }
    ]
  }
  chart.setOption(option, true)
}

const onResize = () => chart?.resize()

onMounted(() => {
  quickCode.value = (route.query.code as string) || ''
  dateRange.value = [dayjs().subtract(1, 'year').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')]
  // resize 监听先于 loadData 注册，确保 chart 创建后窗口缩放即生效
  window.addEventListener('resize', onResize)
  if (route.query.code) loadData()
})

function goStock() {
  const code = quickCode.value?.trim()
  if (code && route.query.code !== code) {
    router.push({ path: '/market/stock', query: { code } })
  }
}

watch(() => route.query.code, (code) => {
  quickCode.value = (code as string) || ''
  if (code) {
    analysisResult.value = null
    loadData()
  }
})
watch(period, () => {
  renderChart()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.stock-detail {
  background: var(--surface-2);
  border-radius: 8px;
  padding: 20px;
  min-height: 600px;
  border: 1px solid var(--glass-border);
}
.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--glass-border);
}
.stock-title { display: flex; align-items: center; gap: 12px; }
.stock-title .name { font-size: 22px; font-weight: 600; color: var(--text-primary); }
.stock-title .code { font-size: 14px; color: var(--text-secondary); }
.exchange-tag { margin-left: 4px; }
.stock-price { font-size: 28px; font-weight: 600; }
.stock-price.up { color: var(--up); }
.stock-price.down { color: var(--down); }
.stock-price .change { font-size: 16px; margin-left: 12px; }
.stock-price .pct { font-size: 14px; opacity: 0.9; }

.quick-search { margin-bottom: 12px; }
.chart-toolbar { margin-bottom: 16px; display: flex; align-items: center; }
.adj-note { margin-left: 10px; font-size: 12px; color: var(--text-muted); }

.chart-content { display: flex; gap: 24px; }
.chart-main { flex: 1; min-width: 0; position: relative; }
.kline-chart { height: 420px; }
.ma-legend { position: absolute; top: 8px; left: 60px; font-size: 12px; display: flex; gap: 16px; }
.ma-legend span { opacity: 0.9; }

.info-panel {
  width: 200px;
  flex-shrink: 0;
  background: var(--surface-2);
  border-radius: 8px;
  padding: 16px;
  height: fit-content;
  border: 1px solid var(--glass-border);
}
.info-title { font-size: 14px; font-weight: 600; color: var(--text-primary); margin-bottom: 12px; }
.info-row { display: flex; justify-content: space-between; padding: 6px 0; font-size: 13px; }
.info-row .label { color: var(--text-secondary); }
.info-row .val { color: var(--text-primary); }
.info-row .val.highlight { color: var(--text-primary); }
.info-row .val.up { color: var(--up); }
.info-row .val.down { color: var(--down); }

.history-section { margin-top: 24px; }
.analysis-section { margin-top: 24px; }
.section-title { font-size: 14px; font-weight: 600; color: var(--text-primary); margin-bottom: 12px; }
.history-table { background: transparent; border-radius: 8px; overflow: hidden; }
.text-up { color: var(--up); }
.text-down { color: var(--down); }
.reasons { margin: 0; padding-left: 18px; color: var(--text-secondary); }
</style>
