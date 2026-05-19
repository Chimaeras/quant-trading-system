<template>
  <el-card shadow="hover" class="terminal-page">
    <template #header>
      <div class="header-row">
        <span>统一打分选股工作台</span>
        <el-radio-group v-model="mode" size="small" @change="onModeChange">
          <el-radio-button value="classic">经典多因子</el-radio-button>
          <el-radio-button value="oversold">超跌反弹</el-radio-button>
        </el-radio-group>
      </div>
    </template>

    <div class="section-title">步骤1：执行打分任务</div>
    <el-form v-if="mode === 'classic'" inline>
      <el-form-item label="交易日">
        <el-date-picker v-model="form.tradeDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="全库历史">
        <el-switch v-model="form.fullHistory" active-text="开" inactive-text="关" />
      </el-form-item>
      <el-form-item v-if="!form.fullHistory" label="回看天数">
        <el-input-number v-model="form.lookbackDays" :min="20" :max="3650" :step="5" />
      </el-form-item>
      <el-form-item label="打分池数量">
        <el-input-number v-model="form.scoreTopN" :min="10" :max="500" :step="10" />
      </el-form-item>
      <el-form-item label="最小日均成交额">
        <el-input-number v-model="form.minAvgAmount" :min="0" :step="5000000" />
      </el-form-item>
      <el-form-item label="最小趋势分">
        <el-input-number v-model="form.minTrendScore" :min="0" :max="100" />
      </el-form-item>
      <el-form-item label="模板">
        <el-select v-model="form.templateId" clearable placeholder="可选模板" style="width: 180px">
          <el-option v-for="t in templates" :key="String(t.template_id)" :label="String(t.name)" :value="String(t.template_id)" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="runSelection">执行打分</el-button>
        <el-button :disabled="running" @click="loadLatest">加载最新</el-button>
        <el-button :disabled="running" @click="saveTemplate">保存模板</el-button>
      </el-form-item>
    </el-form>

    <el-form v-else inline>
      <el-form-item label="交易日">
        <el-date-picker v-model="oversoldForm.tradeDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="打分池数量">
        <el-input-number v-model="oversoldForm.scoreTopN" :min="10" :max="500" :step="10" />
      </el-form-item>
      <el-form-item label="最低综合分">
        <el-input-number v-model="oversoldForm.minTotalScore" :min="0" :max="100" />
      </el-form-item>
      <el-form-item label="排除 ST">
        <el-switch v-model="oversoldForm.excludeST" />
      </el-form-item>
      <el-form-item label="落库">
        <el-switch v-model="oversoldForm.persist" />
      </el-form-item>
      <el-form-item label="模板">
        <el-select v-model="oversoldForm.templateId" clearable placeholder="可选（参数合并）" style="width: 180px">
          <el-option v-for="t in templates" :key="String(t.template_id)" :label="String(t.name)" :value="String(t.template_id)" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="runOversold">执行打分</el-button>
        <el-button :disabled="running" @click="loadLatest">加载最新</el-button>
        <el-button :disabled="running" @click="saveOversoldTemplate">保存模板</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="taskStatus" :type="taskStatus === 'FAILED' ? 'error' : taskStatus === 'SUCCESS' ? 'success' : taskStatus === 'CANCELLED' ? 'warning' : 'info'" show-icon :closable="false" class="run-alert">
      <template #title>
        执行任务状态：{{ taskStatus }} <span v-if="taskId">（{{ taskId }}）</span>
      </template>
      <template #default>
        <div class="run-meta">
          <span>{{ taskMessage || '任务执行中' }}</span>
          <span v-if="taskStatus === 'PENDING'">排队中：前方 {{ pendingAhead }} 个等待任务，当前运行 {{ runningCount }} 个</span>
          <span v-if="taskError">错误：{{ taskError }}</span>
          <span v-if="pollStopReason">监控停止原因：{{ pollStopReason }}</span>
          <span v-if="pollStopAt">停止时间：{{ pollStopAt }}</span>
        </div>
        <div class="run-actions">
          <el-button v-if="taskId && (taskStatus === 'PENDING' || taskStatus === 'RUNNING')" link type="danger" @click="cancelTaskServer">取消当前任务</el-button>
          <el-button v-if="taskId && taskStatus === 'PENDING'" link type="warning" @click="prioritizeCurrentTask">取消前序并优先执行当前任务</el-button>
          <el-button v-if="pendingAhead > 0" link type="danger" @click="clearPendingQueue">一键清空待执行队列</el-button>
          <el-button v-if="taskId && (taskStatus === 'PENDING' || taskStatus === 'RUNNING')" link type="danger" @click="forceRestartCurrentTask">清队列+取消并立即重跑</el-button>
          <el-button v-if="isPollingActive" link type="warning" @click="cancelTaskWatch">停止监控</el-button>
          <el-button v-if="taskId" link type="primary" @click="refreshTaskStatus">刷新任务状态</el-button>
          <el-button v-if="resultMeta.result_id" link type="primary" @click="refreshResultData">刷新已完成股票</el-button>
          <el-button v-else-if="taskId && taskStatus !== 'SUCCESS' && taskStatus !== 'FAILED' && taskStatus !== 'CANCELLED'" link type="primary" @click="resumeTaskWatch">恢复监控</el-button>
        </div>
        <el-progress :percentage="Math.max(0, Math.min(100, taskProgress))" :status="taskStatus === 'FAILED' ? 'exception' : taskStatus === 'SUCCESS' ? 'success' : taskStatus === 'CANCELLED' ? 'warning' : undefined" />
      </template>
    </el-alert>
    <el-form inline class="task-query-form">
      <el-form-item label="任务ID查询">
        <el-input v-model="queryTaskId" placeholder="USK..." style="width: 220px" clearable />
      </el-form-item>
      <el-form-item>
        <el-button @click="queryByTaskId">查询任务</el-button>
      </el-form-item>
      <el-form-item label="结果ID查询">
        <el-input v-model="queryResultId" placeholder="US..." style="width: 220px" clearable />
      </el-form-item>
      <el-form-item>
        <el-button @click="queryByResultId">查询结果</el-button>
      </el-form-item>
    </el-form>

    <div v-if="lastTaskSummary" class="meta">
      <span>最近任务：{{ lastTaskSummary.taskId }}</span>
      <span>策略：{{ lastTaskSummary.strategy }}</span>
      <span>耗时：{{ lastTaskSummary.elapsedMs }} ms</span>
      <span>返回：{{ lastTaskSummary.returnedItems }} / {{ lastTaskSummary.totalItems }}</span>
      <span>完成时间：{{ lastTaskSummary.finishedAt }}</span>
    </div>

    <div class="section-title">步骤2：从评分结果中选择关注 TopN</div>
    <el-form inline class="focus-form">
      <el-form-item label="TopN">
        <el-input-number v-model="focusForm.topN" :min="1" :max="200" />
      </el-form-item>
      <el-form-item label="最低分">
        <el-input-number v-model="focusForm.minScore" :min="0" :max="100" :step="0.5" :precision="1" />
      </el-form-item>
      <el-form-item label="结果视图">
        <el-radio-group v-model="focusForm.view" size="small">
          <el-radio-button value="focus">关注清单</el-radio-button>
          <el-radio-button value="all">全部评分</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :disabled="!results.length" @click="applyFocusSelection">生成关注清单</el-button>
        <el-button :disabled="!displayedResults.length" @click="exportCsv">导出当前视图</el-button>
      </el-form-item>
    </el-form>

    <div class="meta" v-if="resultMeta.result_id">
      <span>结果ID: {{ resultMeta.result_id }}</span>
      <span>交易日: {{ resultMeta.trade_date }}</span>
      <span>已评分: {{ results.length }} 只</span>
      <span v-if="resultMeta.total_candidates > 0">进度: {{ resultMeta.processed_candidates }}/{{ resultMeta.total_candidates }}</span>
      <span>关注: {{ focusResults.length }} 只</span>
      <span>因子版本: {{ resultMeta.factor_version || 'v1' }}</span>
      <span v-if="resultMeta.run_status">状态: {{ resultMeta.run_status }}</span>
    </div>

    <el-table v-if="mode === 'classic'" v-loading="loading" :data="displayedResults" stripe>
      <el-table-column label="排名" width="70">
        <template #default="{ row, $index }">{{ resolveRank(row, $index) }}</template>
      </el-table-column>
      <el-table-column prop="stock_code" label="代码" min-width="95" />
      <el-table-column prop="stock_name" label="名称" min-width="120" />
      <el-table-column prop="final_score" label="综合分" min-width="90" />
      <el-table-column prop="trend_score" label="趋势分" min-width="90" />
      <el-table-column prop="momentum_score" label="动量分" min-width="90" />
      <el-table-column prop="ret20" label="20日收益(%)" min-width="110" />
      <el-table-column prop="ret60" label="60日收益(%)" min-width="110" />
      <el-table-column prop="max_drawdown60" label="60日回撤(%)" min-width="110" />
      <el-table-column label="操作" min-width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-table v-else v-loading="loading" :data="displayedResults" stripe>
      <el-table-column label="排名" width="70">
        <template #default="{ row, $index }">{{ resolveRank(row, $index) }}</template>
      </el-table-column>
      <el-table-column prop="stock_code" label="代码" min-width="95" />
      <el-table-column prop="stock_name" label="名称" min-width="120" />
      <el-table-column prop="final_score" label="综合分" min-width="88" />
      <el-table-column prop="trend_score" label="趋势(40)" min-width="88" />
      <el-table-column prop="volume_score" label="量能(30)" min-width="88" />
      <el-table-column prop="momentum_score" label="超跌(30)" min-width="88" />
      <el-table-column prop="volatility20" label="加权分" min-width="88" />
      <el-table-column prop="ret20" label="20日收益(%)" min-width="110" />
      <el-table-column prop="ret60" label="60日收益(%)" min-width="110" />
      <el-table-column label="操作" min-width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <p v-if="mode === 'classic'" class="tip">流程：先执行打分拿到评分池，再按 TopN 和最低分生成关注清单。</p>
    <p v-else class="tip">逻辑：趋势确认(40) + 成交量验证(30) + 超跌程度(30)；关注清单支持二次阈值筛选。</p>
  </el-card>

  <el-dialog v-model="detailVisible" title="选股详情" width="640px">
    <el-descriptions :column="2" border v-if="detailRow">
      <el-descriptions-item label="股票">{{ detailRow.stock_code }} {{ detailRow.stock_name }}</el-descriptions-item>
      <el-descriptions-item label="综合分">{{ detailRow.final_score }}</el-descriptions-item>
      <template v-if="mode === 'oversold'">
        <el-descriptions-item label="趋势(40)">{{ detailRow.trend_score }}</el-descriptions-item>
        <el-descriptions-item label="量能(30)">{{ detailRow.volume_score }}</el-descriptions-item>
        <el-descriptions-item label="超跌(30)">{{ detailRow.momentum_score }}</el-descriptions-item>
        <el-descriptions-item label="加权分">{{ detailRow.volatility20 }}</el-descriptions-item>
      </template>
      <template v-else>
        <el-descriptions-item label="趋势分">{{ detailRow.trend_score }}</el-descriptions-item>
        <el-descriptions-item label="动量分">{{ detailRow.momentum_score }}</el-descriptions-item>
        <el-descriptions-item label="量价分">{{ detailRow.volume_score }}</el-descriptions-item>
        <el-descriptions-item label="风险惩罚">{{ detailRow.risk_penalty }}</el-descriptions-item>
      </template>
      <el-descriptions-item label="20日收益">{{ detailRow.ret20 }}%</el-descriptions-item>
      <el-descriptions-item label="60日收益">{{ detailRow.ret60 }}%</el-descriptions-item>
      <el-descriptions-item label="日均成交额" :span="2">{{ detailRow.avg_amount20 }}</el-descriptions-item>
      <el-descriptions-item label="明细/原因" :span="2">
        <pre class="json-box">{{ JSON.stringify(detailRow.reasons || detailRow.detail || {}, null, 2) }}</pre>
      </el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { selectorApi } from '@/api/selector'
