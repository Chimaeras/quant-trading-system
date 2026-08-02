<template>
  <div class="factor-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <span>因子研究面板</span>
      </template>

      <el-form inline class="form">
        <el-form-item label="策略">
          <el-select v-model="strategy" placeholder="全部策略" clearable style="width: 180px">
            <el-option label="全部策略" value="" />
            <el-option v-for="(label, key) in STRATEGY_LABELS" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" placeholder="起始日" />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker v-model="endDate" type="date" value-format="YYYY-MM-DD" placeholder="截止日" />
        </el-form-item>
        <el-form-item label="IC持仓日">
          <el-input-number v-model="holdingDays" :min="1" :max="60" :step="1" controls-position="right" style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="run">研究</el-button>
          <el-button :loading="unifiedLoading" @click="runUnified">多周期 IC 对比</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="loading">
        <template v-if="result">
          <el-alert type="warning" :closable="false" show-icon class="note-alert" :title="result.note" />

          <div class="grid">
            <el-card shadow="never" class="q-card">
              <template #header>覆盖率</template>
              <div class="q-meta">
                <span>截面天数：{{ result.cross_section_days }}</span>
                <span>总观测：{{ result.total_observations }}</span>
                <span>有效观测（得分>0）：{{ result.valid_observations }}</span>
                <span :class="coverageClass">{{ coverageText }}</span>
              </div>
            </el-card>

            <el-card shadow="never" class="q-card">
              <template #header>得分截面分布</template>
              <div class="q-meta">
                <span>最小值：{{ fmt(result.distribution.min) }}</span>
                <span>P25：{{ fmt(result.distribution.p25) }}</span>
                <span>中位数：{{ fmt(result.distribution.median) }}</span>
                <span>P75：{{ fmt(result.distribution.p75) }}</span>
                <span>最大值：{{ fmt(result.distribution.max) }}</span>
              </div>
            </el-card>

            <el-card shadow="never" class="q-card">
              <template #header>得分分组（等数量 5 组）</template>
              <el-table v-if="result.group_bands.length" :data="result.group_bands" size="small" stripe border>
                <el-table-column prop="group_no" label="组" width="60" />
                <el-table-column label="得分范围" min-width="140">
                  <template #default="{ row }">{{ fmt(row.min_score) }} ~ {{ fmt(row.max_score) }}</template>
                </el-table-column>
                <el-table-column label="均值" min-width="80">
                  <template #default="{ row }">{{ fmt(row.avg_score) }}</template>
                </el-table-column>
                <el-table-column prop="count" label="数量" width="70" />
              </el-table>
              <el-empty v-else description="无有效得分" :image-size="40" />
            </el-card>

            <el-card shadow="never" class="q-card">
              <template #header>研究范围</template>
              <div class="q-meta">
                <span>策略：{{ strategyLabel(result.strategy) }}</span>
                <span>区间：{{ result.start_date }} ~ {{ result.end_date }}</span>
              </div>
            </el-card>

            <el-card shadow="never" class="q-card rank-ic-card">
              <template #header>
                <span>Rank IC 与分组收益（持仓 {{ result.rank_ic.holding_days }} 日）</span>
              </template>
              <div class="q-meta">
                <span>截面日数：{{ result.rank_ic.cross_section_days }}</span>
                <span>观测数：{{ result.rank_ic.total_observations }}</span>
                <span>IC 均值：{{ fmt4(result.rank_ic.rank_ic_mean) }}</span>
                <span>IC 标准差：{{ fmt4(result.rank_ic.rank_ic_std) }}</span>
                <span>ICIR：{{ fmt4(result.rank_ic.rank_ic_ir) }}</span>
                <span>IC 正率：{{ pct(result.rank_ic.rank_ic_positive_rate) }}</span>
                <span>多空 spread：{{ pct4(result.rank_ic.long_short_spread) }}</span>
              </div>
              <el-alert type="info" :closable="false" show-icon class="ic-note" :title="result.rank_ic.basis_note" />
              <el-alert type="warning" :closable="false" show-icon class="ic-note" :title="result.rank_ic.universe_note" />
              <div v-if="result.rank_ic.grouped_returns.length" class="ic-section">
                <div class="ic-section-title">分组收益（按得分等数量分组，仅研究评价）</div>
                <el-table :data="result.rank_ic.grouped_returns" size="small" stripe border>
                  <el-table-column prop="group_no" label="组" width="60" />
                  <el-table-column prop="label" label="分组" min-width="120" />
                  <el-table-column label="平均收益" min-width="100">
                    <template #default="{ row }">{{ pct4(row.avg_return) }}</template>
                  </el-table-column>
                  <el-table-column prop="observations" label="观测数" width="80" />
                </el-table>
              </div>
              <div v-if="result.rank_ic.daily_rank_ic.length" class="ic-section">
                <div class="ic-section-title">每日 Rank IC</div>
                <el-table :data="result.rank_ic.daily_rank_ic" size="small" stripe border max-height="320">
                  <el-table-column prop="trade_date" label="信号日" min-width="110" />
                  <el-table-column label="Rank IC" min-width="100">
                    <template #default="{ row }">{{ fmt4(row.rank_ic) }}</template>
                  </el-table-column>
                  <el-table-column prop="sample_size" label="样本数" width="80" />
                </el-table>
              </div>
              <el-empty
                v-if="!result.rank_ic.grouped_returns.length && !result.rank_ic.daily_rank_ic.length"
                description="截面样本不足或无可用 forward 收益（信号日接近数据末端时跳过）"
                :image-size="40"
              />
            </el-card>
          </div>
        </template>
        <el-empty v-else-if="!loading" description="选择区间与策略后点击研究" />
      </div>

      <!-- 多周期 IC 对比（统一因子报告） -->
      <div v-loading="unifiedLoading" class="unified-block">
        <template v-if="unifiedResult">
          <div class="unified-toolbar">
            <el-button size="small" @click="exportUnifiedCsv">导出多周期 IC CSV</el-button>
          </div>
          <el-alert type="info" :closable="false" show-icon class="note-alert" :title="unifiedResult.note || ''" />
          <el-table :data="unifiedResult.by_horizon" size="small" stripe border max-height="420">
            <el-table-column prop="holding_days" label="持有期" width="70">
              <template #default="{ row }">T+{{ row.holding_days }}</template>
            </el-table-column>
            <el-table-column label="Rank IC" width="90">
              <template #default="{ row }">{{ fmt4(row.rank_ic_mean) }}</template>
            </el-table-column>
            <el-table-column label="Pearson IC" width="100">
              <template #default="{ row }">{{ fmt4(row.pearson_ic_mean) }}</template>
            </el-table-column>
            <el-table-column label="ICIR" width="80">
              <template #default="{ row }">{{ fmt4(row.rank_ic_ir) }}</template>
            </el-table-column>
            <el-table-column label="正IC率" width="80">
              <template #default="{ row }">{{ pct(row.rank_ic_positive_rate) }}</template>
            </el-table-column>
            <el-table-column label="单调性" width="150">
              <template #default="{ row }">{{ row.monotonicity }}</template>
            </el-table-column>
            <el-table-column label="多空 spread" width="100">
              <template #default="{ row }">{{ pct(row.long_short_spread) }}</template>
            </el-table-column>
            <el-table-column label="净收益 IC (可执行)" width="130">
              <template #default="{ row }">{{ row.executable_net_ic ? fmt4(row.executable_net_ic.rank_ic_mean) : '—' }}</template>
            </el-table-column>
            <el-table-column label="HAC 95%CI" min-width="130">
              <template #default="{ row }">{{ fmt4(row.ci_lower) }} ~ {{ fmt4(row.ci_upper) }}</template>
            </el-table-column>
          </el-table>
          <div class="unified-notes">
            <p v-if="unifiedResult.by_horizon[0]?.basis_note">毛收益口径：{{ unifiedResult.by_horizon[0].basis_note }}</p>
            <p v-if="unifiedResult.by_horizon[0]?.net_basis_note">净收益口径：{{ unifiedResult.by_horizon[0].net_basis_note }}</p>
            <p v-if="unifiedResult.by_horizon[0]?.embargo_note">Embargo：{{ unifiedResult.by_horizon[0].embargo_note }}</p>
          </div>
        </template>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getFactorResearch, getUnifiedFactorReport, type FactorResearchResponse, type UnifiedFactorReport } from '@/api/factor'
