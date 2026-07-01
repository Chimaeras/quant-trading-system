<template>
  <el-card shadow="hover" class="terminal-page">
    <template #header>
      <div class="header-row">
        <span>打分策略回测系统</span>
      </div>
    </template>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="disclaimer-alert"
      title="回测说明"
      description="本页统计基于历史选股结果与当前库内行情，未按历史时点股票池复现，存在幸存者偏差风险；收益为模型假设下的净收益，不代表未来表现或投资建议。"
    />

    <!-- 参数配置 -->
    <div class="section-title">回测参数配置</div>
    <el-form inline>
      <el-form-item label="策略类型">
        <el-select v-model="form.strategyType" placeholder="全部策略" style="width: 200px" :loading="strategiesLoading" clearable>
          <el-option label="全部策略" value="" />
          <el-option v-for="s in strategies" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="起始日期">
        <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="结束日期">
        <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="每日选取TopN">
        <el-input-number v-model="form.topN" :min="1" :max="100" :step="1" />
      </el-form-item>
      <el-form-item label="因子分组数">
        <el-input-number v-model="form.factorGroupCount" :min="2" :max="10" :step="1" />
      </el-form-item>
    </el-form>
    <el-form inline class="holding-form">
      <el-form-item label="持仓周期(交易日)">
        <el-checkbox-group v-model="form.holdingDays">
          <el-checkbox :value="1">T+1</el-checkbox>
          <el-checkbox :value="3">T+3</el-checkbox>
          <el-checkbox :value="5">T+5</el-checkbox>
          <el-checkbox :value="10">T+10</el-checkbox>
          <el-checkbox :value="20">T+20</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="runBacktest">执行回测</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="!response"
      type="info"
      :closable="false"
      show-icon
      class="run-alert"
      title="收益按信号后下一交易日开盘买入、持有期末收盘卖出计算，并扣除双边佣金、卖出税费和双边滑点。"
    />

    <el-alert v-if="errorMsg" type="error" show-icon :closable="true" class="run-alert" @close="errorMsg = ''">
      {{ errorMsg }}
    </el-alert>

    <!-- 回测结果 -->
    <template v-if="response">
      <div class="result-meta">
        <span>策略：{{ response.strategyType }}</span>
        <span>日期：{{ response.startDate }} ~ {{ response.endDate }}</span>
        <span>覆盖交易日：{{ response.totalTradingDays }} 天</span>
        <span>每日前 {{ response.topN }} 只</span>
      </div>

      <el-alert
        v-if="response.executionAssumptions"
        type="info"
        :closable="false"
        class="run-alert assumptions-alert"
        title="成交假设"
      >
        <div class="assumptions">
          <span v-for="(value, key) in response.executionAssumptions" :key="String(key)">
            {{ formatAssumptionKey(String(key)) }}：{{ formatAssumptionValue(value, String(key)) }}
          </span>
        </div>
      </el-alert>

      <el-alert
        v-if="response.formulaAudit"
        :type="formulaAuditAlertType"
        :closable="false"
        show-icon
        class="run-alert formula-audit-alert"
        title="策略公式审计（3～5 日预测适配）"
      >
        <div class="formula-audit">
          <p><strong>公式：</strong>{{ response.formulaAudit.final_score_formula }}</p>
          <p><strong>结论：</strong>{{ response.formulaAudit.short_horizon_verdict }}</p>
          <p>短周期适配分：{{ response.formulaAudit.short_horizon_fit_score }}/100</p>
          <ul v-if="formulaAuditTips.length" class="formula-tips">
            <li v-for="(tip, idx) in formulaAuditTips" :key="idx">{{ tip }}</li>
          </ul>
        </div>
      </el-alert>

      <el-alert
        v-if="response.shortHorizonValidation"
        :type="shortHorizonValidationType"
        :closable="false"
        show-icon
        class="run-alert"
        title="3～5 日预测力验证（Rank IC）"
      >
        <div class="formula-audit">
          <p>{{ response.shortHorizonValidation.verdict }}</p>
          <p v-if="response.executionAssumptions?.codex_neutralization">
            中性化：{{ response.executionAssumptions.codex_neutralization }}
          </p>
          <p>
            T+3 IC：{{ formatIcMean(response.shortHorizonValidation.t3_rank_ic_mean) }}
            · T+5 IC：{{ formatIcMean(response.shortHorizonValidation.t5_rank_ic_mean) }}
            · 置信：{{ response.shortHorizonValidation.confidence }}
          </p>
          <p v-if="response.shortHorizonValidation.codex_sub_factors_available">
            Codex 子因子 IC（T+3/T+5）：
            <span v-for="key in ['T', 'F', 'R']" :key="key" class="codex-ic-chip">
              {{ key }}:
              {{ formatIcMean(codexSubIc(key, 3)) }}/{{ formatIcMean(codexSubIc(key, 5)) }}
            </span>
          </p>
        </div>
      </el-alert>

      <div v-if="codexFactorTableRows.length" class="section-title">Codex T/F/R 子因子 Rank IC</div>
      <el-table v-if="codexFactorTableRows.length" :data="codexFactorTableRows" stripe size="small" class="codex-ic-table">
        <el-table-column prop="factor" label="因子" width="80" />
        <el-table-column prop="h3" label="T+3 IC" min-width="100" />
        <el-table-column prop="h5" label="T+5 IC" min-width="100" />
        <el-table-column prop="h10" label="T+10 IC" min-width="100" />
      </el-table>

      <!-- 汇总统计卡片 -->
      <div class="section-title">各持仓周期汇总</div>
      <div class="stats-grid">
        <div v-for="(stat, day) in response.holdingPeriodStats" :key="day" class="stat-card glass">
          <div class="stat-header">T+{{ day }}</div>
          <div class="stat-body">
            <div class="stat-item">
              <span class="stat-label">胜率</span>
              <span class="stat-value" :class="stat.winRate >= 0.5 ? 'text-success' : 'text-danger'">
                {{ (stat.winRate * 100).toFixed(1) }}%
              </span>
            </div>
            <div class="stat-item">
              <span class="stat-label">平均收益</span>
              <span class="stat-value" :class="stat.avgReturn >= 0 ? 'text-success' : 'text-danger'">
                {{ (stat.avgReturn * 100).toFixed(2) }}%
              </span>
            </div>
            <div class="stat-item">
              <span class="stat-label">中位数收益</span>
              <span class="stat-value" :class="stat.medianReturn >= 0 ? 'text-success' : 'text-danger'">
                {{ (stat.medianReturn * 100).toFixed(2) }}%
              </span>
            </div>
            <div class="stat-item">
              <span class="stat-label">最大收益</span>
              <span class="stat-value text-success">{{ (stat.maxReturn * 100).toFixed(2) }}%</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">最小收益</span>
              <span class="stat-value text-danger">{{ (stat.minReturn * 100).toFixed(2) }}%</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">观测数</span>
              <span class="stat-value">{{ stat.totalObservations }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 截面因子研究 -->
      <template v-if="response.factorAnalysis">
        <div class="section-title">截面因子研究（T+{{ response.factorAnalysis.holdingDays }}）</div>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          class="run-alert"
          :title="response.factorAnalysis.universeNote"
        />
        <div class="stats-grid factor-ic-grid">
          <div class="stat-card glass">
            <div class="stat-header">Rank IC 均值</div>
            <div class="stat-body">
              <div class="stat-item">
                <span class="stat-label">IC Mean</span>
                <span class="stat-value" :class="response.factorAnalysis.rankIcMean >= 0 ? 'text-success' : 'text-danger'">
                  {{ response.factorAnalysis.rankIcMean.toFixed(4) }}
                </span>
              </div>
              <div class="stat-item">
                <span class="stat-label">IC Std</span>
                <span class="stat-value">{{ response.factorAnalysis.rankIcStd.toFixed(4) }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">ICIR</span>
                <span class="stat-value">{{ response.factorAnalysis.rankIcIr.toFixed(3) }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">IC&gt;0 占比</span>
                <span class="stat-value">{{ (response.factorAnalysis.rankIcPositiveRate * 100).toFixed(1) }}%</span>
              </div>
            </div>
          </div>
          <div class="stat-card glass">
            <div class="stat-header">样本覆盖</div>
            <div class="stat-body">
              <div class="stat-item">
                <span class="stat-label">截面日数</span>
                <span class="stat-value">{{ response.factorAnalysis.crossSectionDays }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">总观测数</span>
                <span class="stat-value">{{ response.factorAnalysis.totalObservations }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">分组数</span>
                <span class="stat-value">{{ response.factorAnalysis.groupCount }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">多空 Spread</span>
                <span class="stat-value" :class="response.factorAnalysis.longShortSpread >= 0 ? 'text-success' : 'text-danger'">
                  {{ (response.factorAnalysis.longShortSpread * 100).toFixed(2) }}%
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="section-title">分组平均收益</div>
        <div ref="factorChartRef" class="chart-box"></div>
        <el-table :data="response.factorAnalysis.groupedReturns" stripe size="small" class="data-table" max-height="260">
          <el-table-column prop="label" label="分组" width="120" />
          <el-table-column label="平均收益" width="120">
            <template #default="{ row }">
              <span :class="row.avgReturn >= 0 ? 'text-success' : 'text-danger'">
                {{ (row.avgReturn * 100).toFixed(2) }}%
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="observations" label="截面日观测" />
        </el-table>

        <template v-if="icByHoldingRows.length">
          <div class="section-title">多持有期 IC 对比</div>
          <div ref="icByHoldingChartRef" class="chart-box"></div>
          <el-table :data="icByHoldingRows" stripe size="small" class="data-table" max-height="220">
            <el-table-column prop="label" label="持有期" width="90" />
            <el-table-column label="IC Mean" width="100">
              <template #default="{ row }">
                <span :class="row.rankIcMean >= 0 ? 'text-success' : 'text-danger'">{{ row.rankIcMean.toFixed(4) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="ICIR" width="90">
              <template #default="{ row }">{{ row.rankIcIr.toFixed(3) }}</template>
            </el-table-column>
            <el-table-column label="多空 Spread" width="110">
              <template #default="{ row }">
                <span :class="row.longShortSpread >= 0 ? 'text-success' : 'text-danger'">
                  {{ (row.longShortSpread * 100).toFixed(2) }}%
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="crossSectionDays" label="截面日" width="80" />
          </el-table>
        </template>

        <template v-if="response.factorAnalysis.dailyRankIc?.length">
          <div class="section-title">每日 Rank IC 时序</div>
          <div ref="icSeriesChartRef" class="chart-box"></div>
        </template>
      </template>

      <!-- 胜率柱状图 -->
      <div class="section-title">胜率对比</div>
      <div ref="chartRef" class="chart-box"></div>

      <!-- 每日明细 -->
      <div class="section-title">
        每日回测明细
        <el-button size="small" style="margin-left: 12px" @click="exportCsv">导出 CSV</el-button>
      </div>
      <el-table :data="dailyPage" stripe size="small" class="data-table" max-height="500">
        <el-table-column prop="tradeDate" label="交易日期" width="120" sortable />
        <el-table-column v-for="day in form.holdingDays" :key="day" :label="'T+' + day + ' 胜率'" width="110">
          <template #default="{ row }">
            <span v-if="row.dailyWinRate[day] != null" :class="row.dailyWinRate[day] >= 0.5 ? 'text-success' : 'text-danger'">
              {{ (row.dailyWinRate[day] * 100).toFixed(1) }}%
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-box" v-if="dailyPageTotal > pageSize">
        <el-pagination
          v-model:current-page="dailyPageNum"
          :page-size="pageSize"
          :total="dailyPageTotal"
          layout="prev, pager, next"
          background
          small
        />
      </div>
    </template>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" :title="'回测明细 - ' + detailDate" width="900px" destroy-on-close>
      <el-table :data="detailStocks" stripe size="small" max-height="400">
        <el-table-column prop="rankNo" label="排名" width="60" />
        <el-table-column prop="stockCode" label="代码" width="100" />
        <el-table-column prop="stockName" label="名称" width="100" />
        <el-table-column prop="finalScore" label="得分" width="80" sortable />
        <el-table-column prop="skipReason" label="跳过原因" min-width="140">
          <template #default="{ row }">
            <span v-if="row.skipReason">{{ formatSkipReason(row.skipReason) }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column v-for="day in form.holdingDays" :key="day" :label="'T+' + day" width="110">
          <template #default="{ row: stock }">
            <span v-if="stock.forwardReturns[day] != null" :class="stock.forwardReturns[day] >= 0 ? 'text-success' : 'text-danger'">
              {{ (stock.forwardReturns[day] * 100).toFixed(2) }}%
            </span>
            <span v-else-if="stock.periodSkipReasons?.[day]" class="text-muted skip-hint">
              {{ formatSkipReason(stock.periodSkipReasons[day]) }}
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { backtestApi, type ScoringBacktestResponse, type DailyDetail, type StockReturn } from '@/api/backtest'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const running = ref(false)
const errorMsg = ref('')
const strategies = ref<string[]>([])
const strategiesLoading = ref(false)
const response = ref<ScoringBacktestResponse | null>(null)

const form = reactive({
  strategyType: '',
  startDate: dayjs().subtract(6, 'month').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD'),
  topN: 10,
  holdingDays: [3, 5, 10, 20],
  factorGroupCount: 5
})

const icByHoldingRows = computed(() => {
  const map = response.value?.factorIcByHolding
  if (!map) return []
  return Object.keys(map)
    .map(Number)
    .sort((a, b) => a - b)
    .map((d) => ({
      holdingDays: d,
      label: 'T+' + d,
      ...map[d]
    }))
})

const formulaAuditTips = computed(() => {
  const tips = response.value?.formulaAudit?.usage_tips
  return Array.isArray(tips) ? (tips as string[]) : []
})

const formulaAuditAlertType = computed(() => {
  const score = Number(response.value?.formulaAudit?.short_horizon_fit_score ?? 0)
  if (score >= 75) return 'success'
  if (score >= 50) return 'warning'
  return 'error'
})

const shortHorizonValidationType = computed(() => {
  const v = response.value?.shortHorizonValidation
  if (!v) return 'info'
  if (v.t3_effective || v.t5_effective) return 'success'
  if (v.t3_rank_ic_mean != null || v.t5_rank_ic_mean != null) return 'warning'
  return 'info'
})

const codexFactorTableRows = computed(() => {
  const map = response.value?.codexFactorIcByHolding
  if (!map) return []
  return ['T', 'F', 'R'].map((factor) => ({
    factor,
    h3: formatIcMean(map[3]?.[factor]?.rankIcMean),
    h5: formatIcMean(map[5]?.[factor]?.rankIcMean),
    h10: formatIcMean(map[10]?.[factor]?.rankIcMean)
  }))
})

function codexSubIc(factor: string, holding: number) {
  const v = response.value?.shortHorizonValidation?.codex_sub_factor_ic as Record<string, unknown> | undefined
  if (!v) return null
  return v[`${factor}_t${holding}_ic`]
}

function formatIcMean(v: unknown) {
  if (v == null || v === '') return '-'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(4) : '-'
}

const skipReasonLabels: Record<string, string> = {
  NO_QUOTE_ON_ENTRY_DAY: '入场日无行情（可能停牌）',
  INVALID_ENTRY_OPEN: '入场开盘价无效',
  ZERO_VOLUME_ON_ENTRY: '入场日无成交量（疑似停牌）',
  LIMIT_UP_AT_OPEN: '涨停开盘无法买入',
  NO_NEXT_TRADING_DAY: '无下一交易日',
  BEYOND_CALENDAR: '超出交易日历',
  NO_QUOTE_ON_EXIT_DAY: '出场日无行情（可能停牌）',
  INVALID_EXIT_CLOSE: '出场收盘价无效',
  ZERO_VOLUME_ON_EXIT: '出场日无成交量（疑似停牌）',
  LIMIT_DOWN_AT_CLOSE: '跌停收盘无法卖出'
}

function formatSkipReason(code: string) {
  return skipReasonLabels[code] ?? code
}

const assumptionLabels: Record<string, string> = {
  entry: '买入成交',
  exit: '卖出成交',
  t_plus_one_sell: 'T+1 卖出',
  holding_period_semantics: '持有期语义',
  commission_rate: '佣金费率',
  sell_tax_rate: '卖出税费',
  slippage_rate_each_side: '单边滑点',
  return_type: '收益口径',
  limit_up_entry_blocked: '涨停开盘不买',
  limit_down_exit_blocked: '跌停收盘不卖',
  limit_pct_source: '涨跌停幅度来源',
  skip_reason_codes: '跳过原因码',
  selector_tradability: '选股可交易性假设',
  short_horizon_research_focus: '短周期研究重点'
}

const tradabilityAssumptionLabels: Record<string, string> = {
  exclude_st: '排除 ST',
  exclude_zero_volume: '排除零成交量',
  exclude_limit_up_at_close: '排除涨停收盘',
  exclude_limit_down_at_close: '排除跌停收盘',
  min_listing_days: '最短上市天数',
  listing_tenure_basis: '上市时长口径',
  limit_pct_source: '涨跌停幅度来源',
  limit_price_basis: '涨跌停价基准'
}

function formatAssumptionKey(key: string) {
  return assumptionLabels[key] ?? key
}

function formatAssumptionValue(value: unknown, key?: string): string {
  if (key === 'selector_tradability' && value && typeof value === 'object' && !Array.isArray(value)) {
    return Object.entries(value as Record<string, unknown>)
      .map(([k, v]) => `${tradabilityAssumptionLabels[k] || k}=${formatAssumptionValue(v)}`)
      .join('；')
  }
  if (Array.isArray(value)) return value.join(', ')
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'number' && value > 0 && value < 1) return `${(value * 100).toFixed(2)}%`
  return String(value)
}

// 图表
const chartRef = ref<HTMLElement>()
const factorChartRef = ref<HTMLElement>()
const icByHoldingChartRef = ref<HTMLElement>()
const icSeriesChartRef = ref<HTMLElement>()
let chart: echarts.ECharts | null = null
let factorChart: echarts.ECharts | null = null
let icByHoldingChart: echarts.ECharts | null = null
let icSeriesChart: echarts.ECharts | null = null

function renderFactorChart() {
  if (!factorChartRef.value || !response.value?.factorAnalysis) return
  const groups = response.value.factorAnalysis.groupedReturns
  if (!groups.length) return
  if (!factorChart) factorChart = echarts.init(factorChartRef.value)
  factorChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const p = params[0]
        const g = groups[p.dataIndex]
        return [`<b>${g.label}</b>`, `平均收益：${(g.avgReturn * 100).toFixed(2)}%`, `截面日：${g.observations}`].join('<br/>')
      }
    },
    grid: { left: '8%', right: '8%', top: '10%', bottom: '10%' },
    xAxis: {
      type: 'category',
      data: groups.map((g) => g.label),
      axisLabel: { color: '#9db1cc', interval: 0, rotate: groups.length > 5 ? 20 : 0 }
    },
    yAxis: {
      type: 'value',
      name: '收益 (%)',
      axisLabel: { color: '#9db1cc', formatter: '{value}%' },
      splitLine: { lineStyle: { color: 'rgba(145,196,255,0.08)' } }
    },
    series: [
      {
        name: '平均收益',
        type: 'bar',
        data: groups.map((g) => +(g.avgReturn * 100).toFixed(2)),
        itemStyle: {
          color: (params: { dataIndex: number }) => {
            const v = groups[params.dataIndex].avgReturn
            return v >= 0 ? '#1cd6a6' : '#ff6b6b'
          }
        },
        barWidth: '50%',
        label: {
          show: true,
          position: 'top',
          color: '#e9f1ff',
          formatter: '{c}%'
        }
      }
    ]
  })
}

function renderIcByHoldingChart() {
  if (!icByHoldingChartRef.value || !icByHoldingRows.value.length) return
  const rows = icByHoldingRows.value
  if (!icByHoldingChart) icByHoldingChart = echarts.init(icByHoldingChartRef.value)
  icByHoldingChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: '8%', right: '8%', top: '10%', bottom: '10%' },
    xAxis: {
      type: 'category',
      data: rows.map((r) => r.label),
      axisLabel: { color: '#9db1cc' }
    },
    yAxis: [
      {
        type: 'value',
        name: 'IC Mean',
        axisLabel: { color: '#9db1cc' },
        splitLine: { lineStyle: { color: 'rgba(145,196,255,0.08)' } }
      },
      {
        type: 'value',
        name: 'Spread %',
        axisLabel: { color: '#9db1cc', formatter: '{value}%' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: 'IC Mean',
        type: 'bar',
        data: rows.map((r) => +r.rankIcMean.toFixed(4)),
        itemStyle: { color: '#58b9ff' },
        barWidth: '35%'
      },
      {
        name: '多空 Spread',
        type: 'line',
        yAxisIndex: 1,
        data: rows.map((r) => +(r.longShortSpread * 100).toFixed(2)),
        lineStyle: { color: '#f0b95a', width: 2 },
        itemStyle: { color: '#f0b95a' }
      }
    ]
  })
}

function renderIcSeriesChart() {
  const series = response.value?.factorAnalysis?.dailyRankIc
  if (!icSeriesChartRef.value || !series?.length) return
  if (!icSeriesChart) icSeriesChart = echarts.init(icSeriesChartRef.value)
  icSeriesChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const p = params[0]
        const row = series[p.dataIndex]
        return [`<b>${row.tradeDate}</b>`, `Rank IC：${row.rankIc.toFixed(4)}`, `样本：${row.sampleSize}`].join('<br/>')
      }
    },
    grid: { left: '8%', right: '8%', top: '10%', bottom: '14%' },
    xAxis: {
      type: 'category',
      data: series.map((d) => d.tradeDate),
      axisLabel: { color: '#9db1cc', rotate: 35, fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      name: 'Rank IC',
      axisLabel: { color: '#9db1cc' },
      splitLine: { lineStyle: { color: 'rgba(145,196,255,0.08)' } }
    },
    series: [
      {
        name: 'Rank IC',
        type: 'line',
        data: series.map((d) => +d.rankIc.toFixed(4)),
        lineStyle: { color: '#1cd6a6', width: 2 },
        itemStyle: { color: '#1cd6a6' },
        areaStyle: { color: 'rgba(28,214,166,0.08)' },
        markLine: {
          silent: true,
          data: [{ yAxis: 0, lineStyle: { color: 'rgba(145,196,255,0.35)' } }]
        }
      }
    ]
  })
}

