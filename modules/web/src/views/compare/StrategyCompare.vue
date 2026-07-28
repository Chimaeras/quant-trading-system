<template>
  <div class="compare-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <span>策略对比工作台</span>
      </template>

      <el-form inline class="form">
        <el-form-item label="交易日">
          <el-date-picker v-model="tradeDate" type="date" value-format="YYYY-MM-DD" placeholder="选择交易日" />
        </el-form-item>
        <el-form-item label="TopN">
          <el-input-number v-model="topN" :min="1" :max="100" :step="5" controls-position="right" style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="runCompare">对比</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="loading">
        <template v-if="result">
          <el-alert
            :type="result.overlap_summary.overlap_count > 0 ? 'success' : 'info'"
            :closable="false"
            show-icon
            class="verdict-alert"
            :title="result.overlap_summary.verdict"
          />

          <div v-if="result.overlap_stocks.length" class="overlap-section">
            <div class="section-title">多策略重合标的（共识最强）</div>
            <el-table :data="result.overlap_stocks" size="small" stripe border>
              <el-table-column prop="stock_code" label="代码" min-width="90" />
              <el-table-column prop="stock_name" label="名称" min-width="100" />
              <el-table-column prop="hit_count" label="命中策略数" width="110" />
              <el-table-column label="命中策略" min-width="240">
                <template #default="{ row }">
                  <el-tag v-for="s in row.hit_strategies" :key="s" size="small" class="tag">{{ strategyLabel(s) }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="section-title">各策略候选并排</div>
          <div class="grid">
            <el-card v-for="s in result.strategies" :key="s.strategy" shadow="never" class="strategy-card">
              <template #header>
                <div class="card-header">
                  <span>{{ strategyLabel(s.strategy) }}</span>
                  <el-tag size="small">{{ s.returned_items }} 只</el-tag>
                </div>
              </template>
              <el-table :data="s.top_items" size="small" stripe border>
                <el-table-column prop="rank_no" label="排名" width="60" />
                <el-table-column prop="stock_code" label="代码" min-width="80" />
                <el-table-column prop="stock_name" label="名称" min-width="90" />
                <el-table-column label="得分" min-width="70">
                  <template #default="{ row }">{{ formatScore(row.final_score) }}</template>
                </el-table-column>
              </el-table>
            </el-card>
          </div>
        </template>
        <el-empty v-else-if="!loading" description="选择交易日与 TopN 后点击对比" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { compareStrategies, type CompareResponse } from '@/api/compare'
import { useToast } from '@/composables/useToast'
import dayjs from 'dayjs'

const toast = useToast()
const loading = ref(false)
const tradeDate = ref(dayjs().format('YYYY-MM-DD'))
const topN = ref(10)
const result = ref<CompareResponse | null>(null)

const STRATEGY_LABELS: Record<string, string> = {
  CLASSIC_SELECTOR: '经典多因子',
  OVERSOLD_REBOUND: '超跌反弹',
  TREND: '趋势打分',
  COMPREHENSIVE: '综合三维度',
  MULTI_DIM: '多维度 v1'
}

function strategyLabel(s: string): string {
  return STRATEGY_LABELS[s] || s
}

function formatScore(v: unknown): string {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '—'
}

async function runCompare() {
  if (!tradeDate.value) {
    toast.warning('请选择交易日')
    return
  }
  loading.value = true
  try {
    result.value = await compareStrategies(tradeDate.value, topN.value)
  } catch (e: any) {
    toast.error(e?.message || '策略对比失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => runCompare())
</script>

<style scoped>
.compare-page {
  max-width: 1400px;
  margin: 0 auto;
}
.form {
  margin-bottom: 8px;
}
.verdict-alert {
  margin-bottom: 16px;
}
.overlap-section {
  margin-bottom: 20px;
}
.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 10px;
  color: var(--text-primary);
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}
.strategy-card {
  background: rgba(9, 18, 34, 0.72);
  border: 1px solid rgba(145, 196, 255, 0.18);
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.tag {
  margin-right: 4px;
}
</style>
