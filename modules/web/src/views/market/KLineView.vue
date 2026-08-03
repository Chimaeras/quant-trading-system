<template>
  <el-card shadow="hover">
    <template #header>
      <span>{{ route.query.name || route.query.code || 'K线图' }}</span>
      <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" size="small" style="float: right" @change="loadData" />
      <el-button v-if="route.query.code" size="small" style="margin-right: 8px" :loading="realtimeLoading" @click="loadRealtime">获取实时</el-button>
    </template>
    <div v-if="realtime" class="realtime-bar">
      <span class="label">实时</span>
      <span :class="['price', (realtime.change_pct ?? 0) >= 0 ? 'up' : 'down']">
        {{ realtime.close_price != null ? realtime.close_price.toFixed(2) : '--' }}
      </span>
      <span v-if="realtime.change_pct != null" :class="(realtime.change_pct >= 0 ? 'up' : 'down')">
        {{ realtime.change_pct >= 0 ? '+' : '' }}{{ realtime.change_pct.toFixed(2) }}%
      </span>
    </div>
    <div ref="chartRef" style="height: 500px" />
    <div class="legend">📊 {{ dataSource === 'backend' ? '数据来源：后端 MySQL 行情' : '数据来源：本地 IndexedDB（后端不可用时降级）' }} · 点击「获取实时」可拉取最新价</div>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { echarts, CHART_THEME, CHART_C1 } from '@/utils/echartsTheme'
import { dataApi } from '@/api/data'
import { marketApi } from '@/api/market'
import type { KLineItem } from '@/api/market'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const toast = useToast()
const chartRef = ref<HTMLElement>()
const dateRange = ref<[Date, Date] | null>(null)
const realtime = ref<{ close_price?: number; change_pct?: number } | null>(null)
const realtimeLoading = ref(false)
const dataSource = ref<'backend' | 'local'>('backend')
let chart: echarts.ECharts | null = null

async function loadRealtime() {
  const code = route.query.code as string
  if (!code) return
  realtimeLoading.value = true
  try {
    const res = await marketApi.getRealtimeQuote(code, route.query.exchange as string)
    const d = (res as { data?: { close_price?: number; change_pct?: number } })?.data
    realtime.value = d && (d.close_price != null || d.change_pct != null) ? d : null
  } catch {
    realtime.value = null
    toast.warning('实时行情拉取失败')
  } finally {
    realtimeLoading.value = false
  }
}

const loadData = async () => {
  const code = route.query.code as string
  if (!code) return
  const [start, end] = dateRange.value || [new Date(Date.now() - 365 * 24 * 3600 * 1000), new Date()]
  const startDate = start.toISOString().slice(0, 10)
  const endDate = end.toISOString().slice(0, 10)
  // 优先真实后端行情，失败回退本地 IndexedDB（并标注来源，避免误把离线/Mock 当线上数据）
  let data: KLineItem[] = []
  try {
    const res = await marketApi.getKline(code, {
      exchange: route.query.exchange as string,
      startDate,
      endDate
    })
    data = ((res as { data?: KLineItem[] })?.data ?? []) as KLineItem[]
    dataSource.value = 'backend'
  } catch {
    data = (await dataApi.getKLine(code, startDate, endDate)) as KLineItem[]
    dataSource.value = 'local'
  }
  if (!Array.isArray(data) || data.length === 0) return
  const dates = data.map((d: { date: string }) => d.date)
  const ohlc = data.map((d: { open: number; high: number; low: number; close: number }) => [d.open, d.close, d.low, d.high])
  const volumes = data.map((d: { volume?: number }) => d.volume ?? 0)
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value, CHART_THEME)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: [{ type: 'category', data: dates, gridIndex: 0 }, { type: 'category', data: dates, gridIndex: 1 }],
    yAxis: [
      { type: 'value', gridIndex: 0, splitLine: { show: false } },
      { type: 'value', gridIndex: 1, splitLine: { show: false } }
    ],
    grid: [{ left: '10%', right: '8%', height: '60%' }, { left: '10%', right: '8%', top: '75%', height: '18%' }],
    series: [
      { type: 'candlestick', data: ohlc, xAxisIndex: 0, yAxisIndex: 0 },
      { type: 'bar', data: volumes, xAxisIndex: 1, yAxisIndex: 1, itemStyle: { color: CHART_C1, opacity: 0.6 } }
    ]
  })
}

onMounted(() => {
  if (!dateRange.value) dateRange.value = [new Date(Date.now() - 250 * 24 * 3600 * 1000), new Date()]
  loadData()
  if (route.query.code) loadRealtime()
})
watch(() => route.query.code, (code) => {
  loadData()
  if (code) loadRealtime()
})
</script>

<style scoped>
.realtime-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  font-size: 14px;
}
.realtime-bar .label { color: var(--text-dim); }
.realtime-bar .price { font-weight: 600; font-size: 18px; }
.realtime-bar .up { color: var(--up); }
.realtime-bar .down { color: var(--down); }
.legend { margin-top: 12px; color: var(--text-secondary); font-size: 12px; }
</style>
