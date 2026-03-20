<template>
  <el-card shadow="hover">
    <template #header>选股器（离线）</template>
    <el-form inline>
      <el-form-item label="行业">
        <el-select v-model="industry" placeholder="行业" clearable style="width: 120px">
          <el-option label="金融" value="金融" />
          <el-option label="科技" value="科技" />
          <el-option label="消费" value="消费" />
        </el-form-item>
      <el-form-item label="市值">
        <el-select v-model="marketCap" placeholder="市值" clearable style="width: 120px">
          <el-option label="大市值" value="large" />
          <el-option label="中市值" value="mid" />
          <el-option label="小市值" value="small" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">选股</el-button>
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="results" stripe>
      <el-table-column prop="stock_code" label="代码" width="100" />
      <el-table-column prop="stock_name" label="名称" />
      <el-table-column prop="industry" label="行业" width="100" />
    </el-table>
    <p class="tip">离线模式：基于本地股票列表筛选</p>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { dataApi } from '@/api/data'

const industry = ref('')
const marketCap = ref('')
const loading = ref(false)
const results = ref<any[]>([])

const search = async () => {
  loading.value = true
  try {
    const res = await dataApi.getStockList({ page: 1, size: 50 })
    results.value = industry.value ? res.list.filter((s: any) => s.industry === industry.value) : res.list
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.tip { color: #888; font-size: 12px; margin-top: 12px; }
</style>
