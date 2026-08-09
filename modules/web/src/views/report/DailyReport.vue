<template>
  <div class="daily-report terminal-page">
    <el-card shadow="hover">
      <template #header>
        <div class="report-header">
          <span>选股日报（prediction snapshot 不可变投影 · MP-012）</span>
          <div class="header-actions">
            <el-date-picker
              v-model="tradeDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="留空取最新日期"
              clearable
              style="width: 158px"
            />
            <el-select v-model="horizon" style="width: 92px">
              <el-option label="T+2" :value="2" />
              <el-option label="T+3" :value="3" />
            </el-select>
            <el-select v-model="track" style="width: 116px">
              <el-option label="规则轨" value="RULE_SCORE" />
              <el-option label="概率轨" value="PROBABILITY" />
            </el-select>
            <el-select
              v-model="publishVersion"
              placeholder="版本"
              clearable
              style="width: 158px"
            >
              <el-option label="最新" :value="0" />
              <el-option
                v-for="v in versions"
                :key="v.publish_version"
                :label="versionLabel(v)"
                :value="v.publish_version ?? 0"
              />
            </el-select>
            <el-button type="primary" size="small" :loading="loading" @click="loadReport">生成日报</el-button>
          </div>
        </div>
      </template>

      <!-- V2-MP-028：查询失败给出可判定的错误态（errorId + retry），不吞成无数据/空表。 -->
      <el-alert
        v-if="reportError"
        type="error"
        show-icon
        :closable="false"
        class="verdict-alert"
        title="日报加载失败"
        :description="reportError"
      >
        <template #default>
          <el-button size="small" :loading="loading" @click="loadReport">重试</el-button>
        </template>
      </el-alert>

      <div v-loading="loading">
        <template v-if="report">
          <el-alert
            v-if="report.status === 'PUBLISHED'"
            type="success"
            :closable="false"
            show-icon
            class="verdict-alert"
            :title="`已发布 · ${report.item_count ?? 0} 条候选 · content_hash=${shortHash(report.content_hash)}`"
          />
          <el-alert
            v-else-if="report.status === 'ABSTAIN'"
            type="warning"
            :closable="false"
            show-icon
            class="verdict-alert"
            :title="`ABSTAIN：${report.abstain_reason || '—'}`"
            :description="report.note"
          />
          <el-alert
            v-else
            type="info"
            :closable="false"
            show-icon
            class="verdict-alert"
            :title="`NOT_PUBLISHED：${report.abstain_reason || '该日无预测快照'}`"
            :description="report.note"
          />

          <div class="report-meta">
            <span>数据日期：{{ report.trade_date || '—' }}</span>
            <span>horizon：T+{{ report.horizon }}</span>
            <span>轨道：{{ trackLabel(report.track || '') }}</span>
            <span>publish_version：{{ report.publish_version ?? '—' }}</span>
            <span>report_version：{{ report.report_version ?? '—' }}</span>
            <span>生成时间：{{ formatTime(report.generated_at) }}</span>
          </div>

          <template v-if="report.status === 'PUBLISHED'">
            <el-card v-if="report.lineage" shadow="never" class="lineage-card">
              <template #header><span>预测 Lineage（固定，不可变）</span></template>
              <div class="q-meta">
                <span>prediction_run_id：{{ report.prediction_run_id }}</span>
                <span>source_result_id：{{ report.source_result_id || '—' }}</span>
                <span>source_trade_date：{{ report.lineage.source_trade_date || '—' }}</span>
                <span>factor_version：{{ report.lineage.factor_version || '—' }}</span>
                <span>strategy：{{ report.lineage.strategy_type || '—' }}</span>
                <span>model：{{ report.lineage.model_version || '—' }}</span>
                <span>output_kind：{{ report.lineage.output_kind || '—' }}</span>
                <span>schema_hash：{{ shortHash(report.lineage.schema_hash) }}</span>
                <span>calibrator：{{ report.lineage.calibrator_version || '—' }} / {{ shortHash(report.lineage.calibrator_hash) }}</span>
                <span>snapshot：{{ report.lineage.snapshot_id || '—' }}</span>
                <span>gate：{{ report.lineage.gate_status || '—' }}（{{ report.lineage.gate_decision_id || '—' }}）</span>
                <span>inference_chain：{{ report.lineage.inference_chain || '—' }}</span>
              </div>
            </el-card>

            <el-card shadow="never" class="lineage-card">
              <template #header><span>候选（与 prediction snapshot item 一致）</span></template>
              <el-table :data="report.items" size="small" stripe border max-height="420">
                <el-table-column prop="rank_no" label="排名" width="70" />
                <el-table-column prop="stock_code" label="代码" min-width="90" />
                <el-table-column prop="stock_name" label="名称" min-width="110" />
                <el-table-column label="得分" min-width="90">
                  <template #default="{ row }">{{ fmt4(row.score) }}</template>
                </el-table-column>
                <el-table-column prop="score_type" label="评分类型" min-width="160" />
                <el-table-column label="原因" min-width="240" show-overflow-tooltip>
                  <template #default="{ row }">{{ reasonSummary(row.reasons) }}</template>
                </el-table-column>
              </el-table>
            </el-card>

            <el-card v-if="report.industry_concentration" shadow="never" class="lineage-card">
              <template #header>
                <span>行业分布</span>
                <el-tag size="small" type="info" style="margin-left: 8px">{{ report.industry_concentration.provenance }}</el-tag>
                <el-tag v-if="report.industry_concentration.warning" size="small" type="warning" style="margin-left: 8px">
                  集中风险
                </el-tag>
              </template>
              <p class="note">{{ report.industry_concentration.pit_note }}</p>
              <div class="concentration-bars">
                <div
                  v-for="ind in report.industry_concentration.industries.slice(0, 8)"
                  :key="ind.industry"
                  class="concentration-bar"
                >
                  <span class="ind-name">{{ ind.industry }}</span>
                  <span class="ind-count">{{ ind.count }} 只</span>
                  <span class="ind-pct">{{ ind.pct }}%</span>
                </div>
              </div>
            </el-card>

            <el-card shadow="never" class="lineage-card">
              <template #header>
                <span>Realization（到期追加，不修改原候选）</span>
                <el-button size="small" type="primary" :loading="realizing" style="margin-left: 8px" @click="realize">
                  追加 / 查询 realization
                </el-button>
              </template>
              <template v-if="report.realization">
                <el-alert
                  :type="realizationType(report.realization.status)"
                  :closable="false"
                  show-icon
                  class="ic-note"
                  :title="realizationTitle(report.realization)"
                />
                <el-alert
                  v-if="report.realization.label_join_note"
                  type="info"
                  :closable="false"
                  show-icon
                  class="ic-note"
                  :title="report.realization.label_join_note"
                />
                <div class="q-meta">
                  <span>signal_date：{{ report.realization.signal_date || '—' }}</span>
                  <span>maturity_date：{{ report.realization.maturity_date || '—' }}</span>
                  <span>label_version：{{ report.realization.label_version || '—' }}</span>
                  <span v-if="report.realization.counts">
                    FILLED / RAW / UNAVAILABLE：{{ report.realization.counts.filled ?? 0 }} /
                    {{ report.realization.counts.raw_close_only ?? 0 }} / {{ report.realization.counts.unavailable ?? 0 }}
                  </span>
                  <span v-if="report.realization.reused">（幂等命中，返回既有 realization）</span>
                </div>
                <el-table v-if="report.realization.per_stock?.length" :data="report.realization.per_stock" size="small" stripe border max-height="320">
                  <el-table-column prop="rank_no" label="#" width="50" />
                  <el-table-column prop="stock_code" label="代码" min-width="90" />
                  <el-table-column prop="stock_name" label="名称" min-width="100" />
                  <el-table-column label="状态" width="140">
                    <template #default="{ row }">
                      <el-tag :type="realizationTag(row.realization_status)" size="small">{{ row.realization_status }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="收益" min-width="110">
                    <template #default="{ row }">{{ fmtPct(row.label_net_return ?? row.raw_forward_return) }}</template>
                  </el-table-column>
                  <el-table-column label="entry/exit" min-width="150">
                    <template #default="{ row }">{{ fmtPrice(row.entry_close) }} → {{ fmtPrice(row.exit_close) }}</template>
                  </el-table-column>
                </el-table>
              </template>
              <el-empty v-else description="持有期未到期或未查询；到期后点击「追加 / 查询 realization」" :image-size="60" />
            </el-card>
          </template>
        </template>

        <el-empty v-else-if="!loading" description="选择日期 / 轨道 / 版本后点击「生成日报」" />
      </div>
    </el-card>

    <el-card shadow="hover" class="trend-card">
      <template #header>
        <div class="report-header">
          <span>多日信号趋势</span>
          <div class="header-actions">
            <el-date-picker v-model="trendStart" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
            <span style="color: var(--text-muted)">~</span>
            <el-date-picker v-model="trendEnd" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
            <el-button type="primary" size="small" :loading="trendLoading" @click="loadTrend">查询趋势</el-button>
          </div>
        </div>
      </template>

      <div v-loading="trendLoading">
        <template v-if="trend">
          <p class="report-meta">
            <span>信号日数：{{ trend.signal_days }}</span>
            <span>策略：{{ trend.strategies.join('、') || '—' }}</span>
          </p>
          <el-table v-if="trend.daily_trend.length" :data="trend.daily_trend" size="small" stripe border max-height="360">
            <el-table-column prop="trade_date" label="信号日" min-width="110" />
            <el-table-column prop="strategies_with_opportunity" label="有机会策略数" min-width="120" />
            <el-table-column prop="total_candidates" label="候选总数" min-width="90" />
            <el-table-column v-for="s in trend.strategies" :key="s" :label="strategyLabel(s)" min-width="90">
              <template #default="{ row }">{{ row.per_strategy[s] ?? 0 }}</template>
            </el-table-column>
          </el-table>
          <p class="note">{{ trend.note }}</p>
        </template>
        <el-empty v-else-if="!trendLoading" description="选择日期范围后查询多日信号趋势" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import {
  getDailyReport,
  getDailyReportVersions,
  realizeDailyReport,
  getTrendReport,
  type DailyReportResponse,
  type DailyReportVersion,
  type TrendReportResponse,
  type Realization
} from '@/api/report'
import { useToast } from '@/composables/useToast'
import { errorText } from '@/api/request'
import dayjs from 'dayjs'

const toast = useToast()
const loading = ref(false)
/** V2-MP-028：加载失败错误态（errorId + retry），与 ABSTAIN/NOT_PUBLISHED/空态区分。 */
const reportError = ref('')
const report = ref<DailyReportResponse | null>(null)
const versions = ref<DailyReportVersion[]>([])
const tradeDate = ref('')
const horizon = ref<number>(3)
const track = ref<string>('RULE_SCORE')
/** 0 = 最新版本（选项哨兵值）；请求时 >0 才传 publishVersion */
const publishVersion = ref<number>(0)
const realizing = ref(false)
const trendLoading = ref(false)
const trend = ref<TrendReportResponse | null>(null)
const trendStart = ref(dayjs().subtract(14, 'day').format('YYYY-MM-DD'))
const trendEnd = ref(dayjs().format('YYYY-MM-DD'))

const STRATEGY_LABELS: Record<string, string> = {
  CLASSIC_SELECTOR: '经典多因子',
  OVERSOLD_REBOUND: '超跌反弹',
  TREND: '趋势打分',
  COMPREHENSIVE: '综合三维度',
  MULTI_DIM: '多维度 v1'
}

function strategyLabel(strategy: string): string {
  return STRATEGY_LABELS[strategy] || strategy
}

function trackLabel(track: string): string {
  return track === 'PROBABILITY' ? '概率轨' : '规则轨'
}

function versionLabel(v: DailyReportVersion): string {
  const suffix = v.status === 'ABSTAIN' ? ` · ${v.abstain_reason || 'ABSTAIN'}` : ''
  return `v${v.publish_version} · ${v.status}${suffix}`
}

function shortHash(hash?: string): string {
  return hash ? hash.slice(0, 12) : '—'
}

function fmt4(v: number | null | undefined): string {
  return v != null && Number.isFinite(v) ? v.toFixed(4) : '—'
}

function fmtPct(v: number | null | undefined): string {
  return v != null && Number.isFinite(v) ? `${(v * 100).toFixed(2)}%` : '—'
}

function fmtPrice(v: number | null | undefined): string {
  return v != null && Number.isFinite(v) ? v.toFixed(3) : '—'
}

function formatTime(value?: string): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

/** 展示用原因摘要（不含业务逻辑） */
function reasonSummary(reasons?: Record<string, unknown>): string {
  if (!reasons) return '—'
  const parts: string[] = []
  const ruleScore = reasons.rule_score
  if (ruleScore != null) parts.push(`rule=${typeof ruleScore === 'number' ? ruleScore.toFixed(4) : ruleScore}`)
  const selectorRank = reasons.selector_rank
  if (selectorRank != null) parts.push(`selector_rank=${selectorRank}`)
  const baseProb = reasons.base_probability
  if (baseProb != null) parts.push(`base_prob=${typeof baseProb === 'number' ? (baseProb * 100).toFixed(2) + '%' : baseProb}`)
  const outputKind = reasons.output_kind
  if (outputKind) parts.push(`kind=${outputKind}`)
  return parts.length ? parts.join(' · ') : JSON.stringify(reasons)
}

function realizationType(status?: string): 'success' | 'warning' | 'info' | 'error' {
  if (status === 'COMPLETE' || status === 'REUSED') return 'success'
  if (status === 'PARTIAL') return 'warning'
  if (status === 'NOT_READY') return 'info'
  return 'error'
}

function realizationTitle(r: Realization): string {
  if (r.status === 'COMPLETE') return 'COMPLETE：全部候选已实现（FILLED 或 raw close）'
  if (r.status === 'PARTIAL') return 'PARTIAL：部分候选 raw 缺失，标为 UNAVAILABLE（fail-closed，无 qfq 回退）'
  if (r.status === 'NOT_READY') return `NOT_READY：持有期未到期（到期日 ${r.maturity_date || '未知'}），暂不落库`
  if (r.status === 'REUSED') return 'REUSED：幂等命中，返回既有 realization'
  return r.status || '—'
}

function realizationTag(status?: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'FILLED') return 'success'
  if (status === 'RAW_CLOSE_ONLY') return 'info'
  if (status === 'UNAVAILABLE') return 'danger'
  return 'warning'
}

