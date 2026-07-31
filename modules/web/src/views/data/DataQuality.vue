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

            <el-card shadow="never" class="q-card raw-card">
              <template #header>
                <div class="card-header-row">
                  <span>不复权行情 / 复权因子覆盖</span>
                  <el-button size="small" type="primary" @click="rawDialogVisible = true">回填不复权数据</el-button>
                </div>
              </template>
              <template v-if="report.raw_and_adjust">
                <div class="raw-section">
                  <div class="raw-sub-title">不复权行情（market_price_raw）</div>
                  <div class="q-meta">
                    <span>状态：{{ report.raw_and_adjust.market_price_raw.status }}</span>
                    <span>总行数：{{ report.raw_and_adjust.market_price_raw.total_rows }}</span>
                    <span>覆盖证券：{{ report.raw_and_adjust.market_price_raw.distinct_stocks }}</span>
                    <span>覆盖交易日：{{ report.raw_and_adjust.market_price_raw.distinct_trade_dates }}</span>
                    <span>区间：{{ report.raw_and_adjust.market_price_raw.earliest_date || '—' }} ~ {{ report.raw_and_adjust.market_price_raw.latest_date || '—' }}</span>
                  </div>
                </div>
                <div class="raw-section">
                  <div class="raw-sub-title">复权因子（adjust_factor）</div>
                  <div class="q-meta">
                    <span>状态：{{ report.raw_and_adjust.adjust_factor.status }}</span>
                    <span>总行数：{{ report.raw_and_adjust.adjust_factor.total_rows }}</span>
                    <span>覆盖证券：{{ report.raw_and_adjust.adjust_factor.distinct_stocks }}</span>
                    <span>本地推算：{{ report.raw_and_adjust.adjust_factor.by_source?.derived_local ?? 0 }} 条</span>
                    <span>官方源：{{ report.raw_and_adjust.adjust_factor.by_source?.official ?? 0 }} 条</span>
                  </div>
                  <p class="q-note">{{ report.raw_and_adjust.adjust_factor.note }}</p>
                </div>
              </template>
              <el-empty v-else description="无覆盖数据" :image-size="40" />
            </el-card>
          </div>
        </template>
        <el-empty v-else-if="!loading" description="点击刷新查看数据质量报告" />
      </div>
    </el-card>

    <!-- 不复权数据回填对话框 -->
    <el-dialog v-model="rawDialogVisible" title="回填不复权行情 + 推算复权因子" width="460px">
      <el-form label-width="90px">
        <el-form-item label="股票代码">
          <el-input v-model="rawForm.stockCode" placeholder="如 000001" />
        </el-form-item>
        <el-form-item label="交易所">
          <el-select v-model="rawForm.exchange" style="width: 100%">
            <el-option label="深交所 SZSE" value="SZSE" />
            <el-option label="上交所 SSE" value="SSE" />
            <el-option label="北交所 BSE" value="BSE" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker v-model="rawForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker v-model="rawForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <p class="q-note">
        将拉取东方财富不复权日 K 写入 market_price_raw，并按 qfq/raw 推算复权因子；已存在记录自动跳过（幂等）。
      </p>
      <template #footer>
        <el-button @click="rawDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="rawLoading" @click="runBackfillRaw">执行回填</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive, onMounted } from 'vue'
import dayjs from 'dayjs'
import { dataApi } from '@/api/data'
import { useToast } from '@/composables/useToast'

interface QualityReport {
  earliest_date: string
  latest_date: string
  trading_days: { expected: number; actual: number; missing_count: number; missing_dates: string[]; note: string }
  update_staleness: { today: string; latest_date?: string; days_behind: number | null; stale: boolean; threshold_days: number; warning: string | null }
  list_date: { missing_count: number; backfillable_count: number; note: string }
  adjust_factor: { current_basis: string; scope: string; warning: string }
  raw_and_adjust?: {
    market_price_raw: {
      total_rows: number; distinct_stocks: number; distinct_trade_dates: number
      earliest_date: string; latest_date: string; source: string; status: string
    }
    adjust_factor: {
      total_rows: number; distinct_stocks: number
      by_source?: { derived_local: number; official: number }
      by_reliability?: { limited: number }
      status: string; note: string
    }
  }
  health: { status: string; verdict: string }
}

const toast = useToast()
const loading = ref(false)
const report = ref<QualityReport | null>(null)

const rawDialogVisible = ref(false)
const rawLoading = ref(false)
const rawForm = reactive({
  stockCode: '',
  exchange: 'SZSE',
  startDate: dayjs().subtract(6, 'month').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD')
})

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

async function runBackfillRaw() {
  if (!rawForm.stockCode || !rawForm.startDate || !rawForm.endDate) {
    toast.warning('请填写股票代码与起止日期')
    return
  }
  rawLoading.value = true
  try {
    const res = await dataApi.backfillRaw({
      stockCode: rawForm.stockCode,
      exchange: rawForm.exchange,
      startDate: rawForm.startDate,
      endDate: rawForm.endDate
    })
    toast.success(res.message || `不复号新增 ${res.raw_inserted} 条，复权因子生成 ${res.factor_generated} 条`)
    rawDialogVisible.value = false
    await load()
  } catch (e: any) {
    toast.error(e?.message || '不复权数据回填失败')
  } finally {
    rawLoading.value = false
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
.raw-card {
  grid-column: 1 / -1;
}
.card-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.raw-section {
  margin-bottom: 12px;
}
.raw-section:last-child {
  margin-bottom: 0;
}
.raw-sub-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent-1);
  margin-bottom: 6px;
}
</style>
