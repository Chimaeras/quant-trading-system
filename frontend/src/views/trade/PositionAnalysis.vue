<template>
  <el-card shadow="hover">
    <template #header>持仓分析</template>
    <el-table v-loading="loading" :data="positions" stripe>
      <el-table-column prop="stock_code" label="代码" width="100" />
      <el-table-column prop="stock_name" label="名称" width="100" />
      <el-table-column prop="volume" label="持仓数量" width="100" />
      <el-table-column prop="avg_cost" label="成本价" width="100" />
      <el-table-column prop="current_price" label="现价" width="100" />
      <el-table-column prop="market_value" label="市值" width="120" />
      <el-table-column prop="profit_loss" label="盈亏" width="100">
        <template #default="{ row }">
          <span :class="row.profit_loss >= 0 ? 'profit' : 'loss'">{{ row.profit_loss }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="profit_rate" label="收益率" width="100">
        <template #default="{ row }">
          <span :class="row.profit_rate >= 0 ? 'profit' : 'loss'">{{ row.profit_rate }}%</span>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { tradeApi } from '@/api/trade'

const positions = ref<any[]>([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    positions.value = await tradeApi.getPositions()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.profit { color: #67c23a; }
.loss { color: #f56c6c; }
</style>