async function loadReport() {
  loading.value = true
  reportError.value = ''
  try {
    report.value = await getDailyReport({
      tradeDate: tradeDate.value || undefined,
      horizon: horizon.value,
      track: track.value,
      publishVersion: publishVersion.value > 0 ? publishVersion.value : undefined
    })
    // 版本选择器跟随实际解析到的日期（未选日期时以后端解析的最新信号日刷新）
    const resolvedDate = report.value?.trade_date || tradeDate.value || undefined
    await loadVersions(resolvedDate)
  } catch (e: any) {
    reportError.value = errorText(e)
    toast.error(reportError.value)
  } finally {
    loading.value = false
  }
}

async function loadVersions(date?: string) {
  try {
    const res = await getDailyReportVersions({
      tradeDate: date,
      horizon: horizon.value,
      track: track.value
    })
    versions.value = res.versions || []
  } catch {
    versions.value = []
  }
}

async function realize() {
  if (!report.value?.prediction_run_id) {
    toast.warning('无 PUBLISHED 预测快照，无法追加 realization')
    return
  }
  realizing.value = true
  try {
    const result = await realizeDailyReport({
      tradeDate: tradeDate.value || undefined,
      horizon: horizon.value,
      track: track.value,
      publishVersion: publishVersion.value > 0 ? publishVersion.value : undefined
    })
    report.value = {
      ...report.value,
      realization: result
    }
    toast.success(result.status === 'NOT_READY' ? '未到期，稍后再查' : 'realization 已就绪')
  } catch (e: any) {
    toast.error(e?.message || 'realization 追加失败')
  } finally {
    realizing.value = false
  }
}

