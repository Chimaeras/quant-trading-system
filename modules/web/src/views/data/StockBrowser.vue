<template>
  <el-card shadow="hover">
    <template #header>
      <el-input v-model="keyword" placeholder="搜索股票代码/名称（模糊）" style="width: 220px" clearable @keyup.enter="search" @change="search" />
      <el-select v-model="exchangeType" placeholder="市场" style="width: 120px; margin-left: 12px" clearable @change="search">
        <el-option label="深交所" value="SZSE" />
        <el-option label="上交所" value="SSE" />
        <el-option label="北交所" value="BSE" />
      </el-select>
      <el-button type="primary" style="margin-left: 12px" @click="search">搜索</el-button>
      <el-tag v-if="dataSource === 'local'" type="warning" size="small" style="margin-left: 12px">本地 IndexedDB 数据（后端不可用时降级）</el-tag>
    </template>
    <el-table v-loading="loading" :data="stockList" stripe style="width: 100%">
      <el-table-column prop="stock_code" label="股票代码" min-width="95" />
      <el-table-column prop="stock_name" label="股票名称" min-width="120" />
      <el-table-column prop="exchange_type_name" label="市场" min-width="90" />
      <el-table-column prop="industry" label="行业" min-width="110" />
      <el-table-column prop="list_date" label="上市日期" min-width="110" />
      <el-table-column label="操作" min-width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push({ path: '/market/stock', query: { code: row.stock_code, name: row.stock_name, exchange: row.exchange_type } })">详情</el-button>
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
import { marketApi } from '@/api/market'
import { dataApi } from '@/api/data'

const keyword = ref('')
const exchangeType = ref('')
const stockList = ref<any[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const dataSource = ref<'backend' | 'local'>('backend')

const loadStocks = async () => {
  loading.value = true
  try {
    // 优先使用后端 API（MySQL 数据），失败则回退到 IndexedDB 并标注来源
    try {
      const data = await marketApi.getStockList({
        page: page.value,
        size: pageSize.value,
        keyword: keyword.value || undefined,
        exchange: exchangeType.value || undefined
      })
      stockList.value = data?.list ?? []
      total.value = data?.total ?? 0
      dataSource.value = 'backend'
    } catch {
      const res = await dataApi.getStockList({
        page: page.value,
        size: pageSize.value,
        keyword: keyword.value || undefined,
        exchange_type: exchangeType.value || undefined
      })
      stockList.value = res.list
      total.value = res.total
      dataSource.value = 'local'
    }
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