import { useToast } from '@/composables/useToast'
import dayjs from 'dayjs'

const STRATEGY_LABELS: Record<string, string> = {
  CLASSIC_SELECTOR: '经典多因子',
  OVERSOLD_REBOUND: '超跌反弹',
  TREND: '趋势打分',
  COMPREHENSIVE: '综合三维度',
  MULTI_DIM: '多维度 v1'
}

const toast = useToast()
const loading = ref(false)
const strategy = ref('')
const startDate = ref(dayjs().subtract(30, 'day').format('YYYY-MM-DD'))
const endDate = ref(dayjs().format('YYYY-MM-DD'))
const holdingDays = ref(5)
const result = ref<FactorResearchResponse | null>(null)
const unifiedLoading = ref(false)
const unifiedResult = ref<UnifiedFactorReport | null>(null)

function strategyLabel(s: string): string {
  if (s === 'ALL') return '全部策略'
  return STRATEGY_LABELS[s] || s
}

function fmt(v: number): string {
  return Number.isFinite(v) ? v.toFixed(2) : '—'
}

function fmt4(v: number): string {
  return Number.isFinite(v) ? v.toFixed(4) : '—'
}

function pct(v: number): string {
  return Number.isFinite(v) ? `${(v * 100).toFixed(1)}%` : '—'
}

