<template>
  <el-card shadow="hover" class="terminal-page">
    <template #header>
      <div class="header-row">
        <span>打分策略回测系统</span>
      </div>
    </template>

    <!-- 参数配置 -->
    <div class="section-title">回测参数配置</div>
    <el-form inline>
      <el-form-item label="策略类型">
        <el-select v-model="form.strategyType" placeholder="全部策略" style="width: 200px" :loading="strategiesLoading" clearable>
          <el-option label="全部策略" value="" />
          <el-option v-for="s in strategies" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="起始日期">
        <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="结束日期">
        <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="每日选取TopN">
        <el-input-number v-model="form.topN" :min="1" :max="100" :step="1" />
      </el-form-item>
    </el-form>
    <el-form inline class="holding-form">
      <el-form-item label="持仓周期(交易日)">
        <el-checkbox-group v-model="form.holdingDays">
          <el-checkbox :value="1">T+1</el-checkbox>
          <el-checkbox :value="3">T+3</el-checkbox>
          <el-checkbox :value="5">T+5</el-checkbox>
          <el-checkbox :value="10">T+10</el-checkbox>
          <el-checkbox :value="20">T+20</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="runBacktest">执行回测</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="run-alert"
      title="收益按信号后下一交易日开盘买入、持有期末收盘卖出计算，并扣除双边佣金、卖出税费和双边滑点。"
    />

    <el-alert v-if="errorMsg" type="error" show-icon :closable="true" class="run-alert" @close="errorMsg = ''">
      {{ errorMsg }}
    </el-alert>

    <!-- 回测结果 -->
    <template v-if="response">
      <div class="result-meta">
        <span>策略：{{ response.strategyType }}</span>
        <span>日期：{{ response.startDate }} ~ {{ response.endDate }}</span>
        <span>覆盖交易日：{{ response.totalTradingDays }} 天</span>
        <span>每日前 {{ response.topN }} 只</span>
      </div>

      <!-- 汇总统计卡片 -->
      <div class="section-title">各持仓周期汇总</div>
      <div class="stats-grid">
        <div v-for="(stat, day) in response.holdingPeriodStats" :key="day" class="stat-card glass">
          <div class="stat-header">T+{{ day }}</div>
          <div class="stat-body">
            <div class="stat-item">
              <span class="stat-label">胜率</span>
              <span class="stat-value" :class="stat.winRate >= 0.5 ? 'text-success' : 'text-danger'">
                {{ (stat.winRate * 100).toFixed(1) }}%
              </span>
            </div>
            <div class="stat-item">
              <span class="stat-label">平均收益</span>
              <span class="stat-value" :class="stat.avgReturn >= 0 ? 'text-success' : 'text-danger'">
                {{ (stat.avgReturn * 100).toFixed(2) }}%
              </span>
            </div>
            <div class="stat-item">
              <span class="stat-label">中位数收益</span>
              <span class="stat-value" :class="stat.medianReturn >= 0 ? 'text-success' : 'text-danger'">
                {{ (stat.medianReturn * 100).toFixed(2) }}%
              </span>
            </div>
            <div class="stat-item">
              <span class="stat-label">最大收益</span>
              <span class="stat-value text-success">{{ (stat.maxReturn * 100).toFixed(2) }}%</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">最小收益</span>
              <span class="stat-value text-danger">{{ (stat.minReturn * 100).toFixed(2) }}%</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">观测数</span>
              <span class="stat-value">{{ stat.totalObservations }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 胜率柱状图 -->
      <div class="section-title">胜率对比</div>
      <div ref="chartRef" class="chart-box"></div>

      <!-- 每日明细 -->
      <div class="section-title">
        每日回测明细
        <el-button size="small" style="margin-left: 12px" @click="exportCsv">导出 CSV</el-button>
      </div>
      <el-table :data="dailyPage" stripe size="small" class="data-table" max-height="500">
        <el-table-column prop="tradeDate" label="交易日期" width="120" sortable />
        <el-table-column v-for="day in form.holdingDays" :key="day" :label="'T+' + day + ' 胜率'" width="110">
          <template #default="{ row }">
            <span v-if="row.dailyWinRate[day] != null" :class="row.dailyWinRate[day] >= 0.5 ? 'text-success' : 'text-danger'">
              {{ (row.dailyWinRate[day] * 100).toFixed(1) }}%
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-box" v-if="dailyPageTotal > pageSize">
        <el-pagination
          v-model:current-page="dailyPageNum"
          :page-size="pageSize"
          :total="dailyPageTotal"
          layout="prev, pager, next"
          background
          small
        />
      </div>
    </template>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" :title="'回测明细 - ' + detailDate" width="900px" destroy-on-close>
      <el-table :data="detailStocks" stripe size="small" max-height="400">
        <el-table-column prop="rankNo" label="排名" width="60" />
        <el-table-column prop="stockCode" label="代码" width="100" />
        <el-table-column prop="stockName" label="名称" width="100" />
        <el-table-column prop="finalScore" label="得分" width="80" sortable />
        <el-table-column prop="skipReason" label="跳过原因" min-width="140">
          <template #default="{ row }">
            <span v-if="row.skipReason">{{ formatSkipReason(row.skipReason) }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column v-for="day in form.holdingDays" :key="day" :label="'T+' + day" width="110">
          <template #default="{ row: stock }">
            <span v-if="stock.forwardReturns[day] != null" :class="stock.forwardReturns[day] >= 0 ? 'text-success' : 'text-danger'">
              {{ (stock.forwardReturns[day] * 100).toFixed(2) }}%
            </span>
            <span v-else-if="stock.periodSkipReasons?.[day]" class="text-muted skip-hint">
              {{ formatSkipReason(stock.periodSkipReasons[day]) }}
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { backtestApi, type ScoringBacktestResponse, type DailyDetail, type StockReturn } from '@/api/backtest'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const running = ref(false)
const errorMsg = ref('')
const strategies = ref<string[]>([])
const strategiesLoading = ref(false)
const response = ref<ScoringBacktestResponse | null>(null)

const form = reactive({
  strategyType: '',
  startDate: dayjs().subtract(6, 'month').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD'),
  topN: 10,
  holdingDays: [1, 3, 5, 10, 20]
})

const skipReasonLabels: Record<string, string> = {
  NO_QUOTE_ON_ENTRY_DAY: '入场日无行情（可能停牌）',
  INVALID_ENTRY_OPEN: '入场开盘价无效',
  LIMIT_UP_AT_OPEN: '涨停开盘无法买入',
  NO_NEXT_TRADING_DAY: '无下一交易日',
  BEYOND_CALENDAR: '超出交易日历',
  NO_QUOTE_ON_EXIT_DAY: '出场日无行情（可能停牌）',
  INVALID_EXIT_CLOSE: '出场收盘价无效',
  LIMIT_DOWN_AT_CLOSE: '跌停收盘无法卖出'
}

function formatSkipReason(code: string) {
  return skipReasonLabels[code] ?? code
}

// 图表
const chartRef = ref<HTMLElement>()
let chart: echarts.ECharts | null = null

function renderChart() {
  if (!chartRef.value || !response.value) return
  const stats = response.value.holdingPeriodStats
  const days = Object.keys(stats).map(Number).sort((a, b) => a - b)
  const winRates = days.map((d) => stats[d].winRate * 100)
  const avgReturns = days.map((d) => stats[d].avgReturn * 100)

  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const day = params[0].name
        const stat = stats[parseInt(day.replace('T+', ''))]
        return [
          `<b>${day}</b>`,
          `胜率：${(stat.winRate * 100).toFixed(1)}%`,
          `平均收益：${(stat.avgReturn * 100).toFixed(2)}%`,
          `中位数：${(stat.medianReturn * 100).toFixed(2)}%`,
          `最大：${(stat.maxReturn * 100).toFixed(2)}%  /  最小：${(stat.minReturn * 100).toFixed(2)}%`,
          `观测数：${stat.totalObservations}`
        ].join('<br/>')
      }
    },
    grid: { left: '8%', right: '8%', top: '10%', bottom: '10%' },
    xAxis: {
      type: 'category',
      data: days.map((d) => 'T+' + d),
      axisLabel: { color: '#9db1cc' }
    },
    yAxis: [
      {
        type: 'value',
        name: '胜率 (%)',
        min: 0,
        max: 100,
        axisLabel: { color: '#9db1cc', formatter: '{value}%' },
        splitLine: { lineStyle: { color: 'rgba(145,196,255,0.08)' } }
      },
      {
        type: 'value',
        name: '收益 (%)',
        axisLabel: { color: '#9db1cc', formatter: '{value}%' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '胜率',
        type: 'bar',
        data: winRates,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#58b9ff' },
            { offset: 1, color: '#1cd6a6' }
          ])
        },
        barWidth: '40%',
        label: {
          show: true,
          position: 'top',
          color: '#e9f1ff',
          formatter: '{c:.1f}%'
        }
      },
      {
        name: '平均收益',
        type: 'line',
        yAxisIndex: 1,
        data: avgReturns,
        lineStyle: { color: '#f0b95a', width: 2 },
        itemStyle: { color: '#f0b95a' },
        symbol: 'circle',
        symbolSize: 8,
        label: {
          show: true,
          position: 'top',
          color: '#f0b95a',
          formatter: '{c:.2f}%'
        }
      }
    ]
  })
}