function renderChart() {
  if (!chartRef.value || !response.value) return
  const stats = response.value.holdingPeriodStats
  const days = Object.keys(stats).map(Number).sort((a, b) => a - b)
  const winRates = days.map((d) => stats[d].winRate * 100)
  const avgReturns = days.map((d) => stats[d].avgReturn * 100)

  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const day = params[0].name
        const stat = stats[parseInt(day.replace('T+', ''))]
        return [
          `<b>${day}</b>`,
          `胜率：${(stat.winRate * 100).toFixed(1)}%`,
          `平均收益：${(stat.avgReturn * 100).toFixed(2)}%`,
          `中位数：${(stat.medianReturn * 100).toFixed(2)}%`,
          `最大：${(stat.maxReturn * 100).toFixed(2)}%  /  最小：${(stat.minReturn * 100).toFixed(2)}%`,
          `观测数：${stat.totalObservations}`
        ].join('<br/>')
      }
    },
    grid: { left: '8%', right: '8%', top: '10%', bottom: '10%' },
    xAxis: {
      type: 'category',
      data: days.map((d) => 'T+' + d),
      axisLabel: { color: '#9db1cc' }
    },
    yAxis: [
      {
        type: 'value',
        name: '胜率 (%)',
        min: 0,
        max: 100,
        axisLabel: { color: '#9db1cc', formatter: '{value}%' },
        splitLine: { lineStyle: { color: 'rgba(145,196,255,0.08)' } }
      },
      {
        type: 'value',
        name: '收益 (%)',
        axisLabel: { color: '#9db1cc', formatter: '{value}%' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '胜率',
        type: 'bar',
        data: winRates,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#58b9ff' },
            { offset: 1, color: '#1cd6a6' }
          ])
        },
        barWidth: '40%',
        label: {
          show: true,
          position: 'top',
          color: '#e9f1ff',
          formatter: '{c:.1f}%'
        }
      },
      {
        name: '平均收益',
        type: 'line',
        yAxisIndex: 1,
        data: avgReturns,
        lineStyle: { color: '#f0b95a', width: 2 },
        itemStyle: { color: '#f0b95a' },
        symbol: 'circle',
        symbolSize: 8,
        label: {
          show: true,
          position: 'top',
          color: '#f0b95a',
          formatter: '{c:.2f}%'
        }
      }
    ]
  })
}

