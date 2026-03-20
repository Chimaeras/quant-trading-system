<template>
  <div class="data-management">
    <el-card shadow="hover">
      <template #header>
        <span>数据概览</span>
        <el-button type="primary" size="small" :loading="loading" @click="refresh" style="float: right">
          刷新
        </el-button>
      </template>
      <div v-loading="loading" class="overview-grid">
        <div class="stat-item">
          <span class="label">股票数量</span>
          <span class="value">{{ overview?.stock_count ?? '-' }}</span>
        </div>
        <div class="stat-item">
          <span class="label">最早日期</span>
          <span class="value">{{ overview?.earliest_date ?? '-' }}</span>
        </div>
        <div class="stat-item">
          <span class="label">最新日期</span>
          <span class="value">{{ overview?.latest_date ?? '-' }}</span>
        </div>
        <div class="stat-item">
          <span class="label">数据完整性</span>
          <span class="value">{{ overview?.completeness ?? '-' }}%</span>
        </div>
        <div class="stat-item">
          <span class="label">存储空间</span>
          <span class="value">{{ overview?.storage_size ?? '-' }}</span>
        </div>
      </div>
    </el-card>
    <el-card shadow="hover" style="margin-top: 20px">
      <template #header>数据更新</template>
      <el-space direction="vertical" style="width: 100%">
        <div class="update-item">
          <span>股票列表更新</span>
          <el-button type="primary" size="small" :loading="updating" @click="updateStocks">立即更新</el-button>
          <span class="muted">上次：{{ lastUpdate }}</span>
        </div>
        <div class="update-item">
          <span>历史行情下载</span>
          <el-button type="primary" size="small" disabled>立即更新</el-button>
          <span class="muted">离线模式使用本地数据</span>
        </div>
      </el-space>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useDataStore } from '@/stores/data'
import { dataApi } from '@/api/data'

const dataStore = useDataStore()
const loading = ref(false)
const updating = ref(false)
const lastUpdate = ref('-')

const overview = computed(() => dataStore.overview)

const refresh = async () => {
  loading.value = true
  try {
    await dataStore.fetchOverview()
  } finally {
    loading.value = false
  }
}

const updateStocks = async () => {
  updating.value = true
  try {
    const res = await dataApi.updateStockList()
    lastUpdate.value = new Date().toLocaleString()
    ElMessage.success(`更新成功，共 ${res.count} 只股票`)
    await refresh()
  } finally {
    updating.value = false
  }
}

onMounted(() => {
  refresh()
})
</script>

<style scoped>
.overview-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 20px;
}
.stat-item {
  padding: 16px;
  background: #1a1b1e;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.stat-item .label {
  color: #888;
  font-size: 12px;
}
.stat-item .value {
  font-size: 18px;
  font-weight: bold;
  color: #409eff;
}
.update-item {
  display: flex;
  align-items: center;
  gap: 16px;
}
.update-item .muted {
  color: #888;
  font-size: 12px;
}
</style>