import { scoringApi, type UnifiedScoringStrategy, type UnifiedScoringTaskStatus } from '@/api/scoring'
import { useToast } from '@/composables/useToast'

type ModeType = 'classic' | 'oversold'
type TaskStatusType = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED' | ''

const loading = ref(false)
const running = ref(false)
const toast = useToast()
const mode = ref<ModeType>('classic')
const results = ref<Record<string, any>[]>([])
const focusResults = ref<Record<string, any>[]>([])
const detailVisible = ref(false)
const detailRow = ref<Record<string, any> | null>(null)
const taskId = ref('')
const taskStatus = ref<TaskStatusType>('')
const taskProgress = ref(0)
const taskMessage = ref('')
const taskError = ref('')
const pollSerial = ref(0)
const isPollingActive = ref(false)
const pollStopReason = ref('')
const pollStopAt = ref('')
const pendingAhead = ref(0)
const runningCount = ref(0)
const queryTaskId = ref('')
const queryResultId = ref('')
const lastTaskSummary = ref<{
  taskId: string
  strategy: string
  elapsedMs: number
  totalItems: number
  returnedItems: number
  finishedAt: string
} | null>(null)

const resultMeta = reactive({
  result_id: '',
  trade_date: '',
  factor_version: 'v1',
  run_status: '',
  processed_candidates: 0,
  total_candidates: 0
})
const templates = ref<Array<Record<string, unknown>>>([])

