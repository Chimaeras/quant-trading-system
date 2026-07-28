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
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="run">研究</el-button>
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
              <template #header>研究范围</template>
              <div class="q-meta">
                <span>策略：{{ strategyLabel(result.strategy) }}</span>
                <span>区间：{{ result.start_date }} ~ {{ result.end_date }}</span>
              </div>
            </el-card>
          </div>
        </template>
        <el-empty v-else-if="!loading" description="选择区间与策略后点击研究" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getFactorResearch, type FactorResearchResponse } from '@/api/factor'
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
const result = ref<FactorResearchResponse | null>(null)

function strategyLabel(s: string): string {
  if (s === 'ALL') return '全部策略'
  return STRATEGY_LABELS[s] || s
}

function fmt(v: number): string {
  return Number.isFinite(v) ? v.toFixed(2) : '—'
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
      endDate: endDate.value
    })
  } catch (e: any) {
    toast.error(e?.message || '因子研究失败')
  } finally {
    loading.value = false
  }
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
</style>