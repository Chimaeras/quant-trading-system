<template>
  <el-card shadow="hover">
    <template #header>盈亏分析</template>
    <el-row :gutter="20">
      <el-col :span="6">
        <div class="stat-card">
          <div class="label">总盈亏</div>
          <div :class="['value', stats.total_profit >= 0 ? 'profit' : 'loss']">{{ stats.total_profit?.toFixed(2) ?? 0 }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="label">盈利笔数</div>
          <div class="value profit">{{ stats.win_count ?? 0 }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="label">亏损笔数</div>
          <div class="value loss">{{ stats.loss_count ?? 0 }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="label">总交易笔数</div>
          <div class="value">{{ stats.total_trades ?? 0 }}</div>
        </div>
      </el-col>
    </el-row>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { tradeApi } from '@/api/trade'

const stats = ref<{ total_profit: number; win_count: number; loss_count: number; total_trades: number }>({
  total_profit: 0,
  win_count: 0,
  loss_count: 0,
  total_trades: 0
})

onMounted(async () => {
  const res = await tradeApi.getTradeList({ size: 9999 })
  stats.value = res.stats || stats.value
})
</script>

<style scoped>
.stat-card { padding: 20px; background: #f8fafc; border-radius: 8px; border: 1px solid #e2e8f0; }
.stat-card .label { color: #888; font-size: 12px; }
.stat-card .value { font-size: 24px; font-weight: bold; margin-top: 8px; }
.profit { color: #67c23a; }
.loss { color: #f56c6c; }
</style>
