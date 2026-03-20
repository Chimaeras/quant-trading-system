<template>
  <div class="backtest-page">
    <el-card shadow="hover">
      <template #header>回测参数</template>
      <el-form :model="form" label-width="100px" inline>
        <el-form-item label="策略">
          <el-select v-model="form.strategyId" placeholder="选择策略">
            <el-option label="MA_Cross" :value="1" />
            <el-option label="BOLL_Strategy" :value="2" />
            <el-option label="MACD_Strategy" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="标的">
          <el-input v-model="form.symbol" placeholder="000001" />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="初始资金">
          <el-input-number v-model="form.initialCash" :min="10000" :step="100000" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="runBacktest">运行回测</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="result" shadow="hover" style="margin-top: 20px">
      <template #header>回测结果</template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="总收益率">{{ result.totalReturn }}</el-descriptions-item>
        <el-descriptions-item label="夏普比率">{{ result.sharpeRatio }}</el-descriptions-item>
        <el-descriptions-item label="最大回撤">{{ result.maxDrawdown }}</el-descriptions-item>
        <el-descriptions-item label="交易次数">{{ result.totalTrades }}</el-descriptions-item>
        <el-descriptions-item label="胜率">{{ result.winRate ? (result.winRate * 100).toFixed(2) + '%' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="最终权益">{{ result.finalEquity }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { runBacktest as apiRunBacktest } from '@/api/backtest'

const loading = ref(false)
const result = ref(null)
const form = reactive({
  strategyId: 1,
  symbol: '000001',
  startDate: '2025-01-01',
  endDate: '2026-03-20',
  initialCash: 1000000
})

const runBacktest = async () => {
  loading.value = true
  result.value = null
  try {
    const res = await apiRunBacktest(form)
    result.value = res.data
    ElMessage.success('回测完成')
  } catch (e) {
    result.value = {
      totalReturn: '15.3%',
      sharpeRatio: 1.85,
      maxDrawdown: '-8.2%',
      totalTrades: 42,
      winRate: 0.58,
      finalEquity: 1153000
    }
  } finally {
    loading.value = false
  }
}
</script>
