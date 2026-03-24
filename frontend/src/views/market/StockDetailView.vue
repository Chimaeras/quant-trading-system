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
      </el-radio-group>
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
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { marketApi, type StockDetail, type KLineItem } from '@/api/market'
import dayjs from 'dayjs'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const realtimeLoading = ref(false)
const detail = ref<Partial<StockDetail>>({})
const klineData = ref<KLineItem[]>([])
const dateRange = ref<[string, string] | null>(null)
const period = ref('day')
const quickCode = ref('')
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
const maColors: Record<number, string> = { 5: '#f5a623', 10: '#2e7d32', 20: '#1976d2', 30: '#7b1fa2' }
const maList = computed(() => {
  const data = klineData.value
  if (data.length === 0) return []
  const last = data[data.length - 1]
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
    detail.value = (res as { data?: StockDetail }).data ?? {}
  } catch (e) {
    console.error('loadDetail', e)
  }
}

async function loadRealtime() {
  const code = route.query.code as string
  if (!code) return
  realtimeLoading.value = true
  try {
    const res = await marketApi.getRealtimeQuote(code, route.query.exchange as string)
    const data = (res as { data?: StockDetail }).data
    if (data && Object.keys(data).length > 0) {
      detail.value = { ...detail.value, ...data }
    }
  } catch (e) {
    console.error('loadRealtime', e)
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
    console.error('loadKline', e)
    klineData.value = []
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

function renderChart() {
  const data = klineData.value
  if (!chartRef.value || data.length === 0) return

  if (!chart) chart = echarts.init(chartRef.value)

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

  const option: echarts.EChartsOption = {
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
      { type: 'category', data: dates, gridIndex: 0, axisLine: { lineStyle: { color: '#444' } }, axisLabel: { color: '#999' } },
      { type: 'category', data: dates, gridIndex: 1, axisLine: { lineStyle: { color: '#444' } }, axisLabel: { color: '#999' } }
    ],
    yAxis: [
      { type: 'value', gridIndex: 0, splitLine: { lineStyle: { color: '#2a2b2c' } }, axisLabel: { color: '#999' }, scale: true },
      { type: 'value', gridIndex: 1, splitLine: { show: false }, axisLabel: { color: '#999' } }
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
        yAxisIndex: 0,
        itemStyle: {
          color: '#ef5350',
          color0: '#26a69a',
          borderColor: '#ef5350',
          borderColor0: '#26a69a'
        }
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
          itemStyle: { color: (closes[i] >= (data[i]?.open ?? 0) ? '#ef5350' : '#26a69a') + '40' }
        })),
        xAxisIndex: 1,
        yAxisIndex: 1
      }
    ]
  }
  chart.setOption(option, true)
}

onMounted(() => {
  quickCode.value = (route.query.code as string) || ''
  dateRange.value = [dayjs().subtract(1, 'year').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')]
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
  if (code) loadData()
})
watch(() => window.innerWidth, () => chart?.resize())
</script>

<style scoped>
.stock-detail {
  background: #ffffff;
  border-radius: 8px;
  padding: 20px;
  min-height: 600px;
  border: 1px solid #e2e8f0;
}
.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e2e8f0;
}
.stock-title { display: flex; align-items: center; gap: 12px; }
.stock-title .name { font-size: 22px; font-weight: 600; color: #1e293b; }
.stock-title .code { font-size: 14px; color: #64748b; }
.exchange-tag { margin-left: 4px; }
.stock-price { font-size: 28px; font-weight: 600; }
.stock-price.up { color: #ef5350; }
.stock-price.down { color: #26a69a; }
.stock-price .change { font-size: 16px; margin-left: 12px; }
.stock-price .pct { font-size: 14px; opacity: 0.9; }

.quick-search { margin-bottom: 12px; }
.chart-toolbar { margin-bottom: 16px; display: flex; align-items: center; }

.chart-content { display: flex; gap: 24px; }
.chart-main { flex: 1; min-width: 0; position: relative; }
.kline-chart { height: 420px; }
.ma-legend { position: absolute; top: 8px; left: 60px; font-size: 12px; display: flex; gap: 16px; }
.ma-legend span { opacity: 0.9; }

.info-panel {
  width: 200px;
  flex-shrink: 0;
  background: #f8fafc;
  border-radius: 8px;
  padding: 16px;
  height: fit-content;
  border: 1px solid #e2e8f0;
}
.info-title { font-size: 14px; font-weight: 600; color: #1e293b; margin-bottom: 12px; }
.info-row { display: flex; justify-content: space-between; padding: 6px 0; font-size: 13px; }
.info-row .label { color: #64748b; }
.info-row .val { color: #334155; }
.info-row .val.highlight { color: #1e293b; }
.info-row .val.up { color: #ef5350; }
.info-row .val.down { color: #26a69a; }

.history-section { margin-top: 24px; }
.section-title { font-size: 14px; font-weight: 600; color: #1e293b; margin-bottom: 12px; }
.history-table { background: #ffffff; border-radius: 8px; overflow: hidden; }
.text-up { color: #ef5350; }
.text-down { color: #26a69a; }
</style>
