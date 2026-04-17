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
      <template #header>
        <span>历史数据更新任务</span>
        <el-button type="primary" size="small" :loading="taskLoading" style="float: right" @click="loadTasks">
          刷新任务列表
        </el-button>
      </template>
      <el-space direction="vertical" style="width: 100%">
        <el-form inline class="update-form">
          <el-form-item label="更新模式">
            <el-select v-model="taskForm.mode" style="width: 140px">
              <el-option label="单只" value="SINGLE" />
              <el-option label="清单" value="LIST" />
              <el-option label="全量" value="ALL" />
              <el-option label="主板枚举" value="MAIN_BOARD" />
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
          <el-form-item v-if="taskForm.mode === 'MAIN_BOARD'" label="间隔(ms)">
            <el-input-number v-model="taskForm.delayMs" :min="0" :max="60000" :step="50" controls-position="right" style="width: 130px" />
          </el-form-item>
          <el-form-item v-if="taskForm.mode === 'MAIN_BOARD'" label="最多只数(0=不限)">
            <el-input-number v-model="taskForm.maxStocks" :min="0" :max="200000" :step="100" controls-position="right" style="width: 130px" />
          </el-form-item>
          <el-form-item label="开始日期">
            <el-input
              v-model="taskForm.startDateInput"
              placeholder="20250407 或 2025-04-07"
              style="width: 168px"
              clearable
              @blur="onDateBlur('start')"
            />
          </el-form-item>
          <el-form-item label="结束日期">
            <el-input
              v-model="taskForm.endDateInput"
              placeholder="20250407 或 2025-04-07"
              style="width: 168px"
              clearable
              @blur="onDateBlur('end')"
            />
          </el-form-item>
          <el-form-item label="快捷区间" class="date-preset-row">
            <el-space wrap :size="8">
              <el-button v-for="p in datePresets" :key="p.key" size="small" @click="applyDatePreset(p.key)">
                {{ p.label }}
              </el-button>
            </el-space>
            <span class="preset-tip">结束日取不晚于今天的最近工作日；区间内含固定条数的交易日（周一～周五，未剔除法定节假日，长假前后略有偏差）</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="taskSubmitting" @click="runUpdateTask">创建更新任务</el-button>
          </el-form-item>
        </el-form>
        <p class="task-hint">任务列表不会自动加载或定时刷新；请先点右上角「刷新任务列表」拉取数据。创建/重试成功后也需手动刷新才能看到最新状态。</p>

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

        <el-dialog
          v-model="detailVisible"
          title="任务详情"
          width="760px"
          class="task-detail-dialog"
          lock-scroll
          destroy-on-close
        >
          <div class="task-detail-scroll">
            <el-descriptions v-if="taskDetail" :column="2" border>
              <el-descriptions-item label="任务ID">{{ taskDetail.task_id }}</el-descriptions-item>
              <el-descriptions-item label="状态">{{ taskDetail.status }}</el-descriptions-item>
              <el-descriptions-item label="时间范围">{{ taskDetail.start_date }} ~ {{ taskDetail.end_date }}</el-descriptions-item>
              <el-descriptions-item label="统计">成功{{ taskDetail.success_stocks }} / 失败{{ taskDetail.failed_stocks }}</el-descriptions-item>
              <el-descriptions-item label="任务项返回策略">
                最新优先（倒序）第{{ taskDetail.items_page || 1 }}页，每页{{ taskDetail.items_size || 100 }}条
              </el-descriptions-item>
              <el-descriptions-item label="任务项总数">{{ taskDetail.items_total ?? '-' }}</el-descriptions-item>
            </el-descriptions>
            <el-table :data="taskDetail?.items || []" size="small" stripe border class="task-detail-table" v-loading="detailLoading">
              <el-table-column prop="stock_code" label="股票" min-width="90" />
              <el-table-column prop="exchange_type" label="交易所" min-width="80" />
              <el-table-column prop="status" label="状态" min-width="80" />
              <el-table-column label="结果" min-width="170">
                <template #default="{ row }">新增{{ row.inserted }} / 跳过{{ row.skipped }} / 获取{{ row.fetched }}</template>
              </el-table-column>
              <el-table-column prop="message" label="信息" min-width="200" />
            </el-table>
            <div class="task-detail-pagination" v-if="(taskDetail?.items_total ?? 0) > 0">
              <el-pagination
                background
                layout="total, sizes, prev, pager, next"
                :total="Number(taskDetail?.items_total ?? 0)"
                :current-page="detailPage"
                :page-size="detailSize"
                :page-sizes="[50, 100, 200, 500]"
                @current-change="onDetailPageChange"
                @size-change="onDetailSizeChange"
              />
            </div>
          </div>
        </el-dialog>
      </el-space>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { useDataStore } from '@/stores/data'
