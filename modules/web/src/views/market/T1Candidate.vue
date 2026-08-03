<template>
  <div class="t1-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <span>T+1 次日候选（涨停基因 · 次日上涨概率 Top10）</span>
      </template>

      <el-alert type="info" :closable="false" show-icon class="note-alert"
        :title="result?.score_type === 'probability' ? '已按真实上涨概率排序（模型 ' + (result.score_model ?? '') + '）' : '工作流：T 日收盘生成次日上涨候选 → 次日结束后核销。评分为规则分（涨停基因+短期动量+量能+趋势），非概率；可在概率研究页生成 T+1 标签并训练后按真实概率排序；涨停股次日可能一字板买不进。'" />

      <el-form inline>
        <el-form-item label="信号日 T">
          <el-date-picker v-model="tradeDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="TopN">
          <el-input-number v-model="topN" :min="1" :max="50" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="genLoading" @click="generate">生成 T+1 候选</el-button>
          <el-button :loading="settleLoading" :disabled="!result || result.candidates.length === 0" @click="settle">次日核销</el-button>
          <el-button :disabled="!tableData.length" @click="exportOperationList">导出次日操作清单</el-button>
          <el-button :loading="recentLoading" @click="loadRecent">最近清单</el-button>
        </el-form-item>
      </el-form>

      <!-- 市场情绪快照 -->
      <template v-if="result?.ecology && Object.keys(result.ecology).length">
        <div class="ecology-row">
          <span>涨停家数：<b>{{ result.ecology.limit_up_count ?? '—' }}</b></span>
          <span>跌停家数：<b>{{ result.ecology.limit_down_count ?? '—' }}</b></span>
          <span>最高连板：<b>{{ result.ecology.max_lianban ?? '—' }}</b></span>
          <span>晋级率：<b>{{ pct(result.ecology.promotion_rate as number) }}</b></span>
          <span>炸板率：<b>{{ pct(result.ecology.broken_board_rate as number) }}</b></span>
        </div>
      </template>

      <!-- 核销汇总 -->
      <template v-if="result?.summary">
        <el-alert type="success" :closable="false" show-icon class="note-alert"
          :title="`命中率 ${pct(result.summary.hit_rate)}（${result.summary.hit_count}/${result.summary.settled}）· 平均次日涨幅 ${pct(result.summary.avg_next_ret)} · 平均高开 ${pct(result.summary.avg_gap)}`" />
      </template>

      <!-- 候选列表（含核销后的次日表现） -->
      <el-table v-loading="genLoading || settleLoading" :data="tableData" size="small" stripe border max-height="480">
        <el-table-column type="index" label="#" width="46" :index="(i: number) => i + 1" />
        <el-table-column prop="stock_code" label="代码" width="90" />
        <el-table-column prop="stock_name" label="名称" min-width="100" />
        <el-table-column label="评分" width="90">
          <template #default="{ row }">
            <b v-if="row.score_type === 'probability'">{{ pct(row.score) }}</b>
            <b v-else>{{ fmt1(row.score) }}</b>
            <el-tag v-if="row.score_type === 'probability'" type="success" size="small" class="prob-tag">概率</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="涨停" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.is_limit_up" type="danger" size="small">涨停</el-tag>
            <el-tag v-else-if="row.lianban_days >= 2" type="warning" size="small">{{ row.lianban_days }}板</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="量比" width="70">
          <template #default="{ row }">{{ fmt1(row.volume_ratio) }}</template>
        </el-table-column>
        <el-table-column label="当日涨跌" width="90">
          <template #default="{ row }">
            <span :class="(row.change_pct ?? 0) >= 0 ? 'text-success' : 'text-danger'">{{ fmt1(row.change_pct) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="次日涨幅" width="90">
          <template #default="{ row }">
            <span v-if="row.settled" :class="(row.next_ret ?? 0) >= 0 ? 'text-success' : 'text-danger'">{{ pct(row.next_ret) }}</span>
            <span v-else-if="row.skip">跳过</span>
            <span v-else>待核销</span>
          </template>
        </el-table-column>
        <el-table-column label="次日最高" width="90">
          <template #default="{ row }">
            <span v-if="row.settled">{{ pct(row.next_high_ret) }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="高开" width="80">
          <template #default="{ row }">
            <span v-if="row.settled" :class="(row.gap ?? 0) >= 0 ? 'text-success' : 'text-danger'">{{ pct(row.gap) }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="次日涨停" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.next_is_limit_up" type="danger" size="small">涨停</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="可买性" width="110">
          <template #default="{ row }">
            <el-tag :type="buyabilityType(row.buyability)" size="small">{{ row.buyability || '可买' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="风险" min-width="140">
          <template #default="{ row }">
            <el-tag v-for="(r, i) in (row.risk_tags || [])" :key="i" type="warning" size="small" class="reason-tag">{{ r }}</el-tag>
            <span v-if="!(row.risk_tags || []).length">—</span>
          </template>
        </el-table-column>
        <el-table-column label="触发原因" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="(r, i) in row.reasons" :key="i" size="small" class="reason-tag">{{ r }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !result" description="选择信号日并点击生成候选" />

      <!-- 最近清单 -->
      <template v-if="recent.length">
        <el-divider content-position="left">最近生成</el-divider>
        <el-table :data="recent" size="small" stripe border max-height="240">
          <el-table-column prop="trade_date" label="信号日" min-width="110" />
          <el-table-column prop="top_n" label="TopN" width="70" />
          <el-table-column label="核销" width="90">
            <template #default="{ row }">
              <el-tag :type="row.settled ? 'success' : 'info'" size="small">{{ row.settled ? '已核销' : '未核销' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="loadDate(row.trade_date)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- 策略绩效（历史累计验证） -->
      <el-divider content-position="left">策略绩效（历史累计）</el-divider>
      <el-form inline>
        <el-form-item label="起">
          <el-date-picker v-model="statsStart" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="止">
          <el-date-picker v-model="statsEnd" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item>
          <el-button :loading="statsLoading" @click="loadStats">统计绩效</el-button>
        </el-form-item>
      </el-form>

      <template v-if="stats">
        <div class="kpi-row">
          <div class="kpi-card"><span class="kpi-label">已核销天数</span><b class="kpi-value">{{ stats.days }}</b></div>
          <div class="kpi-card"><span class="kpi-label">样本数</span><b class="kpi-value">{{ stats.total_settled ?? 0 }}</b></div>
          <div class="kpi-card"><span class="kpi-label">命中率</span><b class="kpi-value" :class="(stats.hit_rate ?? 0) >= 0.5 ? 'text-up' : 'text-down'">{{ pct(stats.hit_rate) }}</b></div>
          <div class="kpi-card"><span class="kpi-label">平均次日涨幅</span><b class="kpi-value" :class="(stats.avg_next_ret ?? 0) >= 0 ? 'text-up' : 'text-down'">{{ pct(stats.avg_next_ret) }}</b></div>
          <div class="kpi-card"><span class="kpi-label">平均高开</span><b class="kpi-value">{{ pct(stats.avg_gap) }}</b></div>
          <div v-if="stats.benchmark?.available" class="kpi-card">
            <span class="kpi-label">vs 沪深300 日均</span>
            <b class="kpi-value" :class="stats.benchmark.outperform ? 'text-up' : 'text-down'">
              {{ pct(stats.benchmark.index_avg_daily_return) }}（{{ stats.benchmark.outperform ? '跑赢' : '跑输' }}）
            </b>
          </div>
        </div>
        <el-table v-if="stats.daily_series?.length" :data="stats.daily_series" size="small" stripe border max-height="280" class="mt">
          <el-table-column prop="trade_date" label="信号日" min-width="110" />
          <el-table-column prop="settled" label="样本" width="70" />
          <el-table-column prop="hit_count" label="命中" width="70" />
          <el-table-column label="命中率" width="90">
            <template #default="{ row }">{{ pct(row.hit_rate) }}</template>
          </el-table-column>
          <el-table-column label="平均次日涨幅" min-width="110">
            <template #default="{ row }">
              <span :class="(row.avg_next_ret ?? 0) >= 0 ? 'text-success' : 'text-danger'">{{ pct(row.avg_next_ret) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <p class="note-line" v-if="stats.note">{{ stats.note }}</p>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { generateT1Candidates, settleT1Candidates, getT1Candidates, getT1Recent, getT1Stats, type T1CandidatesResponse, type T1StatsResponse } from '@/api/research'
import { useToast } from '@/composables/useToast'
import dayjs from 'dayjs'

const toast = useToast()
const tradeDate = ref(dayjs().format('YYYY-MM-DD'))
const topN = ref(10)
const genLoading = ref(false)
const settleLoading = ref(false)
const recentLoading = ref(false)
const result = ref<T1CandidatesResponse | null>(null)
const recent = ref<Array<{ trade_date: string; top_n: number; settled: boolean }>>([])
const statsStart = ref(dayjs().subtract(30, 'day').format('YYYY-MM-DD'))
const statsEnd = ref(dayjs().format('YYYY-MM-DD'))
const statsLoading = ref(false)
const stats = ref<T1StatsResponse | null>(null)

const loading = computed(() => genLoading.value || settleLoading.value)

const tableData = computed(() => {
  if (!result.value) return []
  if (result.value.results?.length) return result.value.results
  return result.value.candidates ?? []
})

function fmt1(v: number | undefined | null): string {
  return v == null || !Number.isFinite(v) ? '—' : v.toFixed(1)
}
function pct(v: number | undefined | null): string {
  return v == null || !Number.isFinite(v) ? '—' : `${(v * 100).toFixed(2)}%`
}
function buyabilityType(b: string | undefined): 'success' | 'warning' | 'danger' | 'info' {
  if (b === '可买') return 'success'
  if (b === '高开追高谨慎') return 'warning'
  if (b === '大概率买不进') return 'danger'
  return 'info'
}

/** 导出次日操作清单 CSV（含可买性/风险/触发原因）。 */
function exportOperationList() {
  const rows = tableData.value
  if (!rows.length) return
  const lines: string[] = [
    `信号日,${tradeDate.value}`,
    '',
    ['stock_code', 'stock_name', 'score', 'is_limit_up', 'lianban_days', 'volume_ratio', 'change_pct', 'buyability', 'risks', 'reasons'].join(','),
    ...rows.map((r) => [
      r.stock_code, `"${r.stock_name ?? ''}"`, r.score ?? '', r.is_limit_up ?? '', r.lianban_days ?? '',
      r.volume_ratio ?? '', r.change_pct ?? '', r.buyability ?? '', (r.risk_tags || []).join('|'),
      (r.reasons || []).join('|')
    ].join(','))
  ]
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `t1-operation-list-${tradeDate.value}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

async function generate() {
  if (!tradeDate.value) {
    toast.warning('请选择信号日')
    return
  }
  genLoading.value = true
  try {
    result.value = await generateT1Candidates({ tradeDate: tradeDate.value, topN: topN.value })
    toast.success(`已生成 ${result.value?.top_n ?? 0} 条 T+1 候选`)
    await loadRecent()
  } catch (e: any) {
    toast.error(e?.message || '生成失败')
  } finally {
    genLoading.value = false
  }
}

async function settle() {
  if (!tradeDate.value) return
  settleLoading.value = true
  try {
    const res = await settleT1Candidates({ tradeDate: tradeDate.value })
    // 合并核销结果到当前视图
    result.value = {
      ...(result.value ?? { trade_date: tradeDate.value, top_n: topN.value, candidates: [] }),
      ...res,
      candidates: res.results ?? (result.value?.candidates ?? [])
    }
    toast.success(`核销完成：命中率 ${res.summary ? pct(res.summary.hit_rate) : '—'}`)
    await loadRecent()
  } catch (e: any) {
    toast.error(e?.message || '核销失败')
  } finally {
    settleLoading.value = false
  }
}

async function loadDate(date: string) {
  tradeDate.value = date
  try {
    const res = await getT1Candidates(date)
    if (res.found) {
      result.value = res
    } else {
      result.value = null
      toast.info('该日无候选记录')
    }
  } catch (e: any) {
    toast.error(e?.message || '加载失败')
  }
}

async function loadRecent() {
  recentLoading.value = true
  try {
    recent.value = await getT1Recent()
  } catch {
    // silent
  } finally {
    recentLoading.value = false
  }
}

async function loadStats() {
  if (!statsStart.value || !statsEnd.value) {
    toast.warning('请选择绩效统计区间')
    return
  }
  statsLoading.value = true
  try {
    stats.value = await getT1Stats({ startDate: statsStart.value, endDate: statsEnd.value })
  } catch (e: any) {
    toast.error(e?.message || '绩效统计失败')
  } finally {
    statsLoading.value = false
  }
}

onMounted(loadRecent)
</script>

<style scoped>
.t1-page { max-width: 1280px; margin: 0 auto; }
.note-alert { margin-bottom: 14px; }
.ecology-row { display: flex; flex-wrap: wrap; gap: 18px; font-size: 13px; color: var(--text-secondary); margin-bottom: 12px; }
.reason-tag { margin-right: 4px; }
.prob-tag { margin-left: 4px; }
.kpi-row { margin-bottom: 12px; }
.note-line { font-size: 12px; color: var(--text-muted); margin: 8px 0 0; }
.mt { margin-top: 10px; }
</style>