watch(response, () => {
  nextTick(() => renderChart())
})

// 分页
const dailyPageNum = ref(1)
const pageSize = 20
const dailyPageTotal = computed(() => response.value?.dailyDetails?.length ?? 0)
const dailyPage = computed(() => {
  const details = response.value?.dailyDetails ?? []
  const start = (dailyPageNum.value - 1) * pageSize
  return details.slice(start, start + pageSize)
})

watch(response, () => {
  dailyPageNum.value = 1
})

// 详情弹窗
const detailVisible = ref(false)
const detailDate = ref('')
const detailStocks = ref<StockReturn[]>([])

function showDetail(row: DailyDetail) {
  detailDate.value = row.tradeDate
  detailStocks.value = row.topStocks
  detailVisible.value = true
}

async function runBacktest() {
  if (!form.startDate || !form.endDate) {
    errorMsg.value = '请选择起始和结束日期'
    return
  }
  if (form.holdingDays.length === 0) {
    errorMsg.value = '请至少选择一个持仓周期'
    return
  }
  running.value = true
  errorMsg.value = ''
  response.value = null
  try {
    const data = await backtestApi.runBacktest({
      strategyType: form.strategyType || undefined,
      startDate: form.startDate,
      endDate: form.endDate,
      topN: form.topN,
      holdingDays: form.holdingDays,
      commissionRate: 0.0003,
      sellTaxRate: 0.0005,
      slippageRate: 0.0002
    })
    if (!data.totalTradingDays) {
      toast.info('该条件下无回测数据')
    }
    response.value = data
  } catch (e: any) {
    errorMsg.value = e?.message || '回测执行失败'
    toast.error(errorMsg.value)
  } finally {
    running.value = false
  }
}

