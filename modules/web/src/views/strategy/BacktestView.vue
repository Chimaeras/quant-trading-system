<template>
  <el-card shadow="hover">
    <template #header>
      <span>回测分析</span>
      <el-button type="primary" size="small" :loading="running" @click="runBacktest">运行回测</el-button>
    </template>
    <el-form inline>
      <el-form-item label="策略">
        <el-select
          v-model="strategyId"
          placeholder="选择策略"
          style="min-width: 180px"
        >
          <el-option v-for="s in strategies" :key="s.id" :label="(s.name || s.strategy_name) + ''" :value="s.name || s.strategy_name || 'MA_Cross'" />
        </el-select>
      </el-form-item>
      <el-form-item label="标的">
        <el-input v-model="stockCode" placeholder="如 000001.SZ" style="width: 140px" />
      </el-form-item>
      <el-form-item label="区间">
        <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD" />
      </el-form-item>
    </el-form>
    <div class="config-section">
      <StrategyConfigPanel v-model="backtestParams" :strategy-name="strategyId" />
    </div>
    <div v-if="result" class="result-grid">
      <div class="stat">总收益率: {{ result.total_return ?? '-' }}%</div>
      <div class="stat">年化收益: {{ result.annual_return ?? '-' }}%</div>
      <div class="stat">夏普比率: {{ result.sharpe_ratio ?? '-' }}</div>
      <div class="stat">最大回撤: {{ result.max_drawdown ?? '-' }}%</div>
      <div class="stat">交易次数: {{ result.total_trades ?? '-' }}</div>
      <div class="stat">胜率: {{ result.win_rate ?? '-' }}%</div>
      <div class="stat">总成本: {{ result.total_cost ?? '-' }}</div>
      <div class="stat">结果ID: {{ result.result_id ?? '-' }}</div>
    </div>

    <div class="history-section">
      <div class="history-header">
        <span>回测历史</span>
        <el-button size="small" @click="loadHistory">刷新</el-button>
      </div>
      <el-table :data="historyList" size="small" border stripe v-loading="historyLoading">
        <el-table-column prop="result_id" label="结果ID" min-width="180" />
        <el-table-column prop="strategy_name" label="策略" min-width="130" />
        <el-table-column label="区间" min-width="220">
          <template #default="{ row }">
            {{ row.start_date || '-' }} ~ {{ row.end_date || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="total_return" label="总收益(%)" min-width="100" />
        <el-table-column prop="max_drawdown" label="最大回撤(%)" min-width="110" />
        <el-table-column prop="total_trades" label="交易次数" min-width="90" />
        <el-table-column prop="run_time" label="耗时(s)" min-width="80" />
        <el-table-column prop="created_at" label="创建时间" min-width="170" />
        <el-table-column label="操作" fixed="right" min-width="170">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewDetail(row.result_id)">详情</el-button>
            <el-button link type="success" @click="reuseBacktest(row)">复用参数</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager-wrap">
        <el-pagination
          v-model:current-page="historyPage"
          v-model:page-size="historySize"
          layout="total, prev, pager, next"
          :total="historyTotal"
          @current-change="loadHistory"
        />
      </div>
    </div>
  </el-card>

  <el-dialog v-model="detailVisible" title="回测详情" width="720px">
    <el-descriptions :column="2" border v-if="detailData">
      <el-descriptions-item label="结果ID">{{ detailData.result_id }}</el-descriptions-item>
      <el-descriptions-item label="策略">{{ detailData.strategy_name || '-' }}</el-descriptions-item>
      <el-descriptions-item label="时间区间">{{ detailData.start_date || '-' }} ~ {{ detailData.end_date || '-' }}</el-descriptions-item>
      <el-descriptions-item label="总收益">{{ detailData.total_return ?? '-' }}%</el-descriptions-item>
      <el-descriptions-item label="年化收益">{{ detailData.annual_return ?? '-' }}%</el-descriptions-item>
      <el-descriptions-item label="夏普">{{ detailData.sharpe_ratio ?? '-' }}</el-descriptions-item>
      <el-descriptions-item label="最大回撤">{{ detailData.max_drawdown ?? '-' }}%</el-descriptions-item>
      <el-descriptions-item label="胜率">{{ detailData.win_rate ?? '-' }}%</el-descriptions-item>
      <el-descriptions-item label="交易次数">{{ detailData.total_trades ?? '-' }}</el-descriptions-item>
      <el-descriptions-item label="运行耗时">{{ detailData.run_time ?? '-' }}s</el-descriptions-item>
      <el-descriptions-item label="创建时间" :span="2">{{ detailData.created_at || '-' }}</el-descriptions-item>
      <el-descriptions-item label="参数快照" :span="2">
        <pre class="params-json">{{ JSON.stringify(detailData.params || {}, null, 2) }}</pre>
      </el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import StrategyConfigPanel from '@/components/strategy/StrategyConfigPanel.vue'
import type { StrategyParams, BacktestResult, BacktestHistoryItem } from '@/types'
import { strategyApi } from '@/api/strategy'

const route = useRoute()
const strategyId = ref<string>('MA_Cross')
const stockCode = ref('000001.SZ')
const dateRange = ref<string[]>(['2023-01-01', '2024-12-31'])
const backtestParams = ref<StrategyParams>({})
const running = ref(false)
const result = ref<BacktestResult | null>(null)
const strategies = ref<any[]>([])
const historyList = ref<BacktestHistoryItem[]>([])
const historyLoading = ref(false)
const historyPage = ref(1)
const historySize = ref(10)
const historyTotal = ref(0)
const detailVisible = ref(false)
const detailData = ref<BacktestHistoryItem | null>(null)

const loadStrategies = async () => {
  // 策略中心页面已精简移除，这里仅保留内置策略供回测选择
  strategies.value = [
    { id: 1, name: 'MA_Cross', params: {} },
    { id: 2, name: 'BOLL_Strategy', params: {} },
    { id: 3, name: 'MACD_Strategy', params: {} }
  ]
  const q = String(route.query.id ?? '').trim()
  const byId = q ? strategies.value.find(s => String(s.id) === q) : null
  const byName = q ? strategies.value.find(s => String(s.name) === q) : null
  const cur = byId ?? byName ?? strategies.value[0]
  strategyId.value = String(cur?.name ?? 'MA_Cross')
}

const runBacktest = async () => {
  running.value = true
  try {
    result.value = await strategyApi.runBacktest({
      strategy_id: strategyId.value,
      strategyName: strategyId.value,
      stock_code: stockCode.value,
      start_date: dateRange.value?.[0] || '2023-01-01',
      end_date: dateRange.value?.[1] || '2024-12-31',
      params: backtestParams.value
    })
    await loadHistory()
  } finally {
    running.value = false
  }
}

const loadHistory = async () => {
  historyLoading.value = true
  try {
    const data = await strategyApi.getBacktestResults(historyPage.value, historySize.value)
    historyList.value = data.items ?? []
    historyTotal.value = data.total ?? 0
  } finally {
    historyLoading.value = false
  }
}

const viewDetail = async (resultId: string) => {
  if (!resultId) return
  detailData.value = await strategyApi.getBacktestResult(resultId)
  detailVisible.value = true
}

const reuseBacktest = (item: BacktestHistoryItem) => {
  strategyId.value = item.strategy_name || strategyId.value
  if (item.start_date && item.end_date) dateRange.value = [item.start_date, item.end_date]
  if (item.params && typeof item.params === 'object') backtestParams.value = { ...(item.params as StrategyParams) }
}

onMounted(async () => {
  await loadStrategies()
  await loadHistory()
})
</script>

<style scoped>
.config-section { margin-top: 16px; padding: 12px; background: #f8fafc; border-radius: 8px; border: 1px solid #e2e8f0; }
.result-grid { display: flex; gap: 24px; margin-top: 20px; }
.stat { padding: 12px 20px; background: #f8fafc; border-radius: 8px; border: 1px solid #e2e8f0; }
.history-section { margin-top: 20px; }
.history-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.pager-wrap { margin-top: 10px; display: flex; justify-content: flex-end; }
.params-json { max-height: 220px; overflow: auto; margin: 0; white-space: pre-wrap; background: #f8fafc; border: 1px solid #e2e8f0; padding: 8px; border-radius: 6px; }
</style>