watch(response, () => {
  nextTick(() => {
    renderChart()
    renderFactorChart()
    renderIcByHoldingChart()
    renderIcSeriesChart()
  })
})

// 分页
const dailyPageNum = ref(1)
const pageSize = 20
const dailyPageTotal = computed(() => response.value?.dailyDetails?.length ?? 0)
const dailyPage = computed(() => {
  const details = response.value?.dailyDetails ?? []
  const start = (dailyPageNum.value - 1) * pageSize
  return details.slice(start, start + pageSize)
})

watch(response, () => {
  dailyPageNum.value = 1
})

// 详情弹窗
const detailVisible = ref(false)
const detailDate = ref('')
const detailStocks = ref<StockReturn[]>([])

function showDetail(row: DailyDetail) {
  detailDate.value = row.tradeDate
  detailStocks.value = row.topStocks
  detailVisible.value = true
}

async function runBacktest() {
  if (!form.startDate || !form.endDate) {
    errorMsg.value = '请选择起始和结束日期'
    return
  }
  if (form.holdingDays.length === 0) {
    errorMsg.value = '请至少选择一个持仓周期'
    return
  }
  running.value = true
  errorMsg.value = ''
  response.value = null
  try {
    const data = await backtestApi.runBacktest({
      strategyType: form.strategyType || undefined,
      startDate: form.startDate,
      endDate: form.endDate,
      topN: form.topN,
      holdingDays: form.holdingDays,
      factorGroupCount: form.factorGroupCount,
      commissionRate: 0.0003,
      sellTaxRate: 0.0005,
      slippageRate: 0.0002
    })
    if (!data.totalTradingDays) {
      toast.info('该条件下无回测数据')
    }
    response.value = data
  } catch (e: any) {
    errorMsg.value = e?.message || '回测执行失败'
    toast.error(errorMsg.value)
  } finally {
    running.value = false
  }
}

