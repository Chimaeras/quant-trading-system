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
          <span class="value">{{ overview?.completeness != null ? overview.completeness + '%' : '-' }}</span>
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
        <div class="update-item history-update">
          <span>历史行情下载</span>
          <el-input v-model="historyStockCode" placeholder="股票代码如 000001" style="width: 120px" clearable />
          <el-select v-model="historyExchange" placeholder="交易所" style="width: 100px" clearable>
            <el-option label="自动识别" value="" />
            <el-option label="上交所" value="SSE" />
            <el-option label="深交所" value="SZSE" />
            <el-option label="北交所" value="BSE" />
          </el-select>
          <el-date-picker
            v-model="historyDateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
          />
          <el-button type="primary" size="small" :loading="historyUpdating" @click="updateHistory">
            立即更新
          </el-button>
          <span class="muted">{{ historyResult }}</span>
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
const historyStockCode = ref('')
const historyExchange = ref('')
const historyDateRange = ref<[string, string] | null>(null)
const historyUpdating = ref(false)
const historyResult = ref('选择股票与日期范围后点击立即更新')

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

const updateHistory = async () => {
  const code = historyStockCode.value?.trim()
  if (!code) {
    ElMessage.warning('请输入股票代码')
    return
  }
  const range = historyDateRange.value
  if (!range || range.length !== 2) {
    ElMessage.warning('请选择日期范围')
    return
  }
  historyUpdating.value = true
  historyResult.value = '更新中...'
  try {
    const res = await dataApi.updateHistoryData({
      stockCode: code,
      exchange: historyExchange.value || undefined,
      startDate: range[0],
      endDate: range[1]
    })
    historyResult.value = res.message || `新增 ${res.inserted} 条，跳过 ${res.skipped} 条`
    ElMessage.success(historyResult.value)
    await refresh()
  } catch (e) {
    historyResult.value = '更新失败，请检查网络与股票代码'
  } finally {
    historyUpdating.value = false
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
  background: #f8fafc;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.stat-item .label {
  color: #64748b;
  font-size: 12px;
}
.stat-item .value {
  font-size: 18px;
  font-weight: bold;
  color: #1e40af;
}
.update-item {
  display: flex;
  align-items: center;
  gap: 16px;
}
.update-item.history-update {
  flex-wrap: wrap;
  row-gap: 8px;
}
.update-item .muted {
  color: #64748b;
  font-size: 12px;
}
</style>