const form = reactive({
  tradeDate: dayjs().format('YYYY-MM-DD'),
  fullHistory: true,
  lookbackDays: 120,
  scoreTopN: 500,
  minAvgAmount: 0,
  minTrendScore: 40,
  templateId: ''
})

const oversoldForm = reactive({
  tradeDate: dayjs().format('YYYY-MM-DD'),
  scoreTopN: 500,
  minTotalScore: 60,
  excludeST: true,
  persist: true,
  templateId: ''
})

const focusForm = reactive({
  topN: 20,
  minScore: 0,
  view: 'focus' as 'focus' | 'all'
})

const displayedResults = computed(() => (focusForm.view === 'focus' ? focusResults.value : results.value))

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function scoreOf(row: Record<string, any>) {
  const v = row.final_score ?? row.total_score ?? 0
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

function resolveRank(row: Record<string, any>, index: number) {
  if (focusForm.view === 'focus') return row.local_rank ?? index + 1
  return row.rank_no ?? index + 1
}

function resetTaskState() {
  taskId.value = ''
  taskStatus.value = ''
  taskProgress.value = 0
  taskMessage.value = ''
  taskError.value = ''
  pendingAhead.value = 0
  runningCount.value = 0
}

function stopPolling(reason?: string) {
  const hadPolling = isPollingActive.value
  pollSerial.value += 1
  isPollingActive.value = false
  if (reason && hadPolling) {
    pollStopReason.value = reason
    pollStopAt.value = dayjs().format('YYYY-MM-DD HH:mm:ss')
    taskMessage.value = reason
  }
}

function setTaskState(t: UnifiedScoringTaskStatus) {
  taskId.value = t.task_id
  taskStatus.value = t.status
  taskProgress.value = Number(t.progress_pct || 0)
  taskMessage.value = String(t.message || '')
  taskError.value = String(t.error_message || '')
  pendingAhead.value = Number(t.pending_ahead || 0)
  runningCount.value = Number(t.running_count || 0)
}

function buildTaskSummary(t: UnifiedScoringTaskStatus) {
  const startedAt = t.started_at ? new Date(t.started_at).getTime() : 0
  const finishedAt = t.finished_at ? new Date(t.finished_at).getTime() : 0
  const elapsedMs = startedAt > 0 && finishedAt >= startedAt ? finishedAt - startedAt : 0
  lastTaskSummary.value = {
    taskId: t.task_id,
    strategy: t.strategy,
    elapsedMs,
    totalItems: Number(t.total_items || 0),
    returnedItems: Number(t.returned_items || 0),
    finishedAt: t.finished_at || dayjs().format('YYYY-MM-DD HH:mm:ss')
  }
}

function applyResult(data: Record<string, any>) {
  resultMeta.result_id = String(data.result_id ?? '')
  resultMeta.trade_date = String(data.trade_date ?? '')
  resultMeta.factor_version = String(data.factor_version ?? 'v1')
  resultMeta.run_status = String(data.run_status ?? '')
  resultMeta.processed_candidates = Number(data.processed_candidates || 0)
  resultMeta.total_candidates = Number(data.total_candidates || 0)
  const incoming = Array.isArray(data.items) ? data.items : Array.isArray(data.list) ? data.list : []
  const merged = new Map<string, Record<string, any>>()
  for (const row of results.value) {
    const key = `${String(row.stock_code || '')}|${String(row.exchange_type || '')}`
    if (key !== '|') merged.set(key, row)
  }
  for (const row of incoming) {
    const key = `${String(row.stock_code || '')}|${String(row.exchange_type || '')}`
    if (key === '|') continue
    merged.set(key, { ...(merged.get(key) || {}), ...row })
  }
  results.value = Array.from(merged.values()).sort((a, b) => {
    const ar = Number(a.rank_no ?? Number.MAX_SAFE_INTEGER)
    const br = Number(b.rank_no ?? Number.MAX_SAFE_INTEGER)
    if (ar !== br) return ar - br
    return scoreOf(b) - scoreOf(a)
  })
  applyFocusSelection()
}

function applyFocusSelection() {
  const sorted = [...results.value]
    .filter((item) => scoreOf(item) >= Number(focusForm.minScore || 0))
    .sort((a, b) => scoreOf(b) - scoreOf(a))
    .slice(0, focusForm.topN)
    .map((item, index) => ({ ...item, local_rank: index + 1 }))
  focusResults.value = sorted
}

async function runAndWaitTask(payload: Record<string, unknown>) {
  stopPolling('已启动新任务，停止旧任务监控')
  taskError.value = ''
  pollStopReason.value = ''
  pollStopAt.value = ''
  const created = await scoringApi.runUnifiedScoringTask(payload as any)
  taskId.value = created.task_id
  taskStatus.value = created.status
  taskProgress.value = Number(created.progress_pct || 0)
  taskMessage.value = String(created.message || '任务已创建')
  pendingAhead.value = Number(created.pending_ahead || 0)
  runningCount.value = Number(created.running_count || 0)
  const serial = ++pollSerial.value
  isPollingActive.value = true
  let errorCount = 0

  try {
    while (serial === pollSerial.value) {
      try {
        const t = await scoringApi.getUnifiedScoringTask(created.task_id)
        errorCount = 0
        setTaskState(t)
        if (t.result_id && (t.status === 'RUNNING' || t.status === 'PENDING')) {
          await syncPartialResult(t.result_id)
        }
        if (t.status === 'SUCCESS') {
          if (t.result_id) {
            await syncPartialResult(t.result_id)
          }
          buildTaskSummary(t)
          return t
        }
        if (t.status === 'FAILED') {
          throw new Error(t.error_message || t.message || '任务执行失败')
        }
        if (t.status === 'CANCELLED') {
          throw new Error(t.message || '任务已取消')
        }
      } catch (e: any) {
        errorCount += 1
        const msg = String(e?.message || '')
        if (msg.includes('任务执行失败')) {
          throw e
        }
        taskMessage.value = `进度查询波动，正在重试（${errorCount}/6）`
        if (errorCount >= 6) {
          throw new Error('进度查询连续失败，任务可能仍在执行。可点击"恢复监控"继续查看。')
        }
      }
      await sleep(Math.min(2000 + errorCount * 1000, 6000))
    }
    if (pollStopReason.value) {
      throw new Error(pollStopReason.value)
    }
    throw new Error('任务监控已停止')
  } finally {
    if (serial === pollSerial.value) {
      isPollingActive.value = false
    }
  }
}

async function syncPartialResult(resultId: string) {
  try {
    const page = await scoringApi.getUnifiedResult(resultId, { page: 1, size: currentScorePoolSize() })
    const items = Array.isArray(page.list) ? page.list : Array.isArray(page.items) ? page.items : []
    if (!items.length) return
    applyResult(page as Record<string, any>)
    taskMessage.value = `任务执行中，已同步 ${items.length} 条阶段结果`
  } catch {
    // 阶段结果尚未落库时忽略
  }
}

async function refreshTaskStatus() {
  if (!taskId.value) return
  try {
    const t = await scoringApi.getUnifiedScoringTask(taskId.value)
    setTaskState(t)
    queryTaskId.value = taskId.value
    if (t.result_id) {
      await syncPartialResult(t.result_id)
    }
  } catch (e: any) {
    toast.error(e?.message || '刷新任务状态失败')
  }
}

async function refreshResultData() {
  if (!resultMeta.result_id) return
  loading.value = true
  try {
    const data = await scoringApi.getUnifiedResult(resultMeta.result_id, { page: 1, size: currentScorePoolSize() })
    applyResult(data as Record<string, any>)
  } catch (e: any) {
    toast.error(e?.message || '刷新结果失败')
  } finally {
    loading.value = false
  }
}

async function queryByTaskId() {
  const id = queryTaskId.value.trim()
  if (!id) {
    toast.warning('请输入任务ID')
    return
  }
  try {
    const t = await scoringApi.getUnifiedScoringTask(id)
    setTaskState(t)
    taskId.value = id
    if (t.result_id) {
      resultMeta.result_id = t.result_id
      queryResultId.value = t.result_id
      await refreshResultData()
    }
  } catch (e: any) {
    toast.error(e?.message || '任务查询失败')
  }
}

async function queryByResultId() {
  const id = queryResultId.value.trim()
  if (!id) {
    toast.warning('请输入结果ID')
    return
  }
  loading.value = true
  try {
    const data = await scoringApi.getUnifiedResult(id, { page: 1, size: currentScorePoolSize() })
    applyResult(data as Record<string, any>)
    resultMeta.result_id = id
  } catch (e: any) {
    toast.error(e?.message || '结果查询失败')
  } finally {
    loading.value = false
  }
}

function currentStrategy(): UnifiedScoringStrategy {
  if (mode.value === 'classic') return 'CLASSIC_SELECTOR'
  return 'OVERSOLD_REBOUND'
}

function currentTradeDate() {
  if (mode.value === 'classic') return form.tradeDate
  return oversoldForm.tradeDate
}

function currentScorePoolSize() {
  if (mode.value === 'classic') return form.scoreTopN
  return oversoldForm.scoreTopN
}

async function runSelection() {
  running.value = true
  loading.value = true
  try {
    const done = await runAndWaitTask({
      strategy: 'CLASSIC_SELECTOR',
      tradeDate: form.tradeDate,
      fullHistory: form.fullHistory,
      lookbackDays: form.fullHistory ? undefined : form.lookbackDays,
      topN: form.scoreTopN,
      minAvgAmount: form.minAvgAmount,
      minTrendScore: form.minTrendScore,
      templateId: form.templateId || undefined
    })
    const data = done.result_id
      ? await scoringApi.getUnifiedResult(done.result_id, { page: 1, size: form.scoreTopN })
      : await scoringApi.getUnifiedLatest({ strategy: 'CLASSIC_SELECTOR', tradeDate: form.tradeDate, page: 1, size: form.scoreTopN })
    applyResult(data)
    toast.success(`打分完成，共 ${results.value.length} 只`)
  } catch (e: any) {
    taskError.value = String(e?.message || '运行选股失败')
    toast.error(taskError.value)
  } finally {
    running.value = false
    loading.value = false
  }
}

async function runOversold() {
  running.value = true
  loading.value = true
  try {
    const done = await runAndWaitTask({
      strategy: 'OVERSOLD_REBOUND',
      tradeDate: oversoldForm.tradeDate,
      topN: oversoldForm.scoreTopN,
      minTotalScore: oversoldForm.minTotalScore,
      excludeST: oversoldForm.excludeST,
      persist: oversoldForm.persist,
      templateId: oversoldForm.templateId || undefined
    })
    const data = done.result_id
      ? await scoringApi.getUnifiedResult(done.result_id, { page: 1, size: oversoldForm.scoreTopN })
      : await scoringApi.getUnifiedLatest({ strategy: 'OVERSOLD_REBOUND', tradeDate: oversoldForm.tradeDate, page: 1, size: oversoldForm.scoreTopN })
    applyResult(data)
    toast.success(`打分完成，共 ${results.value.length} 只`)
  } catch (e: any) {
    taskError.value = String(e?.message || '运行超跌筛选失败')
    toast.error(taskError.value)
  } finally {
    running.value = false
    loading.value = false
  }
}

async function resumeTaskWatch() {
  if (!taskId.value) {
    toast.warning('没有可恢复监控的任务')
    return
  }
  running.value = true
  try {
    const serial = ++pollSerial.value
    isPollingActive.value = true
    pollStopReason.value = ''
    pollStopAt.value = ''
    taskError.value = ''
    taskMessage.value = '已恢复监控，正在同步进度...'
    let errorCount = 0

    while (serial === pollSerial.value) {
      try {
        const t = await scoringApi.getUnifiedScoringTask(taskId.value)
        errorCount = 0
        setTaskState(t)
        if (t.result_id && (t.status === 'RUNNING' || t.status === 'PENDING')) {
          await syncPartialResult(t.result_id)
        }
        if (t.status === 'SUCCESS') {
          buildTaskSummary(t)
          const data = t.result_id
            ? await scoringApi.getUnifiedResult(t.result_id, { page: 1, size: currentScorePoolSize() })
            : await scoringApi.getUnifiedLatest({
                strategy: currentStrategy(),
                tradeDate: currentTradeDate(),
                page: 1,
                size: currentScorePoolSize()
              })
          applyResult(data)
          toast.success(`任务已完成，共 ${results.value.length} 只`)
          return
        }
        if (t.status === 'FAILED') {
          throw new Error(t.error_message || t.message || '任务执行失败')
        }
        if (t.status === 'CANCELLED') {
          throw new Error(t.message || '任务已取消')
        }
      } catch (e: any) {
        errorCount += 1
        const msg = String(e?.message || '')
        if (msg.includes('任务执行失败')) {
          throw e
        }
        taskMessage.value = `恢复监控后进度查询波动，重试中（${errorCount}/6）`
        if (errorCount >= 6) {
          throw new Error('恢复监控失败：进度查询连续失败，请稍后重试。')
        }
      }
      await sleep(Math.min(2000 + errorCount * 1000, 6000))
    }
    throw new Error(pollStopReason.value || '任务监控已停止')
  } catch (e: any) {
    taskError.value = String(e?.message || '恢复监控失败')
    toast.error(taskError.value)
  } finally {
    isPollingActive.value = false
    running.value = false
  }
}

function cancelTaskWatch() {
  stopPolling('用户手动停止任务监控（后端任务仍在执行）')
  running.value = false
}

async function cancelTaskServer() {
  if (!taskId.value) return
  try {
    const ret = await scoringApi.cancelUnifiedScoringTask(taskId.value)
    const msg = String(ret?.message || '')
    taskMessage.value = msg || '任务已取消'
    await resumeTaskWatch()
  } catch (e: any) {
    toast.error(e?.message || '取消任务失败')
  }
}

async function prioritizeCurrentTask() {
  if (!taskId.value) return
  try {
    const ret = await scoringApi.prioritizeUnifiedScoringTask(taskId.value)
    const msg = String(ret?.message || '已触发优先执行')
    taskMessage.value = msg
    toast.success(msg)
    await resumeTaskWatch()
  } catch (e: any) {
    toast.error(e?.message || '优先执行失败')
  }
}

async function clearPendingQueue() {
  try {
    const ret = await scoringApi.clearPendingUnifiedScoringTasks()
    const msg = String(ret?.message || '')
    if (msg) {
      taskMessage.value = msg
      toast.success(msg)
    }
    if (taskId.value) {
      await resumeTaskWatch()
    }
  } catch (e: any) {
    toast.error(e?.message || '清空待执行队列失败')
  }
}

async function forceRestartCurrentTask() {
  if (!taskId.value) {
    toast.warning('没有可重跑的任务')
    return
  }
  const oldTaskId = taskId.value
  try {
    stopPolling('准备重跑任务，先清理队列与当前执行')
    const clearRet = await scoringApi.clearPendingUnifiedScoringTasks()
    let cancelMsg = ''
    if (taskStatus.value === 'PENDING' || taskStatus.value === 'RUNNING') {
      const cancelRet = await scoringApi.cancelUnifiedScoringTask(oldTaskId)
      cancelMsg = String(cancelRet?.message || '')
    }
    const clearMsg = String(clearRet?.message || '')
    const summary = [clearMsg, cancelMsg].filter(Boolean).join('；')
    if (summary) {
      taskMessage.value = summary
      toast.success(summary)
    }
    if (mode.value === 'classic') {
      await runSelection()
    } else {
      await runOversold()
    }
  } catch (e: any) {
    const msg = String(e?.message || '强制重跑失败')
    taskError.value = msg
    toast.error(msg)
  }
}

async function loadTemplates() {
  templates.value = await selectorApi.listTemplates()
}

async function saveTemplate() {
  const name = `经典-${dayjs().format('MMDD-HHmm')}`
  await selectorApi.saveTemplate({
    templateId: form.templateId || undefined,
    name,
    params: {
      fullHistory: form.fullHistory,
      lookbackDays: form.lookbackDays,
      topN: form.scoreTopN,
      minAvgAmount: form.minAvgAmount,
      minTrendScore: form.minTrendScore
    }
  })
  toast.success('模板已保存')
  await loadTemplates()
}

async function saveOversoldTemplate() {
  const name = `超跌反弹-${dayjs().format('MMDD-HHmm')}`
  await selectorApi.saveTemplate({
    templateId: oversoldForm.templateId || undefined,
    name,
    params: {
      topN: oversoldForm.scoreTopN,
      minTotalScore: oversoldForm.minTotalScore,
      excludeST: oversoldForm.excludeST,
      persist: oversoldForm.persist
    }
  })
  toast.success('模板已保存')
  await loadTemplates()
}

function exportCsv() {
  const rows = displayedResults.value
  if (!rows.length) return
  const headers = ['rank_no', 'stock_code', 'stock_name', 'final_score', 'trend_score', 'momentum_score', 'volume_score', 'ret20', 'ret60']
  const lines = [headers.join(',')]
  for (const r of rows) {
    const row = {
      ...r,
      rank_no: focusForm.view === 'focus' ? r.local_rank : r.rank_no
    }
    lines.push(headers.map((h) => String(row[h] ?? '')).join(','))
  }
  const suffix = focusForm.view === 'focus' ? 'focus' : 'all'
  downloadCsv(lines, `score-picker-${suffix}-${resultMeta.trade_date || dayjs().format('YYYY-MM-DD')}.csv`)
}

function downloadCsv(lines: string[], filename: string) {
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

async function loadLatest() {
  loading.value = true
  try {
    const data = await scoringApi.getUnifiedLatest({
      strategy: currentStrategy(),
      tradeDate: currentTradeDate(),
      page: 1,
      size: currentScorePoolSize()
    })
    applyResult(data)
    if (!results.value.length) toast.info('当日暂无结果，请先执行打分')
  } catch (e: any) {
    toast.error(e?.message || '加载结果失败')
  } finally {
    loading.value = false
  }
}

function showDetail(row: Record<string, any>) {
  detailRow.value = row
  detailVisible.value = true
}

function onModeChange() {
  stopPolling('切换策略，已停止当前任务监控')
  resetTaskState()
  results.value = []
  focusResults.value = []
  loadLatest().catch(() => {})
}

onMounted(async () => {
  await loadTemplates()
  await loadLatest()
})

onBeforeUnmount(() => {
  stopPolling('页面离开，已停止任务监控')
})
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.section-title {
  margin: 2px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.focus-form {
  margin-bottom: 10px;
}
.meta {
  display: flex;
  gap: 20px;
  margin: 8px 0 12px;
  color: var(--text-secondary);
  font-size: 13px;
  flex-wrap: wrap;
}
.run-alert {
  margin: 0 0 12px;
}
.run-meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 13px;
  margin-bottom: 6px;
}
.run-actions {
  margin-bottom: 6px;
}
.task-query-form {
  margin: 8px 0 12px;
}
.tip {
  color: var(--text-secondary);
  font-size: 12px;
  margin-top: 12px;
}
.json-box {
  margin: 0;
  white-space: pre-wrap;
  background: rgba(9, 18, 34, 0.72);
  border: 1px solid rgba(145, 196, 255, 0.2);
  border-radius: 6px;
  padding: 8px;
  max-height: 220px;
  overflow: auto;
  color: var(--text-primary);
}
</style>
