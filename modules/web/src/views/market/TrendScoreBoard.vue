<template>
  <el-card shadow="hover">
    <template #header>
      <div class="header-row">
        <span>趋势选股打分（0–100）</span>
        <span class="hint">分项：趋势30 + 乖离20 + 量能15 + 支撑10 + MACD15 + RSI10</span>
      </div>
    </template>

    <el-form inline>
      <el-form-item label="交易日">
        <el-date-picker v-model="form.tradeDate" type="date" value-format="YYYY-MM-DD" placeholder="查询/打分时可选" clearable />
      </el-form-item>
      <el-form-item label="乖离阈值(%)">
        <el-input-number v-model="form.biasThreshold" :min="1" :max="50" :step="0.5" :precision="1" />
      </el-form-item>
      <el-form-item label="落库">
        <el-switch v-model="form.persist" />
      </el-form-item>
      <el-form-item label="每页">
        <el-input-number v-model="pageSize" :min="10" :max="200" :step="10" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="runBatch">执行打分</el-button>
        <el-button :loading="loading" @click="loadList">刷新列表</el-button>
      </el-form-item>
    </el-form>

    <div class="meta" v-if="meta.trade_date">
      <span>列表交易日: {{ meta.trade_date }}</span>
      <span>共 {{ meta.total }} 条</span>
      <span v-if="lastRun">最近运行: {{ lastRun.trade_date }} / 扫描 {{ lastRun.scanned }} / 有效 {{ lastRun.scored }}</span>
    </div>

    <el-table v-loading="loading" :data="rows" stripe border>
      <el-table-column type="index" label="#" width="55" :index="indexMethod" />
      <el-table-column prop="stock_code" label="代码" min-width="90" />
      <el-table-column prop="stock_name" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="total_score" label="总分" min-width="80" />
      <el-table-column prop="score_trend" label="趋势" width="72" />
      <el-table-column prop="score_bias" label="乖离" width="72" />
      <el-table-column prop="score_volume" label="量能" width="72" />
      <el-table-column prop="score_support" label="支撑" width="72" />
      <el-table-column prop="score_macd" label="MACD" width="78" />
      <el-table-column prop="score_rsi" label="RSI" width="72" />
      <el-table-column prop="trend_status" label="趋势状态" min-width="100" show-overflow-tooltip />
      <el-table-column prop="volume_status" label="量能" min-width="110" show-overflow-tooltip />
      <el-table-column prop="macd_status" label="MACD" min-width="100" show-overflow-tooltip />
      <el-table-column prop="rsi_status" label="RSI" min-width="90" />
      <el-table-column prop="buy_signal" label="信号" min-width="100" />
    </el-table>

    <div class="pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="meta.total"
        :page-size="pageSize"
        :current-page="page + 1"
        @current-change="onPage"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { scoringApi, type TrendRunResult, type TrendScoreRow } from '@/api/scoring'

const form = reactive({
  tradeDate: '' as string | undefined,
  biasThreshold: 5,
  persist: true
})

const loading = ref(false)
const running = ref(false)
const rows = ref<TrendScoreRow[]>([])
const meta = reactive({ trade_date: '', total: 0 })
const page = ref(0)
const pageSize = ref(50)
const lastRun = ref<TrendRunResult | null>(null)

function indexMethod(index: number) {
  return page.value * pageSize.value + index + 1
}

async function loadList() {
  loading.value = true
  try {
    const d = await scoringApi.getTrendScores({
      tradeDate: form.tradeDate || undefined,
      page: page.value,
      size: pageSize.value
    })
    meta.trade_date = d.trade_date
    meta.total = d.total
    rows.value = d.list ?? []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function runBatch() {
  running.value = true
  try {
    const data = await scoringApi.runTrendScores({
      tradeDate: form.tradeDate || undefined,
      biasThreshold: form.biasThreshold,
      persist: form.persist
    })
    lastRun.value = data
    ElMessage.success(`打分完成：有效 ${data.scored} / 扫描 ${data.scanned}`)
    page.value = 0
    form.tradeDate = data.trade_date
    await loadList()
  } finally {
    running.value = false
  }
}

function onPage(p: number) {
  page.value = p - 1
  loadList()
}

onMounted(() => {
  loadList().catch(() => {})
})
</script>

<style scoped>
.header-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.hint {
  font-size: 12px;
  color: #64748b;
  font-weight: normal;
}
.meta {
  margin-bottom: 12px;
  font-size: 13px;
  color: #475569;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.pager {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