function pct4(v: number): string {
  return Number.isFinite(v) ? `${(v * 100).toFixed(2)}%` : '—'
}

const coverageText = computed(() => {
  if (!result.value) return ''
  return `覆盖率：${result.value.coverage}%`
})

const coverageClass = computed(() => {
  if (!result.value) return ''
  const c = result.value.coverage
  if (c >= 80) return 'text-success'
  if (c >= 50) return 'text-warning'
  return 'text-danger'
})

async function run() {
  if (!startDate.value || !endDate.value) {
    toast.warning('请选择起止日期')
    return
  }
  if (startDate.value > endDate.value) {
    toast.warning('起始日不能晚于截止日')
    return
  }
  loading.value = true
  try {
    result.value = await getFactorResearch({
      strategy: strategy.value || undefined,
      startDate: startDate.value,
      endDate: endDate.value,
      holdingDays: holdingDays.value
    })
  } catch (e: any) {
    toast.error(e?.message || '因子研究失败')
  } finally {
    loading.value = false
  }
}

async function runUnified() {
  if (!startDate.value || !endDate.value) {
    toast.warning('请选择起止日期')
    return
  }
  unifiedLoading.value = true
  try {
    unifiedResult.value = await getUnifiedFactorReport({
      strategy: strategy.value || undefined,
      startDate: startDate.value,
      endDate: endDate.value
    })
  } catch (e: any) {
    toast.error(e?.message || '多周期 IC 研究失败')
  } finally {
    unifiedLoading.value = false
  }
}

function exportUnifiedCsv() {
  if (!unifiedResult.value || !unifiedResult.value.by_horizon.length) return
  const r = unifiedResult.value
  const lines: string[] = [
    `strategy,${r.strategy}`,
    `range,${r.start_date} ~ ${r.end_date}`,
    '',
    ['holding_days', 'rank_ic', 'pearson_ic', 'icir', 'positive_rate', 'monotonicity', 'spread', 'net_ic', 'hac_std', 'ci_lower', 'ci_upper'].join(','),
    ...r.by_horizon.map((row) => [
      row.holding_days, row.rank_ic_mean, row.pearson_ic_mean, row.rank_ic_ir,
      row.rank_ic_positive_rate, row.monotonicity, row.long_short_spread,
      row.executable_net_ic?.rank_ic_mean ?? '', row.hac_std, row.ci_lower, row.ci_upper
    ].join(','))
  ]
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `unified-factor-ic-${r.start_date}_${r.end_date}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(() => run())
</script>

<style scoped>
.factor-page {
  max-width: 1200px;
  margin: 0 auto;
}
.form {
  margin-bottom: 8px;
}
.note-alert {
  margin-bottom: 16px;
}
.unified-block {
  margin-top: 16px;
}
.unified-toolbar {
  margin-bottom: 8px;
}
.unified-notes {
  margin-top: 10px;
  font-size: 12px;
  color: #aab8cc;
  line-height: 1.6;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}
.q-card {
  background: rgba(9, 18, 34, 0.72);
  border: 1px solid rgba(145, 196, 255, 0.18);
}
.q-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.text-danger { color: #f56c6c; }
.text-success { color: #67c23a; }
.text-warning { color: #e6a23c; }
.rank-ic-card {
  grid-column: 1 / -1;
}
.ic-note {
  margin-top: 8px;
}
.ic-section {
  margin-top: 12px;
}
.ic-section-title {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
</style>