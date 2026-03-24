<template>
  <el-card shadow="hover">
    <template #header>交易记录</template>
    <el-form inline>
      <el-form-item label="时间范围">
        <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="trade_time" label="时间" min-width="165" />
      <el-table-column prop="stock_code" label="代码" min-width="95" />
      <el-table-column prop="stock_name" label="名称" min-width="95" />
      <el-table-column prop="trade_type" label="方向" min-width="75">
        <template #default="{ row }">{{ row.trade_type === 1 ? '买入' : '卖出' }}</template>
      </el-table-column>
      <el-table-column prop="trade_price" label="价格" min-width="75" />
      <el-table-column prop="trade_volume" label="数量" min-width="90" />
      <el-table-column prop="trade_amount" label="金额" min-width="95" />
      <el-table-column prop="profit_loss" label="盈亏" min-width="95">
        <template #default="{ row }">
          <span :class="(row.profit_loss || 0) >= 0 ? 'profit' : 'loss'">{{ row.profit_loss ?? '-' }}</span>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total" layout="total, prev, pager, next" style="margin-top: 16px" @current-change="load" />
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { tradeApi } from '@/api/trade'

const dateRange = ref<[string, string] | null>(null)
const list = ref<any[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

const load = async () => {
  loading.value = true
  try {
    const [start, end] = dateRange.value || [null, null]
    const res = await tradeApi.getTradeList({
      page: page.value,
      size: pageSize.value,
      startDate: start || undefined,
      endDate: end || undefined
    })
    list.value = res.list
    total.value = res.total
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
