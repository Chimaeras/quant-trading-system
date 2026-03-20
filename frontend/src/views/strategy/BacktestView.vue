<template>
  <el-card shadow="hover">
    <template #header>
      <span>回测分析</span>
      <el-button type="primary" size="small" :loading="running" @click="runBacktest">运行回测</el-button>
    </template>
    <el-form inline>
      <el-form-item label="策略">
        <el-select v-model="strategyId" placeholder="选择策略" style="width: 200px">
          <el-option v-for="s in strategies" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="标的">
        <el-input v-model="stockCode" placeholder="如 000001.SZ" style="width: 140px" />
      </el-form-item>
      <el-form-item label="区间">
        <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD" />
      </el-form-item>
    </el-form>
    <div v-if="result" class="result-grid">
      <div class="stat">总收益率: {{ result.total_return ?? '-' }}%</div>
      <div class="stat">年化收益: {{ result.annual_return ?? '-' }}%</div>
      <div class="stat">夏普比率: {{ result.sharpe_ratio ?? '-' }}</div>
      <div class="stat">最大回撤: {{ result.max_drawdown ?? '-' }}%</div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { strategyApi } from '@/api/strategy'

const route = useRoute()
const strategyId = ref('')
const stockCode = ref('000001.SZ')
const dateRange = ref<string[]>(['2023-01-01', '2024-12-31'])
const running = ref(false)
const result = ref<any>(null)
const strategies = ref<any[]>([])

const loadStrategies = async () => {
  strategies.value = await strategyApi.getStrategyList()
  strategyId.value = (route.query.id as string) || strategies.value[0]?.id || ''
}

const runBacktest = async () => {
  running.value = true
  try {
    result.value = await strategyApi.runBacktest({
      strategy_id: strategyId.value,
      stock_code: stockCode.value,
      start_date: dateRange.value?.[0] || '2023-01-01',
      end_date: dateRange.value?.[1] || '2024-12-31'
    })
  } finally {
    running.value = false
  }
}

onMounted(loadStrategies)
</script>

<style scoped>
.result-grid { display: flex; gap: 24px; margin-top: 20px; }
.stat { padding: 12px 20px; background: #1a1b1e; border-radius: 8px; }
</style>
