<template>
  <div class="daily-report terminal-page">
    <el-card shadow="hover">
      <template #header>
        <div class="report-header">
          <span>选股日报</span>
          <div class="header-actions">
            <el-date-picker
              v-model="tradeDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="留空取各策略最新"
              clearable
              style="width: 168px"
            />
            <el-button type="primary" size="small" :loading="loading" @click="loadReport">生成日报</el-button>
          </div>
        </div>
      </template>

      <div v-loading="loading">
        <template v-if="report">
          <div class="report-meta">
            <span>数据日期：{{ report.trade_date || '—' }}</span>
            <span>生成时间：{{ formatTime(report.generated_at) }}</span>
            <span class="note">{{ report.signal_execution_note }}</span>
          </div>

          <el-alert
            :type="overallVerdictType"
            :closable="false"
            show-icon
            class="verdict-alert"
            :title="report.overall_summary.verdict"
            :description="`共 ${report.overall_summary.strategy_count} 个策略，其中 ${report.overall_summary.strategies_with_opportunity} 个给出候选，${report.overall_summary.strategies_without_opportunity} 个无合格标的。`"
          />

          <el-card v-if="report.prediction" shadow="never" class="lineage-card">
            <template #header>
              <span>预测快照 Lineage</span>
              <el-tag size="small" :type="report.prediction.status === 'PUBLISHED' ? 'success' : 'warning'" style="margin-left: 8px">
                {{ report.prediction.status }}
              </el-tag>
            </template>
            <p v-if="report.prediction.note" class="note">{{ report.prediction.note }}</p>
            <div v-for="h in report.prediction.horizons || []" :key="h.horizon" class="lineage-horizon">
              <div class="lineage-h-title">T+{{ h.horizon }} · {{ h.status }}
                <span v-if="h.probability_track"> · 概率轨 {{ h.probability_track }}</span>
              </div>
              <p v-if="h.note" class="note">{{ h.note }}</p>
              <el-table v-if="h.tracks?.length" :data="h.tracks" size="small" stripe border>
                <el-table-column prop="track" label="轨道" width="120" />
                <el-table-column prop="status" label="状态" width="100" />
                <el-table-column prop="prediction_run_id" label="run_id" min-width="160" show-overflow-tooltip />
                <el-table-column prop="model_version" label="模型" min-width="120" show-overflow-tooltip />
                <el-table-column prop="publish_version" label="版本" width="70" />
                <el-table-column prop="item_count" label="条目" width="70" />
                <el-table-column label="abstain" min-width="140" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.abstain_reason || '—' }}</template>
                </el-table-column>
                <el-table-column label="gate" width="90">
                  <template #default="{ row }">{{ row.quality_gate?.gate_status || '—' }}</template>
                </el-table-column>
              </el-table>
            </div>
          </el-card>

          <el-empty
            v-if="!report.has_any_result"
            description="该交易日无成功选股结果，请先在「选股打分」页运行策略"
          />

          <div v-else class="strategy-grid">
            <el-card
              v-for="s in report.strategies"
              :key="s.strategy"
              shadow="never"
              class="strategy-card"
              :class="{ 'no-opportunity': !s.has_opportunity }"
            >
              <template #header>
                <div class="strategy-header">
                  <span class="strategy-name">{{ strategyLabel(s.strategy) }}</span>
                  <el-tag :type="s.has_opportunity ? 'success' : 'info'" size="small">
                    {{ s.has_opportunity ? `候选 ${s.returned_items}` : '无机会' }}
                  </el-tag>
                </div>
              </template>
              <div class="strategy-meta">
                <span>因子版本：{{ s.factor_version || '—' }}</span>
                <span>信号日：{{ s.trade_date || '—' }}</span>
                <span>状态：{{ s.run_status }}</span>
              </div>
              <p class="strategy-message">{{ s.message }}</p>

              <div v-if="s.industry_concentration && s.industry_concentration.total > 0" class="concentration">
                <div class="concentration-header">
                  <span>行业分布</span>
                  <el-tag
                    v-if="s.industry_concentration.warning"
                    type="warning"
                    size="small"
                  >集中风险</el-tag>
                </div>
                <p v-if="s.industry_concentration.warning" class="concentration-warning">
                  {{ s.industry_concentration.warning }}
                </p>
                <div class="concentration-bars">
                  <div
                    v-for="ind in s.industry_concentration.industries.slice(0, 5)"
                    :key="ind.industry"
                    class="concentration-bar"
                  >
                    <span class="ind-name">{{ ind.industry }}</span>
                    <span class="ind-count">{{ ind.count }} 只</span>
                    <span class="ind-pct">{{ ind.pct }}%</span>
                  </div>
                </div>
              </div>

              <el-table v-if="s.top_items.length" :data="s.top_items" size="small" stripe border>
                <el-table-column prop="rank_no" label="排名" width="70" />
                <el-table-column prop="stock_code" label="代码" min-width="90" />
                <el-table-column prop="stock_name" label="名称" min-width="100" />
                <el-table-column label="得分" min-width="80">
                  <template #default="{ row }">{{ formatScore(row.final_score) }}</template>
                </el-table-column>
              </el-table>
            </el-card>
          </div>
        </template>

        <el-empty v-else-if="!loading" description="点击「生成日报」查看每日信号摘要" />
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
import { ref, computed, onMounted } from 'vue'
import { getDailyReport, getTrendReport, type DailyReportResponse, type TrendReportResponse } from '@/api/report'
import { useToast } from '@/composables/useToast'
import dayjs from 'dayjs'

const toast = useToast()
const loading = ref(false)
const report = ref<DailyReportResponse | null>(null)
const tradeDate = ref('')
const trendLoading = ref(false)
const trend = ref<TrendReportResponse | null>(null)
const trendStart = ref(dayjs().subtract(14, 'day').format('YYYY-MM-DD'))
const trendEnd = ref(dayjs().format('YYYY-MM-DD'))

const overallVerdictType = computed<'success' | 'warning' | 'info'>(() => {
  if (!report.value) return 'info'
  const withOpp = report.value.overall_summary.strategies_with_opportunity
  if (withOpp === 0) return 'warning'
  return 'success'
})

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

function formatScore(value: unknown): string {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(2) : '—'
}

function formatTime(value: string): string {
  if (!value) return '—'
  // 后端返回 ISO LocalDateTime，截到分钟
  return value.replace('T', ' ').slice(0, 16)
}

async function loadReport() {
  loading.value = true
  try {
    report.value = await getDailyReport(tradeDate.value || undefined)
  } catch (e: any) {
    toast.error(e?.message || '选股日报生成失败')
  } finally {
    loading.value = false
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

onMounted(() => {
  loadReport()
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
.strategy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
}
.strategy-card {
  background: var(--surface-2);
  border: 1px solid var(--hairline-strong);
}
.strategy-card.no-opportunity {
  opacity: 0.7;
}
.strategy-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.strategy-name {
  font-weight: 600;
}
.strategy-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
.strategy-message {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-muted);
}
.concentration {
  margin-bottom: 10px;
  padding: 8px 10px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 4px;
}
.concentration-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.concentration-warning {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--warning);
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
.lineage-card {
  margin-bottom: 16px;
  background: var(--surface-2);
}
.lineage-horizon {
  margin-top: 10px;
}
.lineage-h-title {
  font-weight: 600;
  margin-bottom: 6px;
}
</style>
