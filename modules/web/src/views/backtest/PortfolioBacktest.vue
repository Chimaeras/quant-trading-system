<template>
  <PageShell>
    <PageHeader
      title="组合回测"
      subtitle="事件驱动组合回测（MP-005 现金因果 + raw-only 成交 + 最低佣金 + T+1）。"
    >
      <template #actions>
        <el-tag size="small" type="danger" class="beta-tag">Beta</el-tag>
      </template>
    </PageHeader>

    <SectionCard title="运行参数">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="note-alert"
      title="Beta 页面 —— 事件驱动组合回测（MP-005 现金因果 + raw-only 成交 + 最低佣金 + T+1）；订单/NAV/持仓已规范化落子表，长区间订单分页读取。选择已发布 prediction run 可闭环验证「预测 → 组合结果」且资金守恒。"
    />

      <el-form inline class="form">
        <el-form-item label="信号源">
          <el-radio-group v-model="form.mode">
            <el-radio value="prediction">已发布预测（闭环）</el-radio>
            <el-radio value="range">选股结果区间</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="form.mode === 'prediction'">
          <el-form-item label="prediction run">
            <el-select v-model="form.predictionRunId" filterable placeholder="选择已发布预测" style="width: 360px">
              <el-option
                v-for="r in publishedRuns"
                :key="r.prediction_run_id || ''"
                :value="r.prediction_run_id || ''"
                :label="runLabel(r)"
                :disabled="r.status !== 'PUBLISHED'"
              >
                <span>{{ runLabel(r) }}</span>
                <el-tag v-if="r.status !== 'PUBLISHED'" size="small" type="info" class="run-tag">{{ r.status }}</el-tag>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button :loading="loadingRuns" @click="loadPredictionRuns">刷新</el-button>
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="策略">
            <el-select v-model="form.strategyType" style="width: 160px">
              <el-option v-for="s in strategies" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item label="起始">
            <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" placeholder="起始日" />
          </el-form-item>
          <el-form-item label="结束">
            <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束日" />
          </el-form-item>
          <el-form-item label="T+N">
            <el-input-number v-model="form.horizon" :min="1" :max="3" :step="1" controls-position="right" style="width: 90px" />
          </el-form-item>
          <el-form-item label="每日topN">
            <el-input-number v-model="form.topN" :min="1" :max="50" :step="1" controls-position="right" style="width: 90px" />
          </el-form-item>
        </template>
      </el-form>

      <el-form inline class="form">
        <el-form-item label="初始资金">
          <el-input-number v-model="form.initialCash" :min="10000" :step="100000" controls-position="right" style="width: 140px" />
        </el-form-item>
        <el-form-item label="最大持仓">
          <el-input-number v-model="form.maxPositions" :min="1" :max="50" :step="1" controls-position="right" style="width: 90px" />
        </el-form-item>
        <el-form-item label="佣金率">
          <el-input-number v-model="form.commissionRate" :min="0" :max="0.01" :step="0.0001" :precision="4" controls-position="right" style="width: 110px" />
        </el-form-item>
        <el-form-item label="印花税率">
          <el-input-number v-model="form.sellTaxRate" :min="0" :max="0.01" :step="0.0001" :precision="4" controls-position="right" style="width: 110px" />
        </el-form-item>
        <el-form-item label="滑点率">
          <el-input-number v-model="form.slippageRate" :min="0" :max="0.01" :step="0.0001" :precision="4" controls-position="right" style="width: 110px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="running" @click="submitRun">运行组合回测</el-button>
          <el-button @click="() => loadHistory()">刷新历史</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="errorMsg" type="error" show-icon class="run-alert" :title="errorMsg" />
    </SectionCard>

    <template v-if="currentRunId">
    <SectionCard :title="`当前运行：${currentRunId}`">
        <div class="status-line">
          <span>run_id：{{ currentRunId }}</span>
          <el-tag :type="statusTagType" size="small">{{ status?.status || '...' }}</el-tag>
          <span v-if="status && status.status !== 'COMPLETED'">进度 {{ status.progress ?? 0 }}%</span>
          <el-progress
            v-if="status && status.status !== 'COMPLETED'"
            :percentage="status.progress ?? 0"
            :status="status.status === 'FAILED' ? 'exception' : undefined"
            class="progress"
          />
          <el-button v-if="status?.status === 'COMPLETED' && detail" size="small" @click="downloadCurrentRun">下载当前 run</el-button>
        </div>

        <el-alert
          v-if="status?.status === 'FAILED'"
          type="error"
          :closable="false"
          show-icon
          class="run-alert"
          :title="`回测执行失败：${status.error_message || '未知错误'}`"
        />

        <template v-if="detail">
          <div class="q-meta funnel-meta">
            <span>信号源：{{ detail.signal_source || '—' }}</span>
            <span>策略：{{ detail.strategy_type || '—' }}</span>
            <span>区间：{{ detail.start_date }} ~ {{ detail.end_date }}</span>
            <span>T+{{ detail.horizon }}</span>
            <span>初始资金：{{ fmtNum(detail.initial_cash) }}</span>
            <span>最大持仓：{{ detail.max_positions }}</span>
            <span>execution_version：{{ detail.execution_version }}</span>
            <span>storage_version：{{ detail.storage_version }}</span>
          </div>

          <div class="grid">
            <SectionCard title="净值概览" pad="sm" class="q-card">
              <div class="q-meta">
                <span>期末 NAV：<b>{{ fmt4(detail.final_nav) }}</b></span>
                <span>总收益：<b :class="pctClass(detail.total_return)">{{ fmtPct(detail.total_return) }}</b></span>
                <span>最大回撤：{{ fmtPct(detail.max_drawdown) }}</span>
                <span>成交笔数：{{ detail.total_trades }}</span>
                <span>拒单笔数：{{ detail.reject_count }}</span>
                <span>期末持仓：{{ detail.final_position_count }}</span>
                <span>未实现盈亏：{{ fmt4(detail.unrealized_pnl) }}</span>
              </div>
            </SectionCard>

            <SectionCard title="资金守恒审计（V2-MP-020）" pad="sm" class="q-card">
              <el-alert
                v-if="cashAudit"
                :type="cashAudit.balanced === false ? 'error' : 'success'"
                :closable="false"
                show-icon
                class="ic-note"
                :title="cashAudit.balanced === false ? '资金不守恒 —— 回测引擎缺陷' : '资金守恒校验通过'"
              />
              <div v-if="cashAudit" class="q-meta">
                <span>初始现金：{{ fmt4(cashAudit.initial_cash) }}</span>
                <span>买入流出：{{ fmt4(cashAudit.buy_cash_out) }}</span>
                <span>卖出流入：{{ fmt4(cashAudit.sell_cash_in) }}</span>
                <span>推算期末现金：{{ fmt4(cashAudit.computed_final_cash) }}</span>
                <span>实际期末现金：{{ fmt4(cashAudit.actual_final_cash) }}</span>
              </div>
            </SectionCard>
          </div>

          <SectionCard title="NAV 曲线" pad="sm" class="q-card chart-card">
            <div ref="chartRef" class="chart-box" />
            <div v-if="!detail.nav?.length" class="empty-tip">区间内无 NAV 记录</div>
          </SectionCard>

          <div class="grid">
            <SectionCard title="订单拒绝漏斗" pad="sm" class="q-card">
              <div v-if="rejectFunnelEntries.length" class="funnel-list">
                <div v-for="[reason, cnt] in rejectFunnelEntries" :key="reason" class="funnel-row">
                  <span class="funnel-reason">{{ reason }}</span>
                  <el-tag size="small" type="danger">{{ cnt }}</el-tag>
                </div>
              </div>
              <div v-else class="empty-tip">无拒单</div>
            </SectionCard>

            <SectionCard v-if="lineage" title="预测 lineage（闭环信号源）" pad="sm" class="q-card">
              <div class="q-meta">
                <span>prediction_run_id：{{ lineage.prediction_run_id }}</span>
                <span>信号日：{{ lineage.trade_date }}</span>
                <span>T+{{ lineage.horizon }} · {{ lineage.track }} · v{{ lineage.publish_version }}</span>
                <span>model：{{ lineage.model_version || '—' }}</span>
                <span>factor：{{ lineage.factor_version || '—' }}</span>
                <span>strategy：{{ lineage.strategy_type || '—' }}</span>
                <span>selector_result：{{ lineage.selector_result_id || '—' }}</span>
                <span>候选数：{{ lineage.item_count }}</span>
              </div>
            </SectionCard>
          </div>

          <SectionCard :title="`期末持仓（${detail.positions_count ?? 0}）`" pad="sm" class="q-card">
            <el-table :data="detail.open_positions ?? []" size="small" stripe border max-height="260">
              <el-table-column prop="stock_code" label="代码" width="90" />
              <el-table-column prop="stock_name" label="名称" width="110" />
              <el-table-column label="股数" width="80">
                <template #default="{ row }">{{ row.shares }}</template>
              </el-table-column>
              <el-table-column label="成本" width="90">
                <template #default="{ row }">{{ fmt4(row.entry_price) }}</template>
              </el-table-column>
              <el-table-column label="标记价" width="90">
                <template #default="{ row }">{{ fmt4(row.mark_price) }}</template>
              </el-table-column>
              <el-table-column prop="entry_date" label="买入日" width="100" />
              <el-table-column prop="exit_date" label="到期日" width="100" />
              <el-table-column prop="final_status" label="终态" width="150" />
              <el-table-column label="未实现盈亏" width="100">
                <template #default="{ row }">
                  <span :class="pctClass((row.unrealized_pnl ?? 0) >= 0 ? 1 : -1)">{{ fmt4(row.unrealized_pnl) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="price_source" label="标记来源" width="120" />
            </el-table>
          </SectionCard>

          <SectionCard :title="`订单明细（${ordersPage?.total ?? 0} 条，seq 升序分页）`" pad="sm" class="q-card">
            <el-table :data="ordersPage?.items ?? []" size="small" stripe border max-height="420">
              <el-table-column prop="seq" label="seq" width="60" />
              <el-table-column prop="action" label="方向" width="70">
                <template #default="{ row }">
                  <el-tag :type="row.action === 'BUY' ? 'danger' : 'success'" size="small">{{ row.action }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="trade_date" label="日期" width="100" />
              <el-table-column prop="stock_code" label="代码" width="90" />
              <el-table-column prop="stock_name" label="名称" width="110" />
              <el-table-column prop="status" label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'FILLED' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="reject_reason" label="拒单原因" min-width="160">
                <template #default="{ row }">{{ row.reject_reason || '—' }}</template>
              </el-table-column>
              <el-table-column label="价格" width="80">
                <template #default="{ row }">{{ row.price != null ? fmt4(row.price) : '—' }}</template>
              </el-table-column>
              <el-table-column label="股数" width="70">
                <template #default="{ row }">{{ row.shares ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="金额" width="90">
                <template #default="{ row }">{{ row.amount != null ? fmt4(row.amount) : '—' }}</template>
              </el-table-column>
              <el-table-column prop="commission" label="佣金" width="80">
                <template #default="{ row }">{{ row.commission != null ? fmt4(row.commission) : '—' }}</template>
              </el-table-column>
              <el-table-column prop="stamp_tax" label="印花税" width="80">
                <template #default="{ row }">{{ row.stamp_tax != null ? fmt4(row.stamp_tax) : '—' }}</template>
              </el-table-column>
              <el-table-column prop="event_time" label="时点" width="70" />
              <el-table-column prop="price_source" label="价格来源" width="120" />
            </el-table>
            <el-pagination
              v-if="(ordersPage?.total ?? 0) > 0"
              class="pager"
              layout="prev, pager, next, total"
              :total="ordersPage?.total ?? 0"
              :page-size="ORDERS_SIZE"
              :current-page="ordersPage?.page ?? 1"
              @current-change="(p: number) => loadOrders(p)"
            />
          </SectionCard>
        </template>
    </SectionCard>
      </template>

    <SectionCard title="运行历史">
      <el-table :data="history?.items ?? []" size="small" stripe border max-height="360">
        <el-table-column label="run_id" width="200">
          <template #default="{ row }">
            <el-link type="primary" @click="loadRun(row.run_id)">{{ row.run_id }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="historyStatusTag(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="prediction_run_id" label="prediction_run" min-width="180" show-overflow-tooltip />
        <el-table-column prop="strategy_type" label="策略" width="120" />
        <el-table-column label="区间" width="180">
          <template #default="{ row }">{{ row.start_date }} ~ {{ row.end_date }}</template>
        </el-table-column>
        <el-table-column label="T+N" width="50">
          <template #default="{ row }">{{ row.horizon }}</template>
        </el-table-column>
        <el-table-column label="期末NAV" width="100">
          <template #default="{ row }">{{ row.final_nav != null ? fmt4(row.final_nav) : '—' }}</template>
        </el-table-column>
        <el-table-column label="总收益" width="90">
          <template #default="{ row }">
            <span :class="pctClass(row.total_return)">{{ row.total_return != null ? fmtPct(row.total_return) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最大回撤" width="90">
          <template #default="{ row }">{{ row.max_drawdown != null ? fmtPct(row.max_drawdown) : '—' }}</template>
        </el-table-column>
        <el-table-column prop="total_trades" label="成交" width="60" />
        <el-table-column prop="reject_count" label="拒单" width="60" />
        <el-table-column prop="created_at" label="创建" width="150" show-overflow-tooltip />
        <el-table-column label="" width="70">
          <template #default="{ row }">
            <el-button size="small" @click="loadRun(row.run_id)">加载</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="(history?.total ?? 0) > 0"
        class="pager"
        layout="prev, pager, next, total"
        :total="history?.total ?? 0"
        :page-size="10"
        :current-page="history?.page ?? 1"
        @current-change="(p: number) => loadHistory(p)"
      />
    </SectionCard>
  </PageShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, nextTick } from 'vue'
import dayjs from 'dayjs'
import { echarts, CHART_THEME } from '@/utils/echartsTheme'
import {
  runPortfolioBacktest,
  getPortfolioBacktestStatus,
  getPortfolioBacktest,
  getPortfolioOrders,
  listPortfolioBacktests,
  type PortfolioBacktestRequest,
  type PortfolioSubmitResponse,
  type PortfolioRunStatus,
  type PortfolioRunDetail,
  type PortfolioOrderPage,
  type PortfolioOrderItem,
  type PortfolioRunList
} from '@/api/backtest'
import { getPredictionRuns, type PredictionTrack } from '@/api/predictions'
import { backtestApi } from '@/api/backtest'
import { errorText } from '@/api/request'
import PageShell from '@/components/ui/PageShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'

const ORDERS_SIZE = 50

const running = ref(false)
const loadingRuns = ref(false)
const errorMsg = ref('')
const currentRunId = ref('')
const status = ref<PortfolioRunStatus | null>(null)
const detail = ref<PortfolioRunDetail | null>(null)
const ordersPage = ref<PortfolioOrderPage | null>(null)
const history = ref<PortfolioRunList | null>(null)
const predictionRuns = ref<PredictionTrack[]>([])
const strategies = ref<string[]>(['CLASSIC_SELECTOR', 'OVERSOLD_REBOUND'])
const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let pollTimer: number | null = null

const form = reactive({
  mode: 'prediction' as 'prediction' | 'range',
  predictionRunId: '',
  startDate: dayjs().subtract(3, 'month').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD'),
  strategyType: 'CLASSIC_SELECTOR',
  horizon: 2,
  topN: 5,
  initialCash: 1_000_000,
  maxPositions: 5,
  commissionRate: 0.00025,
  sellTaxRate: 0.0005,
  slippageRate: 0.001
})

const publishedRuns = computed(() => predictionRuns.value.filter((r) => !!r.prediction_run_id))

function runLabel(r: PredictionTrack) {
  return `${r.trade_date} · T+${r.horizon} · ${r.track} · v${r.publish_version} · ${r.item_count ?? 0}只`
}

const statusTagType = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  const s = status.value?.status
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'RUNNING' || s === 'PENDING') return 'warning'
  return 'info'
})

function historyStatusTag(s?: string): 'success' | 'warning' | 'danger' | 'info' {
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'RUNNING' || s === 'PENDING') return 'warning'
  return 'info'
}

const cashAudit = computed(() => detail.value?.cash_audit ?? null)
const rejectFunnelEntries = computed(() => Object.entries(detail.value?.reject_funnel ?? {}))
const lineage = computed(() => (detail.value?.prediction_lineage as Record<string, unknown> | null | undefined) ?? null)

function fmt4(v?: unknown) {
  if (v == null || Number.isNaN(Number(v))) return '--'
  return Number(v).toFixed(4)
}
function fmtNum(v?: unknown) {
  if (v == null || Number.isNaN(Number(v))) return '--'
  return Number(v).toLocaleString('zh-CN')
}
function fmtPct(v?: unknown) {
  if (v == null || Number.isNaN(Number(v))) return '--'
  return `${(Number(v) * 100).toFixed(2)}%`
}
function pctClass(v?: unknown) {
  const n = v != null && !Number.isNaN(Number(v)) ? Number(v) : 0
  return n >= 0 ? 'text-up' : 'text-down'
}

async function loadPredictionRuns() {
  loadingRuns.value = true
  try {
    predictionRuns.value = await getPredictionRuns(50)
  } catch {
    predictionRuns.value = []
  } finally {
    loadingRuns.value = false
  }
}

async function submitRun() {
  running.value = true
  errorMsg.value = ''
  try {
    const body: PortfolioBacktestRequest = {
      initialCash: form.initialCash,
      maxPositions: form.maxPositions,
      commissionRate: form.commissionRate,
      sellTaxRate: form.sellTaxRate,
      slippageRate: form.slippageRate
    }
    if (form.mode === 'prediction') {
      if (!form.predictionRunId) {
        throw new Error('请选择已发布 prediction run（闭环模式）')
      }
      body.predictionRunId = form.predictionRunId
    } else {
      if (!form.startDate || !form.endDate) {
        throw new Error('区间模式需提供起始/结束日期')
      }
      if (form.startDate > form.endDate) {
        throw new Error('开始日期不能晚于结束日期')
      }
      body.startDate = form.startDate
      body.endDate = form.endDate
      body.strategyType = form.strategyType
      body.horizon = form.horizon
      body.topN = form.topN
    }
    const res: PortfolioSubmitResponse = await runPortfolioBacktest(body)
    currentRunId.value = res.run_id || ''
    detail.value = null
    ordersPage.value = null
    status.value = { run_id: currentRunId.value, status: 'RUNNING', progress: 0 }
    if (currentRunId.value) {
      startPolling()
    } else {
      errorMsg.value = '提交成功但未返回 run_id'
    }
  } catch (e: unknown) {
    errorMsg.value = errorText(e)
  } finally {
    running.value = false
  }
}

function startPolling() {
  stopPolling()
  poll()
  pollTimer = window.setInterval(poll, 1500)
}

function stopPolling() {
  if (pollTimer != null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function poll() {
  if (!currentRunId.value) return
  try {
    status.value = await getPortfolioBacktestStatus(currentRunId.value)
    const s = status.value?.status
    if (s === 'COMPLETED') {
      stopPolling()
      await loadDetail()
    } else if (s === 'FAILED') {
      stopPolling()
      errorMsg.value = status.value?.error_message || '回测执行失败'
    }
  } catch {
    // 瞬态错误（服务重启/网络抖动）继续轮询
  }
}

async function loadDetail() {
  if (!currentRunId.value) return
  try {
    detail.value = await getPortfolioBacktest(currentRunId.value)
    status.value = { run_id: currentRunId.value, status: detail.value?.status, progress: detail.value?.progress }
    await loadOrders(1)
    await nextTick()
    renderChart()
  } catch (e: unknown) {
    errorMsg.value = errorText(e)
  }
}

async function loadOrders(page = 1) {
  if (!currentRunId.value) return
  try {
    ordersPage.value = await getPortfolioOrders(currentRunId.value, page, ORDERS_SIZE)
  } catch {
    ordersPage.value = null
  }
}

async function loadHistory(page = 1) {
  try {
    history.value = await listPortfolioBacktests(page, 10)
  } catch {
    history.value = null
  }
}

function loadRun(runId: string) {
  currentRunId.value = runId
  stopPolling()
  status.value = null
  detail.value = null
  ordersPage.value = null
  errorMsg.value = ''
  loadDetail()
}

function renderChart() {
  if (!chartRef.value || !detail.value?.nav?.length) return
  chart?.dispose()
  chart = echarts.init(chartRef.value, CHART_THEME)
  const dates = detail.value.nav.map((d) => d.trade_date)
  const nav = detail.value.nav.map((d) => Number(d.nav))
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 45 } },
    yAxis: { type: 'value', scale: true },
    series: [{ name: 'NAV', type: 'line', data: nav, smooth: true, areaStyle: { opacity: 0.08 } }]
  })
}