import { dataApi } from '@/api/data'
import { applyTradingDayPreset, type PresetKey } from '@/utils/tradingDays'

const dataStore = useDataStore()
const loading = ref(false)
const taskLoading = ref(false)
const taskSubmitting = ref(false)
const tasks = ref<any[]>([])
const detailVisible = ref(false)
const taskDetail = ref<any>(null)
const detailLoading = ref(false)
const detailTaskId = ref('')
const detailPage = ref(1)
const detailSize = ref(100)

const _defaultRange = applyTradingDayPreset('y1')
const taskForm = reactive({
  mode: 'SINGLE',
  stockCode: '',
  exchange: '',
  /** 支持 20250407、2025-04-07 等，失焦后规范为 8 位数字 */
  startDateInput: _defaultRange.start.format('YYYYMMDD'),
  endDateInput: _defaultRange.end.format('YYYYMMDD'),
  stockListText: '',
  /** 主板枚举：与 StockInfo 类似的请求间隔，默认 150ms */
  delayMs: 150,
  /** 0 表示不限制 */
  maxStocks: 0
})

const overview = computed(() => dataStore.overview)

/** 各按钮：闭区间内交易日条数见 utils/tradingDays PRESET_TRADING_DAY_COUNTS */
const datePresets: { key: PresetKey; label: string }[] = [
  { key: 'd7', label: '近7个交易日' },
  { key: 'm1', label: '近1月·约20交易日' },
  { key: 'm3', label: '近3月·约60交易日' },
  { key: 'm6', label: '近6月·约120交易日' },
  { key: 'y1', label: '近1年·约250交易日' },
  { key: 'y2', label: '近2年·约500交易日' }
]

function applyDatePreset(key: PresetKey) {
  const { start, end } = applyTradingDayPreset(key)
  taskForm.startDateInput = start.format('YYYYMMDD')
  taskForm.endDateInput = end.format('YYYYMMDD')
}

const refresh = async () => {
  loading.value = true
  try {
    await dataStore.fetchOverview()
  } finally {
    loading.value = false
  }
}