async function loadStrategies() {
  strategiesLoading.value = true
  try {
    strategies.value = await backtestApi.getStrategies()
  } catch {
    // silent
  } finally {
    strategiesLoading.value = false
  }
}

function exportCsv() {
  if (!response.value) return
  const details = response.value.dailyDetails
  if (!details.length) return
  const holdingDays = form.holdingDays
  const headers = ['tradeDate', ...holdingDays.map((d) => 'T+' + d + '_winRate')]
  const lines = [headers.join(',')]
  for (const row of details) {
    const cells = [row.tradeDate, ...holdingDays.map((d) => row.dailyWinRate[d] ?? '')]
    lines.push(cells.join(','))
  }
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `backtest-${response.value.strategyType || 'all'}-${response.value.startDate}_${response.value.endDate}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

// resize
function onResize() {
  chart?.resize()
}

onMounted(() => {
  loadStrategies()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
})
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.section-title {
  margin: 6px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.holding-form {
  margin-top: -4px;
}

.run-alert {
  margin: 0 0 12px;
}

.result-meta {
  display: flex;
  gap: 20px;
  margin: 8px 0 4px;
  color: var(--text-secondary);
  font-size: 13px;
  flex-wrap: wrap;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  border-radius: var(--r-md);
  padding: 12px 14px;
}

.stat-header {
  font-size: var(--fz-lg);
  font-weight: 600;
  color: var(--accent-1);
  margin-bottom: 8px;
}

.stat-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.stat-label {
  font-size: var(--fz-xs);
  color: var(--text-secondary);
}

.stat-value {
  font-size: var(--fz-sm);
  font-weight: var(--fw-num);
  font-variant-numeric: tabular-nums;
}

.text-success { color: var(--text-success); }
.text-danger { color: var(--text-danger); }
.text-muted { color: var(--text-dim); }

.chart-box {
  width: 100%;
  height: 300px;
  margin-bottom: 16px;
}

.data-table {
  margin-bottom: 8px;
}

.pagination-box {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}
</style>
