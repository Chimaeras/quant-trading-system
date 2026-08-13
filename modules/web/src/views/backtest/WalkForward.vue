<template>
  <div class="wf-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <span>分段稳定性（非样本外 / 非 Walk-Forward）</span>
        <el-tooltip content="V1 旧页：仅连续窗口分段收益统计，非真正 Walk-Forward / 样本外验证" placement="top">
          <el-tag size="small" effect="plain" type="info" class="capability-tag">Legacy</el-tag>
        </el-tooltip>
      </template>

      <el-alert type="warning" :closable="false" show-icon class="note-alert"
        title="本页仅为连续窗口分段收益统计，不是真正的 Walk-Forward / 样本外验证。不含训练、校准、purge 或 embargo。" />

      <el-form inline class="form">
        <el-form-item label="策略">
          <el-select v-model="strategy" style="width: 180px">
            <el-option v-for="(label, key) in STRATEGY_LABELS" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" placeholder="起始日" />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker v-model="endDate" type="date" value-format="YYYY-MM-DD" placeholder="截止日" />
        </el-form-item>
        <el-form-item label="窗口数">
          <el-input-number v-model="windows" :min="2" :max="12" :step="1" controls-position="right" style="width: 110px" />
        </el-form-item>
        <el-form-item label="TopN">
          <el-input-number v-model="topN" :min="1" :max="100" :step="5" controls-position="right" style="width: 110px" />
        </el-form-item>
        <el-form-item label="持有期">
          <el-input-number v-model="holdingDay" :min="1" :max="60" :step="1" controls-position="right" style="width: 110px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="run">运行</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="loading">
        <template v-if="result">
          <el-alert type="info" :closable="false" show-icon class="note-alert" :title="result.note" />

          <div class="toolbar-row">
            <el-button v-if="result.window_stats.length" size="small" @click="exportCsv">导出窗口统计 CSV</el-button>
          </div>
          <el-table :data="result.window_stats" size="small" stripe border>
            <el-table-column prop="window_no" label="窗口" width="70" />
            <el-table-column label="区间" min-width="200">
              <template #default="{ row }">{{ row.start_date }} ~ {{ row.end_date }}</template>
            </el-table-column>
            <el-table-column prop="signal_days" label="信号日数" width="90" />
            <el-table-column prop="observations" label="观测数" width="90" />
            <el-table-column label="平均收益" width="110">
              <template #default="{ row }">
                <span :class="retClass(row.avg_return)">{{ pct(row.avg_return) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="胜率" width="100">
              <template #default="{ row }">{{ pct(row.win_rate) }}</template>
            </el-table-column>
          </el-table>

          <p class="verdict">{{ verdict }}</p>
        </template>
        <el-empty v-else-if="!loading" description="选择策略与区间后点击运行" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { runWalkForward, type WalkForwardResponse } from '@/api/walkforward'
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
const strategy = ref('CLASSIC_SELECTOR')
const startDate = ref(dayjs().subtract(120, 'day').format('YYYY-MM-DD'))
const endDate = ref(dayjs().format('YYYY-MM-DD'))
const windows = ref(4)
const topN = ref(10)
const holdingDay = ref(5)
const result = ref<WalkForwardResponse | null>(null)

function pct(v: number): string {
  return Number.isFinite(v) ? (v * 100).toFixed(2) + '%' : '—'
}
function retClass(v: number): string {
  if (!Number.isFinite(v)) return ''
  // A股红涨绿跌：正收益→红(text-up)，负收益→绿(text-down)
  return v > 0 ? 'text-up' : v < 0 ? 'text-down' : ''
}

const verdict = computed(() => {
  if (!result.value || !result.value.window_stats.length) return ''
  const valid = result.value.window_stats.filter(s => s.observations > 0)
  if (valid.length < 2) return '有效窗口不足，难以判断分段稳定性'
  const positive = valid.filter(s => s.avg_return > 0).length
  const ratio = positive / valid.length
  if (ratio >= 0.75) return `多数窗口（${positive}/${valid.length}）平均收益为正，策略表现分段稳定`
  if (ratio <= 0.25) return `多数窗口（${valid.length - positive}/${valid.length}）平均收益为负，策略分段表现偏弱`
  return `正负窗口接近（${positive}/${valid.length}），分段稳定性一般，需结合更多样本`
})

function exportCsv() {
  if (!result.value || !result.value.window_stats.length) return
  const r = result.value
  const lines: string[] = [
    `strategy,${r.strategy}`,
    `range,${r.start_date} ~ ${r.end_date}`,
    `windows,${r.windows}`,
    `top_n,${r.top_n}`,
    `holding_day,${r.holding_day}`,
    '',
    ['window_no', 'start_date', 'end_date', 'signal_days', 'observations', 'avg_return', 'win_rate'].join(','),
    ...r.window_stats.map((s) =>
      [s.window_no, s.start_date, s.end_date, s.signal_days, s.observations, s.avg_return, s.win_rate].join(','))
  ]
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `segment-stability-${r.strategy}-${r.start_date}_${r.end_date}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

async function run() {
  if (!strategy.value) {
    toast.warning('请选择策略')
    return
  }
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
    result.value = await runWalkForward({
      strategy: strategy.value,
      startDate: startDate.value,
      endDate: endDate.value,
      windows: windows.value,
      topN: topN.value,
      holdingDay: holdingDay.value
    })
  } catch (e: any) {
    toast.error(e?.message || '分段稳定性运行失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => run())
</script>

<style scoped>
.capability-tag { margin-left: 8px; vertical-align: middle; }
.wf-page {
  max-width: 1200px;
  margin: 0 auto;
}
.form {
  margin-bottom: 8px;
}
.note-alert {
  margin-bottom: 16px;
}
.verdict {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
