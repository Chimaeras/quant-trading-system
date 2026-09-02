<template>
  <PageShell>
    <PageHeader title="持仓与选股信号对账" subtitle="输入选股结果 ID（在「选股打分」页运行后获得），与当前模拟账户持仓比对，输出建议买入 / 卖出 / 持有差异" />

    <SectionCard>
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
            <SectionCard title="建议买入关注" pad="sm">
              <template #extra><el-tag type="danger" size="small">{{ result.to_buy.length }}</el-tag></template>
              <el-table :data="result.to_buy" size="small" stripe border>
                <el-table-column prop="rank_no" label="排名" width="70" />
                <el-table-column prop="stock_code" label="代码" min-width="90" />
                <el-table-column prop="stock_name" label="名称" min-width="100" />
                <el-table-column label="得分" min-width="80">
                  <template #default="{ row }">{{ formatScore(row.final_score) }}</template>
                </el-table-column>
              </el-table>
              <EmptyState v-if="!result.to_buy.length" message="候选均已持有" />
            </SectionCard>

            <SectionCard title="建议减仓 / 退出关注" pad="sm">
              <template #extra><el-tag type="warning" size="small">{{ result.to_sell.length }}</el-tag></template>
              <el-table :data="result.to_sell" size="small" stripe border>
                <el-table-column prop="stock_code" label="代码" min-width="90" />
                <el-table-column prop="stock_name" label="名称" min-width="100" />
                <el-table-column prop="volume" label="持仓量" min-width="90" />
                <el-table-column label="盈亏率" min-width="90">
                  <template #default="{ row }">{{ formatPct(row.profit_rate) }}</template>
                </el-table-column>
              </el-table>
              <EmptyState v-if="!result.to_sell.length" message="持仓均在候选中" />
            </SectionCard>

            <SectionCard title="已持有且在候选中" pad="sm">
              <template #extra><el-tag type="success" size="small">{{ result.held_in_candidates.length }}</el-tag></template>
              <el-table :data="result.held_in_candidates" size="small" stripe border>
                <el-table-column prop="stock_code" label="代码" min-width="90" />
                <el-table-column prop="stock_name" label="名称" min-width="100" />
                <el-table-column prop="volume" label="持仓量" min-width="90" />
                <el-table-column label="盈亏率" min-width="90">
                  <template #default="{ row }">{{ formatPct(row.profit_rate) }}</template>
                </el-table-column>
              </el-table>
              <EmptyState v-if="!result.held_in_candidates.length" message="无交集" />
            </SectionCard>
          </div>
        </template>
        <EmptyState v-else-if="!loading" message="输入选股结果 ID 后点击「对账」" />
      </div>
    </SectionCard>
  </PageShell>
</template>

<script setup lang="ts">
import PageShell from '@/components/ui/PageShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { ref } from 'vue'
import { reconcilePosition, type ReconcileResponse } from '@/api/reconcile'
import { errorText } from '@/api/request'
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
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.reconcile-form {
  margin: 0 0 8px;
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
</style>
