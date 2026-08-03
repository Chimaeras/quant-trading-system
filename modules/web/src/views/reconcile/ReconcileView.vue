<template>
  <div class="reconcile-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <span>持仓与选股信号对账</span>
      </template>

      <el-form inline class="reconcile-form">
        <el-form-item label="选股结果 ID">
          <el-input v-model="resultId" placeholder="如 US20250102xxxx" style="width: 240px" clearable />
        </el-form-item>
        <el-form-item label="TopN">
          <el-input-number v-model="topN" :min="1" :max="100" :step="5" controls-position="right" style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="runReconcile">对账</el-button>
        </el-form-item>
      </el-form>

      <p class="hint">
        输入选股结果 ID（可在「选股打分」页运行后获得），与当前模拟账户持仓比对，输出建议买入/卖出/持有差异。
      </p>

      <div v-loading="loading">
        <template v-if="result">
          <div class="meta">
            <span>策略：{{ result.strategy || '—' }}</span>
            <span>信号日：{{ result.trade_date || '—' }}</span>
            <span>目标持仓：{{ result.target_count }} 只</span>
            <span>实际持仓：{{ result.actual_count }} 只</span>
          </div>
          <el-alert type="info" :closable="false" show-icon class="note-alert" :title="result.note" />

          <div class="grid">
            <el-card shadow="never" class="section-card">
              <template #header>
                <div class="section-header">
                  <span>建议买入关注</span>
                  <el-tag type="danger" size="small">{{ result.to_buy.length }}</el-tag>
                </div>
              </template>
              <el-table :data="result.to_buy" size="small" stripe border>
                <el-table-column prop="rank_no" label="排名" width="70" />
                <el-table-column prop="stock_code" label="代码" min-width="90" />
                <el-table-column prop="stock_name" label="名称" min-width="100" />
                <el-table-column label="得分" min-width="80">
                  <template #default="{ row }">{{ formatScore(row.final_score) }}</template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!result.to_buy.length" description="候选均已持有" :image-size="40" />
            </el-card>

            <el-card shadow="never" class="section-card">
              <template #header>
                <div class="section-header">
                  <span>建议减仓/退出关注</span>
                  <el-tag type="warning" size="small">{{ result.to_sell.length }}</el-tag>
                </div>
              </template>
              <el-table :data="result.to_sell" size="small" stripe border>
                <el-table-column prop="stock_code" label="代码" min-width="90" />
                <el-table-column prop="stock_name" label="名称" min-width="100" />
                <el-table-column prop="volume" label="持仓量" min-width="90" />
                <el-table-column label="盈亏率" min-width="90">
                  <template #default="{ row }">{{ formatPct(row.profit_rate) }}</template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!result.to_sell.length" description="持仓均在候选中" :image-size="40" />
            </el-card>

            <el-card shadow="never" class="section-card">
              <template #header>
                <div class="section-header">
                  <span>已持有且在候选中</span>
                  <el-tag type="success" size="small">{{ result.held_in_candidates.length }}</el-tag>
                </div>
              </template>
              <el-table :data="result.held_in_candidates" size="small" stripe border>
                <el-table-column prop="stock_code" label="代码" min-width="90" />
                <el-table-column prop="stock_name" label="名称" min-width="100" />
                <el-table-column prop="volume" label="持仓量" min-width="90" />
                <el-table-column label="盈亏率" min-width="90">
                  <template #default="{ row }">{{ formatPct(row.profit_rate) }}</template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!result.held_in_candidates.length" description="无交集" :image-size="40" />
            </el-card>
          </div>
        </template>
        <el-empty v-else-if="!loading" description="输入选股结果 ID 后点击「对账」" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { reconcilePosition, type ReconcileResponse } from '@/api/reconcile'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const loading = ref(false)
const resultId = ref('')
const topN = ref(10)
const result = ref<ReconcileResponse | null>(null)

function formatScore(value: unknown): string {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(2) : '—'
}

function formatPct(value: unknown): string {
  const n = Number(value)
  return Number.isFinite(n) ? `${(n * 100).toFixed(2)}%` : '—'
}

async function runReconcile() {
  if (!resultId.value.trim()) {
    toast.warning('请输入选股结果 ID')
    return
  }
  loading.value = true
  try {
    result.value = await reconcilePosition(resultId.value.trim(), topN.value)
  } catch (e: any) {
    toast.error(e?.message || '对账失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.reconcile-page {
  max-width: 1200px;
  margin: 0 auto;
}
.reconcile-form {
  margin-bottom: 8px;
}
.hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--text-secondary);
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
.note-alert {
  margin-bottom: 16px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}
.section-card {
  background: var(--surface-2);
  border: 1px solid var(--hairline-strong);
}
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
</style>
