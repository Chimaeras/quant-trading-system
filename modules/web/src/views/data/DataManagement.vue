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
      <template #header>历史数据更新任务</template>
      <el-space direction="vertical" style="width: 100%">
        <el-form inline class="update-form">
          <el-form-item label="更新模式">
            <el-select v-model="taskForm.mode" style="width: 120px">
              <el-option label="单只" value="SINGLE" />
              <el-option label="清单" value="LIST" />
              <el-option label="全量" value="ALL" />
            </el-select>
          </el-form-item>
          <el-form-item label="股票代码" v-if="taskForm.mode === 'SINGLE'">
            <el-input v-model="taskForm.stockCode" placeholder="000001" style="width: 120px" clearable />
          </el-form-item>
          <el-form-item label="交易所" v-if="taskForm.mode === 'SINGLE'">
            <el-select v-model="taskForm.exchange" placeholder="自动识别" style="width: 100px" clearable>
              <el-option label="自动" value="" />
              <el-option label="SSE" value="SSE" />
              <el-option label="SZSE" value="SZSE" />
              <el-option label="BSE" value="BSE" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间范围">
            <el-date-picker
              v-model="taskForm.dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
              style="width: 260px"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="taskSubmitting" @click="runUpdateTask">创建更新任务</el-button>
            <el-button :loading="taskLoading" @click="loadTasks">刷新任务</el-button>
          </el-form-item>
        </el-form>

        <el-input
          v-if="taskForm.mode === 'LIST'"
          v-model="taskForm.stockListText"
          type="textarea"
          :rows="3"
          placeholder="输入股票清单，支持逗号/换行分隔，如 000001.SZ,600519.SH"
        />

        <el-table :data="tasks" size="small" stripe border v-loading="taskLoading">
          <el-table-column prop="task_id" label="任务ID" min-width="180" />
          <el-table-column prop="mode" label="模式" min-width="80" />
          <el-table-column prop="status" label="状态" min-width="90" />
          <el-table-column label="区间" min-width="200">
            <template #default="{ row }">{{ row.start_date }} ~ {{ row.end_date }}</template>
          </el-table-column>
          <el-table-column label="进度" min-width="180">
            <template #default="{ row }">
              {{ row.processed_stocks }}/{{ row.total_stocks }}（成功{{ row.success_stocks }} 失败{{ row.failed_stocks }}）
            </template>
          </el-table-column>
          <el-table-column label="数据条数" min-width="180">
            <template #default="{ row }">
              新增{{ row.inserted_count }} / 跳过{{ row.skipped_count }} / 获取{{ row.fetched_count }}
            </template>
          </el-table-column>
          <el-table-column prop="message" label="说明" min-width="150" />
          <el-table-column label="操作" min-width="140" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="viewTask(row.task_id)">详情</el-button>
              <el-button link type="warning" @click="retryTask(row.task_id)" v-if="row.failed_stocks > 0">重试失败</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-dialog v-model="detailVisible" title="任务详情" width="760px">
          <el-descriptions :column="2" border v-if="taskDetail">
            <el-descriptions-item label="任务ID">{{ taskDetail.task_id }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ taskDetail.status }}</el-descriptions-item>
            <el-descriptions-item label="时间范围">{{ taskDetail.start_date }} ~ {{ taskDetail.end_date }}</el-descriptions-item>
            <el-descriptions-item label="统计">成功{{ taskDetail.success_stocks }} / 失败{{ taskDetail.failed_stocks }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="taskDetail?.items || []" size="small" stripe border style="margin-top: 10px">
            <el-table-column prop="stock_code" label="股票" min-width="90" />
            <el-table-column prop="exchange_type" label="交易所" min-width="80" />
            <el-table-column prop="status" label="状态" min-width="80" />
            <el-table-column label="结果" min-width="170">
              <template #default="{ row }">新增{{ row.inserted }} / 跳过{{ row.skipped }} / 获取{{ row.fetched }}</template>
            </el-table-column>
            <el-table-column prop="message" label="信息" min-width="200" />
          </el-table>
        </el-dialog>
      </el-space>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, reactive } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { useDataStore } from '@/stores/data'
import { dataApi } from '@/api/data'

const dataStore = useDataStore()
const loading = ref(false)
const taskLoading = ref(false)
const taskSubmitting = ref(false)
const tasks = ref<any[]>([])
const detailVisible = ref(false)
const taskDetail = ref<any>(null)
let pollTimer: number | null = null

const taskForm = reactive({
  mode: 'SINGLE',
  stockCode: '',
  exchange: '',
  dateRange: [dayjs().subtract(1, 'year').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')] as [string, string],
  stockListText: ''
})

const overview = computed(() => dataStore.overview)

const refresh = async () => {
  loading.value = true
  try {
    await dataStore.fetchOverview()
  } finally {
    loading.value = false
  }
}

const loadTasks = async () => {
  taskLoading.value = true
  try {
    const data = await dataApi.listUpdateTasks(1, 20)
    tasks.value = (data.items as any[]) ?? []
  } finally {
    taskLoading.value = false
  }
}

const runUpdateTask = async () => {
  if (!taskForm.dateRange || taskForm.dateRange.length !== 2) {
    ElMessage.warning('请选择时间范围')
    return
  }
  if (taskForm.mode === 'SINGLE' && !taskForm.stockCode.trim()) {
    ElMessage.warning('请输入股票代码')
    return
  }
  if (taskForm.mode === 'LIST' && !taskForm.stockListText.trim()) {
    ElMessage.warning('请输入股票清单')
    return
  }
  taskSubmitting.value = true
  try {
    const payload = {
      mode: taskForm.mode as 'SINGLE' | 'ALL' | 'LIST',
      stockCode: taskForm.stockCode.trim() || undefined,
      exchange: taskForm.exchange || undefined,
      startDate: taskForm.dateRange[0],
      endDate: taskForm.dateRange[1],
      stockListText: taskForm.stockListText || undefined
    }
    const data = await dataApi.runUpdateTask(payload)
    ElMessage.success(`任务已创建：${data.task_id}`)
    await loadTasks()
    startPolling()
  } catch (e: any) {
    ElMessage.error(e?.message || '创建任务失败')
  } finally {
    taskSubmitting.value = false
  }
}

const viewTask = async (taskId: string) => {
  const data = await dataApi.getUpdateTask(taskId)
  taskDetail.value = data
  detailVisible.value = true
}

const retryTask = async (taskId: string) => {
  try {
    const data = await dataApi.retryUpdateTask(taskId)
    ElMessage.success(`已创建重试任务：${data.task_id}`)
    await loadTasks()
    startPolling()
  } catch (e: any) {
    ElMessage.error(e?.message || '重试任务创建失败')
  }
}

const startPolling = () => {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    await loadTasks()
    const running = tasks.value.some(t => t.status === 'RUNNING' || t.status === 'PENDING')
    if (!running) {
      stopPolling()
      refresh()
    }
  }, 3000)
}

const stopPolling = () => {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(async () => {
  await refresh()
  await loadTasks()
  if (tasks.value.some(t => t.status === 'RUNNING' || t.status === 'PENDING')) startPolling()
})

onBeforeUnmount(() => {
  stopPolling()
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
.update-form { margin-bottom: 8px; }
</style>