/** 解析为 YYYY-MM-DD；支持 8 位连写、带分隔符的日期 */
function parseFlexibleDate(raw: string): string | null {
  const t = raw.trim()
  if (!t) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(t)) {
    const d = new Date(t + 'T12:00:00')
    return isNaN(d.getTime()) ? null : t
  }
  const compact = t.replace(/\D/g, '')
  if (compact.length !== 8 || !/^\d{8}$/.test(compact)) return null
  const y = parseInt(compact.slice(0, 4), 10)
  const m = parseInt(compact.slice(4, 6), 10)
  const day = parseInt(compact.slice(6, 8), 10)
  const d = new Date(y, m - 1, day)
  if (d.getFullYear() !== y || d.getMonth() !== m - 1 || d.getDate() !== day) return null
  return `${y}-${String(m).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

function normalizeDateDisplay(iso: string): string {
  return iso.replace(/-/g, '')
}

function onDateBlur(which: 'start' | 'end') {
  const raw = which === 'start' ? taskForm.startDateInput : taskForm.endDateInput
  const iso = parseFlexibleDate(raw)
  if (iso) {
    if (which === 'start') taskForm.startDateInput = normalizeDateDisplay(iso)
    else taskForm.endDateInput = normalizeDateDisplay(iso)
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
  const startIso = parseFlexibleDate(taskForm.startDateInput)
  const endIso = parseFlexibleDate(taskForm.endDateInput)
  if (!startIso || !endIso) {
    ElMessage.warning('请填写有效日期：支持 20250407 或 2025-04-07')
    return
  }
  let startDate = startIso
  let endDate = endIso
  if (dayjs(startDate).isAfter(dayjs(endDate))) {
    const x = startDate
    startDate = endDate
    endDate = x
    taskForm.startDateInput = normalizeDateDisplay(startDate)
    taskForm.endDateInput = normalizeDateDisplay(endDate)
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
    const payload: Parameters<typeof dataApi.runUpdateTask>[0] = {
      mode: taskForm.mode,
      stockCode: taskForm.stockCode.trim() || undefined,
      exchange: taskForm.exchange || undefined,
      startDate,
      endDate,
      stockListText: taskForm.stockListText || undefined
    }
    if (taskForm.mode === 'MAIN_BOARD') {
      payload.delayMs = taskForm.delayMs
      if (taskForm.maxStocks > 0) payload.maxStocks = taskForm.maxStocks
    }
    const data = await dataApi.runUpdateTask(payload)
    ElMessage.success(`任务已创建：${data.task_id}，请点击「刷新任务列表」查看进度`)
  } catch (e: any) {
    ElMessage.error(e?.message || '创建任务失败')
  } finally {
    taskSubmitting.value = false
  }
}

const loadTaskDetail = async (taskId: string, page = detailPage.value, size = detailSize.value) => {
  detailLoading.value = true
  try {
    const data = await dataApi.getUpdateTask(taskId, page, size)
    taskDetail.value = data
    detailPage.value = Number(data.items_page ?? page)
    detailSize.value = Number(data.items_size ?? size)
  } finally {
    detailLoading.value = false
  }
}

const viewTask = async (taskId: string) => {
  detailTaskId.value = taskId
  detailPage.value = 1
  detailSize.value = 100
  detailVisible.value = true
  await loadTaskDetail(taskId, detailPage.value, detailSize.value)
}

const onDetailPageChange = async (page: number) => {
  if (!detailTaskId.value) return
  detailPage.value = page
  await loadTaskDetail(detailTaskId.value, detailPage.value, detailSize.value)
}

const onDetailSizeChange = async (size: number) => {
  if (!detailTaskId.value) return
  detailSize.value = size
  detailPage.value = 1
  await loadTaskDetail(detailTaskId.value, detailPage.value, detailSize.value)
}

const retryTask = async (taskId: string) => {
  try {
    const data = await dataApi.retryUpdateTask(taskId)
    ElMessage.success(`已创建重试任务：${data.task_id}，请点击「刷新任务列表」查看进度`)
  } catch (e: any) {
    ElMessage.error(e?.message || '重试任务创建失败')
  }
}

onMounted(async () => {
  await refresh()
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
.task-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}
.date-preset-row {
  align-items: flex-start;
}
.date-preset-row :deep(.el-form-item__content) {
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}
.preset-tip {
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.4;
}

/* 任务详情：在弹窗内部滚动，避免带动整页滚动 */
.task-detail-dialog :deep(.el-dialog__body) {
  padding-top: 8px;
  max-height: calc(80vh - 120px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.task-detail-scroll {
  flex: 1;
  min-height: 0;
  max-height: calc(80vh - 140px);
  overflow-y: auto;
  overflow-x: auto;
  padding-right: 4px;
  overscroll-behavior: contain;
}
.task-detail-scroll .task-detail-table {
  margin-top: 10px;
}
.task-detail-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
