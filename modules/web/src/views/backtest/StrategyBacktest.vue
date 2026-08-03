<template>
  <el-card shadow="hover" class="terminal-page">
    <template #header><span>经典策略回测（MA / BOLL / MACD）</span></template>

    <el-form inline>
      <el-form-item label="策略">
        <el-select v-model="form.strategyType" style="width: 140px">
          <el-option v-for="s in strategies" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="股票代码">
        <el-input v-model="form.stockCode" placeholder="000001" style="width: 120px" />
      </el-form-item>
      <el-form-item label="交易所">
        <el-select v-model="form.exchangeType" style="width: 100px">
          <el-option label="SZSE" value="SZSE" />
          <el-option label="SSE" value="SSE" />
        </el-select>
      </el-form-item>
      <el-form-item label="起始">
        <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="结束">
        <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="初始资金">
        <el-input-number v-model="form.initialCapital" :min="10000" :step="10000" />
      </el-form-item>
      <el-form-item v-if="form.strategyType === 'MA_CROSS'" label="短/长均线">
        <el-input-number v-model="form.shortPeriod" :min="2" :max="60" /> /
        <el-input-number v-model="form.longPeriod" :min="5" :max="250" />
      </el-form-item>
      <el-form-item v-if="form.strategyType === 'BOLL'" label="布林带周期/倍数">
        <el-input-number v-model="form.bollPeriod" :min="5" :max="120" /> /
        <el-input-number v-model="form.bollStdDev" :min="0.5" :max="4" :step="0.1" />
      </el-form-item>
      <el-form-item v-if="form.strategyType === 'MACD'" label="MACD 快/慢线">
        <el-input-number v-model="form.macdFast" :min="2" :max="60" /> /
        <el-input-number v-model="form.macdSlow" :min="10" :max="120" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="run">执行回测</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="errorMsg" type="error" show-icon class="run-alert" :title="errorMsg" />

    <template v-if="result">
      <div class="meta">
        <span>{{ result.stockName }} ({{ result.stockCode }})</span>
        <span>策略：{{ result.strategyType }}</span>
        <span>区间：{{ result.startDate }} ~ {{ result.endDate }}</span>
      </div>
      <el-alert
        v-if="result.executionAssumptions"
        type="info"
        :closable="false"
        class="run-alert assumptions-alert"
        title="成交假设"
      >
        <div class="assumptions">
          <span v-for="(value, key) in result.executionAssumptions" :key="String(key)">
            {{ formatAssumptionKey(String(key)) }}：{{ formatAssumptionValue(value) }}
          </span>
        </div>
      </el-alert>
      <div class="stats-grid">
        <div class="stat-card"><div class="label">总收益率</div><div :class="pctClass(result.totalReturn)">{{ fmtPct(result.totalReturn) }}</div></div>
        <div class="stat-card"><div class="label">最大回撤</div><div class="text-danger">{{ fmtPct(result.maxDrawdown) }}</div></div>
        <div class="stat-card"><div class="label">夏普比率</div><div>{{ result.sharpeRatio?.toFixed(2) }}</div></div>
        <div class="stat-card"><div class="label">胜率</div><div>{{ fmtPct(result.winRate) }}</div></div>
        <div class="stat-card"><div class="label">最终资金</div><div>{{ result.finalCapital?.toLocaleString() }}</div></div>
        <div class="stat-card"><div class="label">交易次数</div><div>{{ result.totalTrades }}</div></div>
      </div>

      <div ref="chartRef" class="chart-box" />

      <div class="section-title">成交明细</div>
      <el-table :data="result.trades" stripe max-height="320">
        <el-table-column prop="trade_date" label="日期" width="120" />
        <el-table-column prop="action" label="方向" width="80" />
        <el-table-column prop="price" label="价格" />
        <el-table-column prop="volume" label="数量" />
        <el-table-column prop="equity_after" label="权益" />
      </el-table>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import * as echarts from 'echarts'
import { backtestApi, type StrategyBacktestResponse } from '@/api/backtest'

const running = ref(false)
const errorMsg = ref('')
const result = ref<StrategyBacktestResponse | null>(null)
const strategies = ref<string[]>(['MA_CROSS', 'BOLL', 'MACD'])
const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null

const form = reactive({
  strategyType: 'MA_CROSS',
  stockCode: '000001',
  exchangeType: 'SZSE',
  startDate: dayjs().subtract(1, 'year').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD'),
  initialCapital: 100000,
  shortPeriod: 5,
  longPeriod: 20,
  bollPeriod: 20,
  bollStdDev: 2.0,
  macdFast: 12,
  macdSlow: 26
})

function fmtPct(v?: number) {
  if (v == null) return '--'
  return `${(v * 100).toFixed(2)}%`
}

function pctClass(v?: number) {
  return v != null && v >= 0 ? 'text-success' : 'text-danger'
}

const assumptionLabels: Record<string, string> = {
  signal_timing: '信号时点',
  entry: '买入成交',
  exit: '卖出成交',
  commission_rate: '佣金费率',
  stamp_duty_rate: '印花税率',
  slippage_rate_each_side: '单边滑点',
  t_plus_one_sell: 'T+1 卖出约束',
  limit_up_entry_blocked: '涨停开盘不买',
  limit_down_exit_blocked: '跌停开盘不卖',
  limit_pct: '涨跌停幅度',
  limit_pct_source: '幅度来源',
  return_type: '收益口径'
}

function formatAssumptionKey(key: string) {
  return assumptionLabels[key] ?? key
}

function formatAssumptionValue(value: unknown) {
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'number' && value > 0 && value < 1) return `${(value * 100).toFixed(2)}%`
  return String(value)
}

async function run() {
  running.value = true
  errorMsg.value = ''
  try {
    result.value = await backtestApi.runStrategyBacktest({ ...form })
    await nextTick()
    renderChart()
  } catch (e: any) {
    errorMsg.value = e?.message || '回测失败'
  } finally {
    running.value = false
  }
}

function renderChart() {
  if (!chartRef.value || !result.value?.equityCurve?.length) return
  chart?.dispose()
  chart = echarts.init(chartRef.value)
  const dates = result.value.equityCurve.map((d) => String(d.trade_date))
  const equity = result.value.equityCurve.map((d) => Number(d.equity))
  chart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value', scale: true },
    series: [{ type: 'line', data: equity, smooth: true, areaStyle: { opacity: 0.08 } }]
  })
}

onMounted(async () => {
  try {
    strategies.value = await backtestApi.getStrategyTypes()
  } catch {
    // keep defaults
  }
})

onBeforeUnmount(() => {
  chart?.dispose()
})
</script>

<style scoped>
.meta { display: flex; gap: 16px; flex-wrap: wrap; margin: 12px 0; color: var(--text-secondary); font-size: 13px; }
.stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; margin-bottom: 16px; }
.stat-card { padding: 12px; border-radius: 8px; background: rgba(9, 18, 34, 0.55); border: 1px solid rgba(145, 196, 255, 0.15); }
.stat-card .label { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
.text-success { color: #ef5350; }
.text-danger { color: #26a69a; }
.chart-box { height: 280px; margin-bottom: 16px; }
.section-title { margin: 12px 0 8px; font-weight: 600; }
.run-alert { margin-bottom: 12px; }
.assumptions { display: flex; flex-wrap: wrap; gap: 8px 16px; font-size: 12px; }
.assumptions-alert :deep(.el-alert__content) { width: 100%; }
</style>