async function fetchAllOrders(runId: string): Promise<PortfolioOrderItem[]> {
  const all: PortfolioOrderItem[] = []
  let page = 1
  for (;;) {
    const pg = await getPortfolioOrders(runId, page, 500)
    all.push(...(pg.items ?? []))
    const totalPages = pg.total_pages ?? 0
    if (page >= totalPages || totalPages === 0) break
    page++
  }
  return all
}

function triggerDownload(filename: string, content: string) {
  const blob = new Blob([content], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

async function downloadCurrentRun() {
  if (!currentRunId.value || !detail.value) return
  try {
    const orders = await fetchAllOrders(currentRunId.value)
    const payload = {
      run_id: currentRunId.value,
      summary: detail.value,
      nav: detail.value.nav ?? [],
      open_positions: detail.value.open_positions ?? [],
      orders
    }
    triggerDownload(`portfolio_${currentRunId.value}.json`, JSON.stringify(payload, null, 2))
  } catch (e: unknown) {
    errorMsg.value = errorText(e)
  }
}

onMounted(async () => {
  loadPredictionRuns()
  loadHistory()
  try {
    const list = await backtestApi.getStrategies()
    if (list.length) strategies.value = list
  } catch {
    // 保持默认策略列表
  }
})

onBeforeUnmount(() => {
  stopPolling()
  chart?.dispose()
})
</script>

<style scoped>
.beta-tag { margin-left: 8px; vertical-align: middle; }
.note-alert { margin-bottom: 12px; }
.form { margin-bottom: 4px; }
.run-alert { margin-bottom: 12px; }
.status-line { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px; color: var(--text-secondary); }
.progress { width: 200px; }
.funnel-meta { margin-bottom: 8px; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 12px; }
.chart-card { margin-bottom: 12px; }
.chart-box { height: 300px; }
.empty-tip { color: var(--text-dim); font-size: 13px; padding: 12px 0; }
.funnel-list { display: flex; flex-direction: column; gap: 6px; }
.funnel-row { display: flex; align-items: center; justify-content: space-between; padding: 2px 0; }
.funnel-reason { font-family: monospace; font-size: 12px; color: var(--text-secondary); }
.pager { margin-top: 10px; justify-content: flex-end; }
.run-tag { margin-left: 8px; }
.q-card { margin-bottom: 12px; }
</style>