async function loadTrend() {
  if (!trendStart.value || !trendEnd.value) {
    toast.warning('请选择起止日期')
    return
  }
  if (trendStart.value > trendEnd.value) {
    toast.warning('起始日不能晚于截止日')
    return
  }
  trendLoading.value = true
  try {
    trend.value = await getTrendReport(trendStart.value, trendEnd.value)
  } catch (e: any) {
    toast.error(e?.message || '趋势查询失败')
  } finally {
    trendLoading.value = false
  }
}

onMounted(async () => {
  await loadVersions()
  await loadReport()
})
</script>

<style scoped>
.daily-report {
  max-width: 1200px;
  margin: 0 auto;
}
.report-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.report-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin-bottom: 12px;
  color: var(--text-secondary);
  font-size: 12px;
}
.report-meta .note {
  color: var(--text-muted);
}
.verdict-alert {
  margin-bottom: 16px;
}
.q-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.q-meta span {
  white-space: nowrap;
}
.ic-note {
  margin-bottom: 8px;
}
.lineage-card {
  margin-bottom: 16px;
  background: var(--surface-2);
}
.note {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--text-muted);
}
.concentration-bars {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.concentration-bar {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
.ind-pct {
  color: var(--text-muted);
  min-width: 44px;
  text-align: right;
}
.trend-card {
  margin-top: 16px;
}
</style>
