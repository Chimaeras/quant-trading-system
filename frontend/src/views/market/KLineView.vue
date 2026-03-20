<template>
  <el-card shadow="hover">
    <template #header>
      <span>{{ route.query.name || route.query.code || 'K线图' }}</span>
      <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" size="small" style="float: right" @change="loadData" />
    </template>
    <div ref="chartRef" style="height: 500px" />
    <div class="legend">📊 离线K线 · 基于本地历史数据</div>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { dataApi } from '@/api/data'

const route = useRoute()
const chartRef = ref<HTMLElement>()
const dateRange = ref<[Date, Date] | null>(null)
let chart: echarts.ECharts | null = null

const loadData = async () => {
  const code = route.query.code as string
  if (!code) return
  const [start, end] = dateRange.value || [new Date(Date.now() - 365 * 24 * 3600 * 1000), new Date()]
  const data = await dataApi.getKLine(code, start.toISOString().slice(0, 10), end.toISOString().slice(0, 10))
  if (!Array.isArray(data) || data.length === 0) return
  const dates = data.map((d: { date: string }) => d.date)
  const ohlc = data.map((d: { open: number; high: number; low: number; close: number }) => [d.open, d.close, d.low, d.high])
  const volumes = data.map((d: { volume?: number }) => d.volume ?? 0)
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value)
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
      { type: 'bar', data: volumes, xAxisIndex: 1, yAxisIndex: 1, itemStyle: { color: '#5470c6' } }
    ]
  })
}

onMounted(() => {
  if (!dateRange.value) dateRange.value = [new Date(Date.now() - 250 * 24 * 3600 * 1000), new Date()]
  loadData()
})
watch(() => route.query.code, loadData)
</script>

<style scoped>
.legend { margin-top: 12px; color: #888; font-size: 12px; }
</style>
