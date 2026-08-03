<template>
  <div class="data-management terminal-page">
    <el-tabs v-model="activeDataTab" class="data-tabs" @tab-change="onDataTabChange">
      <el-tab-pane label="数据总览" name="overview" />
      <el-tab-pane label="热门股票" name="hot" />
    </el-tabs>

    <div v-show="activeDataTab === 'overview'">
    <el-card shadow="hover">
      <template #header>
        <span>数据概览</span>
        <el-button type="primary" size="small" :loading="loading" @click="refresh" style="float: right">
          刷新
        </el-button>
      </template>
      <div v-loading="loading" class="stat-grid">
        <div class="stat-card compact">
          <span class="label">股票数量</span>
          <span class="value">{{ overview?.stock_count ?? '-' }}</span>
        </div>
        <div class="stat-card compact">
          <span class="label">最早日期</span>
          <span class="value">{{ overview?.earliest_date ?? '-' }}</span>
        </div>
        <div class="stat-card compact">
          <span class="label">最新日期</span>
          <span class="value">{{ overview?.latest_date ?? '-' }}</span>
        </div>
        <div class="stat-card compact">
          <span class="label">数据完整性</span>
          <span class="value">{{ overview?.completeness != null ? overview.completeness + '%' : '-' }}</span>
        </div>
        <div class="stat-card compact">
          <span class="label">存储空间</span>
          <span class="value">{{ overview?.storage_size ?? '-' }}</span>
        </div>
        <div class="stat-card compact">
          <span class="label">上市日缺失</span>
          <span class="value">{{ overview?.list_date_missing_count ?? '-' }}</span>
        </div>
        <div class="stat-card compact">
          <span class="label">可回填上市日</span>
          <span class="value">{{ overview?.list_date_backfillable_count ?? '-' }}</span>
        </div>
      </div>
      <div class="list-date-backfill">
        <p class="backfill-hint">
          上市日期（list_date）缺失时，可用本地 K 线最早交易日回填空值，不覆盖已有数据；受行情窗口限制，非交易所官方上市日。
        </p>
        <el-alert
          v-if="listDateMissing > 0 && listDateBackfillable > 0"
          type="warning"
          :closable="false"
          show-icon
          class="backfill-alert"
          :title="`检测到 ${listDateMissing} 只股票缺失上市日，其中 ${listDateBackfillable} 只可从本地 K 线回填，建议立即回填。`"
        />
        <el-alert
          v-else-if="listDateMissing > 0 && listDateBackfillable === 0"
          type="info"
          :closable="false"
          show-icon
          class="backfill-alert"
          :title="`检测到 ${listDateMissing} 只股票缺失上市日，但本地 K 线无法回填（需接入官方上市日数据源）。`"
        />
        <el-alert
          v-else
          type="success"
          :closable="false"
          show-icon
          class="backfill-alert"
          title="上市日数据完整，无需回填。"
        />
        <el-button
          type="warning"
          size="small"
          :loading="backfillLoading"
          :disabled="listDateBackfillable === 0"
          @click="runBackfillListDate"
        >
          回填缺失上市日期
        </el-button>
        <span v-if="backfillResult" class="backfill-result">
          可回填 {{ backfillResult.backfillable ?? '—' }} 条，已补 {{ backfillResult.updated }} 条（补前缺失 {{ backfillResult.missing_before }}，补后缺失 {{ backfillResult.missing_after }}）
        </span>
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
              <el-button
                v-if="['PENDING', 'RUNNING', 'STOPPING'].includes(row.status)"
                link
                type="danger"
                :loading="stoppingTaskId === row.task_id"
                @click="stopTask(row.task_id)"
              >
                停止
              </el-button>
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

    <div v-show="activeDataTab === 'hot'">
      <el-card shadow="hover">
        <template #header>
          <div class="hot-header">
            <span>热门股票</span>
            <el-button type="primary" size="small" :loading="hotLoading" @click="loadHotStocks">
              刷新榜单
            </el-button>
          </div>
        </template>
        <el-form inline class="hot-form">
          <el-form-item label="TopN">
            <el-input-number v-model="hotForm.limit" :min="1" :max="500" :step="10" controls-position="right" />
          </el-form-item>
          <el-form-item label="近期交易日">
            <el-input-number v-model="hotForm.days" :min="3" :max="30" :step="1" controls-position="right" />
          </el-form-item>
          <el-form-item label="基准交易日">
            <el-input-number v-model="hotForm.baselineDays" :min="5" :max="120" :step="5" controls-position="right" />
          </el-form-item>
          <el-form-item label="截止日">
            <el-date-picker v-model="hotForm.endDate" type="date" value-format="YYYY-MM-DD" placeholder="默认最新交易日" clearable />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="hotLoading" @click="loadHotStocks">计算热门度</el-button>
          </el-form-item>
        </el-form>

        <div class="hot-meta">
          <span>区间：{{ hotMeta.start_date }} ~ {{ hotMeta.end_date }}</span>
          <span>返回：{{ hotRows.length }} / {{ hotMeta.limit }}</span>
          <span>公式：{{ hotMeta.formula }}</span>
        </div>

        <el-table :data="hotRows" size="small" stripe border v-loading="hotLoading" class="hot-table">
          <el-table-column type="index" label="#" width="56" />
          <el-table-column prop="stock_code" label="代码" min-width="90" fixed="left" />
          <el-table-column prop="stock_name" label="名称" min-width="110" fixed="left" />
          <el-table-column prop="exchange_type" label="交易所" min-width="80" />
          <el-table-column prop="industry" label="行业" min-width="120" show-overflow-tooltip />
          <el-table-column label="热度" min-width="120" sortable>
            <template #default="{ row }">
              <span class="hot-score">{{ formatNumber(row.hot_score, 2) }}</span>
              <el-tag size="small" :type="hotLevelType(row.hot_score)" effect="dark">{{ row.hot_level }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="近均成交额" min-width="120" sortable>
            <template #default="{ row }">{{ formatAmount(row.avg_amount) }}</template>
          </el-table-column>
          <el-table-column label="均换手率" min-width="100" sortable>
            <template #default="{ row }">{{ formatNumber(row.avg_turnover, 2) }}%</template>
          </el-table-column>
          <el-table-column label="量能放大" min-width="100" sortable>
            <template #default="{ row }">{{ formatNumber(row.volume_ratio, 2) }}x</template>
          </el-table-column>
          <el-table-column label="上涨天数" min-width="90">
            <template #default="{ row }">{{ row.up_days }}/{{ row.trade_days }}</template>
          </el-table-column>
          <el-table-column label="最新价" min-width="90">
            <template #default="{ row }">{{ formatNumber(row.latest_close, 2) }}</template>
          </el-table-column>
          <el-table-column label="最新涨跌幅" min-width="110">
            <template #default="{ row }">
              <span :class="Number(row.latest_change_pct) >= 0 ? 'price-up' : 'price-down'">
                {{ formatNumber(row.latest_change_pct, 2) }}%
              </span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import dayjs from 'dayjs'
import { useDataStore } from '@/stores/data'
import { dataApi, type HotStockItem, type HotStocksResponse } from '@/api/data'
import { applyTradingDayPreset, type PresetKey } from '@/utils/tradingDays'
import { useToast } from '@/composables/useToast'

const dataStore = useDataStore()
const toast = useToast()
const loading = ref(false)
const backfillLoading = ref(false)
const backfillResult = ref<{ missing_before: number; backfillable?: number; updated: number; missing_after: number } | null>(null)
const taskLoading = ref(false)
const taskSubmitting = ref(false)
const stoppingTaskId = ref('')
const tasks = ref<any[]>([])
const detailVisible = ref(false)
const taskDetail = ref<any>(null)
const detailLoading = ref(false)
const detailTaskId = ref('')
const detailPage = ref(1)
const detailSize = ref(100)
const activeDataTab = ref<'overview' | 'hot'>('overview')
const hotLoading = ref(false)
const hotRows = ref<HotStockItem[]>([])
const hotMeta = ref<HotStocksResponse>({
  limit: 50,
  days: 7,
  baseline_days: 20,
  start_date: '-',
  end_date: '-',
  items_count: 0,
  formula: '35%成交额规模 + 25%换手率 + 25%量能放大 + 15%上涨天数占比',
  items: []
})
const hotForm = reactive({
  limit: 50,
  days: 7,
  baselineDays: 20,
  endDate: ''
})

const _defaultRange = applyTradingDayPreset('y1')
type UpdateMode = 'SINGLE' | 'ALL' | 'LIST' | 'MAIN_BOARD'

const taskForm = reactive<{
  mode: UpdateMode
  stockCode: string
  exchange: string
  startDateInput: string
  endDateInput: string
  stockListText: string
  delayMs: number
  maxStocks: number
}>({
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
const listDateMissing = computed(() => Number(overview.value?.list_date_missing_count ?? 0))
const listDateBackfillable = computed(() => Number(overview.value?.list_date_backfillable_count ?? 0))

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

const loadHotStocks = async () => {
  hotLoading.value = true
  try {
    const data = await dataApi.getHotStocks({
      limit: hotForm.limit,
      days: hotForm.days,
      baselineDays: hotForm.baselineDays,
      endDate: hotForm.endDate || undefined
    })
    hotMeta.value = data
    hotRows.value = data.items ?? []
  } catch (e: any) {
    toast.error(e?.message || '热门股票计算失败')
  } finally {
    hotLoading.value = false
  }
}

const onDataTabChange = async (name: string | number) => {
  if (name === 'hot' && hotRows.value.length === 0) {
    await loadHotStocks()
  }
}

function formatNumber(value: unknown, digits = 2): string {
  const n = Number(value)
  if (!Number.isFinite(n)) return '-'
  return n.toFixed(digits)
}

function formatAmount(value: unknown): string {
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) return '-'
  if (n >= 100000000) return `${(n / 100000000).toFixed(2)}亿`
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  return n.toFixed(0)
}

function hotLevelType(score: unknown): 'danger' | 'warning' | 'success' | 'info' {
  const n = Number(score)
  if (n >= 80) return 'danger'
  if (n >= 65) return 'warning'
  if (n >= 50) return 'success'
  return 'info'
}

const runBackfillListDate = async () => {
  backfillLoading.value = true
  try {
    const result = await dataApi.backfillListDate()
    backfillResult.value = result
    toast.success(`上市日期回填完成：新增 ${result.updated} 条`)
    // 回填后自动刷新概览，避免 missing_count 仍显示旧值造成「未回填成功」的误判
    await refresh()
  } catch (e: any) {
    toast.error(e?.message || '上市日期回填失败')
  } finally {
    backfillLoading.value = false
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
    toast.warning('请填写有效日期：支持 20250407 或 2025-04-07')
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
    toast.warning('请输入股票代码')
    return
  }
  if (taskForm.mode === 'LIST' && !taskForm.stockListText.trim()) {
    toast.warning('请输入股票清单')
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
    toast.success(`任务已创建：${data.task_id}，请点击「刷新任务列表」查看进度`)
  } catch (e: any) {
    toast.error(e?.message || '创建任务失败')
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
    toast.success(`已创建重试任务：${data.task_id}，请点击「刷新任务列表」查看进度`)
  } catch (e: any) {
    toast.error(e?.message || '重试任务创建失败')
  }
}

const stopTask = async (taskId: string) => {
  if (!taskId) return
  stoppingTaskId.value = taskId
  try {
    const data = await dataApi.stopUpdateTask(taskId)
    const status = String(data?.status ?? '')
    if (status === 'STOPPED') {
      toast.success('任务已停止')
    } else {
      toast.success('已发送停止请求，任务将尽快终止')
    }
    await loadTasks()
    if (detailVisible.value && detailTaskId.value === taskId) {
      await loadTaskDetail(taskId, detailPage.value, detailSize.value)
    }
  } catch (e: any) {
    toast.error(e?.message || '停止任务失败')
  } finally {
    stoppingTaskId.value = ''
  }
}

onMounted(async () => {
  await refresh()
})
</script>

<style scoped>
.list-date-backfill {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--hairline);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.backfill-hint {
  margin: 0;
  flex: 1 1 100%;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}
.backfill-alert {
  flex: 1 1 100%;
  margin-bottom: 4px;
}
.backfill-result {
  font-size: 12px;
  color: var(--text-muted);
}
.data-tabs {
  margin-bottom: 12px;
}
.hot-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.hot-form {
  margin-bottom: 8px;
}
.hot-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin-bottom: 12px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.hot-table {
  width: 100%;
}
.hot-score {
  display: inline-block;
  min-width: 44px;
  margin-right: 8px;
  font-weight: 700;
  color: var(--text-primary);
}
.price-up {
  color: var(--up);
}
.price-down {
  color: var(--down);
}
.update-form { margin-bottom: 8px; }
.task-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--text-secondary);
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
  color: var(--text-muted);
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
