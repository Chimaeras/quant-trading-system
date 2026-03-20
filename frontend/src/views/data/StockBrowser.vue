<template>
  <el-card shadow="hover">
    <template #header>
      <el-input v-model="keyword" placeholder="搜索股票代码/名称" style="width: 200px" clearable @change="search" />
      <el-select v-model="exchangeType" placeholder="市场" style="width: 120px; margin-left: 12px" clearable @change="search">
        <el-option label="深交所" value="SZSE" />
        <el-option label="上交所" value="SSE" />
      </el-select>
      <el-button type="primary" style="margin-left: 12px" @click="search">搜索</el-button>
    </template>
    <el-table v-loading="loading" :data="stockList" stripe style="width: 100%">
      <el-table-column prop="stock_code" label="股票代码" width="100" />
      <el-table-column prop="stock_name" label="股票名称" />
      <el-table-column prop="exchange_type_name" label="市场" width="100" />
      <el-table-column prop="industry" label="行业" width="120" />
      <el-table-column prop="list_date" label="上市日期" width="120" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push({ path: '/market/kline', query: { code: row.stock_code, name: row.stock_name } })">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      :page-size="pageSize"
      :total="total"
      layout="total, prev, pager, next"
      style="margin-top: 16px"
      @current-change="loadStocks"
    />
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { dataApi } from '@/api/data'

const keyword = ref('')
const exchangeType = ref('')
const stockList = ref<any[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

const loadStocks = async () => {
  loading.value = true
  try {
    const res = await dataApi.getStockList({
      page: page.value,
      size: pageSize.value,
      keyword: keyword.value || undefined,
      exchange_type: exchangeType.value || undefined
    })
    stockList.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}

const search = () => {
  page.value = 1
  loadStocks()
}

onMounted(loadStocks)
</script>