async function loadStrategies() {
  strategiesLoading.value = true
  try {
    strategies.value = await backtestApi.getStrategies()
  } catch {
    // silent
  } finally {
    strategiesLoading.value = false
  }
}

function exportCsv() {
  if (!response.value) return
  const details = response.value.dailyDetails
  if (!details.length) return
  const holdingDays = form.holdingDays
  const headers = ['tradeDate', ...holdingDays.map((d) => 'T+' + d + '_winRate')]
  const lines = [headers.join(',')]
  for (const row of details) {
    const cells = [row.tradeDate, ...holdingDays.map((d) => row.dailyWinRate[d] ?? '')]
    lines.push(cells.join(','))
  }
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `backtest-${response.value.strategyType || 'all'}-${response.value.startDate}_${response.value.endDate}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

// resize
function onResize() {
  chart?.resize()
  factorChart?.resize()
  icByHoldingChart?.resize()
  icSeriesChart?.resize()
}

onMounted(() => {
  loadStrategies()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  factorChart?.dispose()
  icByHoldingChart?.dispose()
  icSeriesChart?.dispose()
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

.disclaimer-alert {
  margin-bottom: 16px;
}

.section-title {
  margin: 6px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.holding-form {
  margin-top: -4px;
}

.run-alert {
  margin: 0 0 12px;
}

.result-meta {
  display: flex;
  gap: 20px;
  margin: 8px 0 4px;
  color: var(--text-secondary);
  font-size: 13px;
  flex-wrap: wrap;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.factor-ic-grid {
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
}

.stat-card {
  border-radius: var(--r-md);
  padding: 12px 14px;
}

.stat-header {
  font-size: var(--fz-lg);
  font-weight: 600;
  color: var(--accent-1);
  margin-bottom: 8px;
}

.stat-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.stat-label {
  font-size: var(--fz-xs);
  color: var(--text-secondary);
}

.stat-value {
  font-size: var(--fz-sm);
  font-weight: var(--fw-num);
  font-variant-numeric: tabular-nums;
}

.text-success { color: var(--text-success); }
.text-danger { color: var(--text-danger); }
.text-muted { color: var(--text-dim); }

.chart-box {
  width: 100%;
  height: 300px;
  margin-bottom: 16px;
}

.data-table {
  margin-bottom: 8px;
}

.pagination-box {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}

.run-alert {
  margin-bottom: 12px;
}

.assumptions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  font-size: 12px;
}

.formula-audit {
  font-size: 13px;
  line-height: 1.6;
}

.formula-audit p {
  margin: 4px 0;
}

.formula-tips {
  margin: 8px 0 0 18px;
  padding: 0;
}

.assumptions-alert :deep(.el-alert__content) {
  width: 100%;
}
</style>
