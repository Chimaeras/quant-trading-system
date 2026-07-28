<template>
  <div class="quality-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <div class="header">
          <span>数据质量驾驶舱</span>
          <el-button type="primary" size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <div v-loading="loading">
        <template v-if="report">
          <el-alert
            :type="healthType"
            :closable="false"
            show-icon
            class="health-alert"
            :title="report.health.verdict"
          />

          <div class="grid">
            <el-card shadow="never" class="q-card">
              <template #header>交易日覆盖</template>
              <div class="q-meta">
                <span>区间：{{ report.earliest_date || '—' }} ~ {{ report.latest_date || '—' }}</span>
                <span>预期交易日：{{ report.trading_days.expected }}</span>
                <span>实际交易日：{{ report.trading_days.actual }}</span>
                <span :class="{ 'text-danger': report.trading_days.missing_count > 0 }">
                  缺失：{{ report.trading_days.missing_count }}
                </span>
              </div>
              <p class="q-note">{{ report.trading_days.note }}</p>
            </el-card>

            <el-card shadow="never" class="q-card">
              <template #header>更新滞后</template>
              <div class="q-meta">
                <span>今日：{{ report.update_staleness.today }}</span>
                <span>最新行情：{{ report.update_staleness.latest_date || '—' }}</span>
                <span>滞后天数：{{ report.update_staleness.days_behind ?? '—' }}</span>
                <span :class="report.update_staleness.stale ? 'text-danger' : 'text-success'">
                  {{ report.update_staleness.stale ? '滞后' : '正常' }}
                </span>
              </div>
              <p v-if="report.update_staleness.warning" class="q-note text-danger">
                {{ report.update_staleness.warning }}
              </p>
            </el-card>

            <el-card shadow="never" class="q-card">
              <template #header>上市日缺失</template>
              <div class="q-meta">
                <span>缺失数量：{{ report.list_date.missing_count }}</span>
                <span>可回填：{{ report.list_date.backfillable_count }}</span>
              </div>
              <p class="q-note">{{ report.list_date.note }}</p>
            </el-card>

            <el-card shadow="never" class="q-card">
              <template #header>复权口径</template>
              <div class="q-meta">
                <span>当前基准：{{ report.adjust_factor.current_basis }}</span>
              </div>
              <p class="q-note">{{ report.adjust_factor.scope }}</p>
              <p class="q-note text-warning">{{ report.adjust_factor.warning }}</p>
            </el-card>
          </div>
        </template>
        <el-empty v-else-if="!loading" description="点击刷新查看数据质量报告" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { dataApi } from '@/api/data'
import { useToast } from '@/composables/useToast'

interface QualityReport {
  earliest_date: string
  latest_date: string
  trading_days: { expected: number; actual: number; missing_count: number; missing_dates: string[]; note: string }
  update_staleness: { today: string; latest_date?: string; days_behind: number | null; stale: boolean; threshold_days: number; warning: string | null }
  list_date: { missing_count: number; backfillable_count: number; note: string }
  adjust_factor: { current_basis: string; scope: string; warning: string }
  health: { status: string; verdict: string }
}

const toast = useToast()
const loading = ref(false)
const report = ref<QualityReport | null>(null)

const healthType = computed<'success' | 'warning' | 'info'>(() => {
  if (!report.value) return 'info'
  return report.value.health.status === 'ok' ? 'success' : 'warning'
})

async function load() {
  loading.value = true
  try {
    report.value = await dataApi.getQuality()
  } catch (e: any) {
    toast.error(e?.message || '数据质量报告加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<style scoped>
.quality-page {
  max-width: 1200px;
  margin: 0 auto;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.health-alert {
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
.q-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.text-danger { color: #f56c6c; }
.text-success { color: #67c23a; }
.text-warning { color: #e6a23c; }
</style>
