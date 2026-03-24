<template>
  <el-card shadow="hover">
    <template #header>日频选股器（T+1）</template>
    <el-form inline>
      <el-form-item label="交易日">
        <el-date-picker v-model="form.tradeDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="回看天数">
        <el-input-number v-model="form.lookbackDays" :min="20" :max="120" :step="5" />
      </el-form-item>
      <el-form-item label="TopN">
        <el-input-number v-model="form.topN" :min="1" :max="50" />
      </el-form-item>
      <el-form-item label="最小日均成交额">
        <el-input-number v-model="form.minAvgAmount" :min="1000000" :step="5000000" />
      </el-form-item>
      <el-form-item label="最小趋势分">
        <el-input-number v-model="form.minTrendScore" :min="0" :max="100" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="runSelection">运行选股</el-button>
        <el-button @click="loadLatest">加载最新</el-button>
      </el-form-item>
    </el-form>
    <div class="meta" v-if="resultMeta.result_id">
      <span>结果ID: {{ resultMeta.result_id }}</span>
      <span>交易日: {{ resultMeta.trade_date }}</span>
      <span>参数: {{ resultMeta.lookback_days }}天 / Top{{ resultMeta.top_n }}</span>
    </div>
    <el-table v-loading="loading" :data="results" stripe>
      <el-table-column prop="rank_no" label="排名" width="70" />
      <el-table-column prop="stock_code" label="代码" min-width="95" />
      <el-table-column prop="stock_name" label="名称" min-width="120" />
      <el-table-column prop="final_score" label="综合分" min-width="90" />
      <el-table-column prop="trend_score" label="趋势分" min-width="90" />
      <el-table-column prop="momentum_score" label="动量分" min-width="90" />
      <el-table-column prop="ret20" label="20日收益(%)" min-width="110" />
      <el-table-column prop="ret60" label="60日收益(%)" min-width="110" />
      <el-table-column prop="max_drawdown60" label="60日回撤(%)" min-width="110" />
      <el-table-column label="操作" min-width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="tip">逻辑：趋势 + 动量 + 量价得分 - 风险惩罚；用于次日观察与买入决策参考。</p>
  </el-card>

  <el-dialog v-model="detailVisible" title="选股详情" width="640px">
    <el-descriptions :column="2" border v-if="detailRow">
      <el-descriptions-item label="股票">{{ detailRow.stock_code }} {{ detailRow.stock_name }}</el-descriptions-item>
      <el-descriptions-item label="综合分">{{ detailRow.final_score }}</el-descriptions-item>
      <el-descriptions-item label="趋势分">{{ detailRow.trend_score }}</el-descriptions-item>
      <el-descriptions-item label="动量分">{{ detailRow.momentum_score }}</el-descriptions-item>
      <el-descriptions-item label="量价分">{{ detailRow.volume_score }}</el-descriptions-item>
      <el-descriptions-item label="风险惩罚">{{ detailRow.risk_penalty }}</el-descriptions-item>
      <el-descriptions-item label="20日收益">{{ detailRow.ret20 }}%</el-descriptions-item>
      <el-descriptions-item label="60日收益">{{ detailRow.ret60 }}%</el-descriptions-item>
      <el-descriptions-item label="20日波动">{{ detailRow.volatility20 }}%</el-descriptions-item>
      <el-descriptions-item label="60日回撤">{{ detailRow.max_drawdown60 }}%</el-descriptions-item>
      <el-descriptions-item label="日均成交额" :span="2">{{ detailRow.avg_amount20 }}</el-descriptions-item>
      <el-descriptions-item label="入选原因" :span="2">
        <pre class="json-box">{{ JSON.stringify(detailRow.reasons || {}, null, 2) }}</pre>
      </el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { selectorApi } from '@/api/selector'

const loading = ref(false)
const results = ref<any[]>([])
const detailVisible = ref(false)
const detailRow = ref<any>(null)
const resultMeta = reactive({
  result_id: '',
  trade_date: '',
  lookback_days: 60,
  top_n: 10
})

const form = reactive({
  tradeDate: dayjs().format('YYYY-MM-DD'),
  lookbackDays: 60,
  topN: 10,
  minAvgAmount: 30000000,
  minTrendScore: 40
})

const applyResult = (data: Record<string, any>) => {
  resultMeta.result_id = String(data.result_id ?? '')
  resultMeta.trade_date = String(data.trade_date ?? '')
  resultMeta.lookback_days = Number(data.lookback_days ?? form.lookbackDays)
  resultMeta.top_n = Number(data.top_n ?? form.topN)
  results.value = Array.isArray(data.items) ? data.items : []
}

const runSelection = async () => {
  loading.value = true
  try {
    const data = await selectorApi.run(form)
    applyResult(data)
    ElMessage.success(`选股完成，共 ${results.value.length} 只`)
  } catch (e: any) {
    ElMessage.error(e?.message || '运行选股失败')
  } finally {
    loading.value = false
  }
}

const loadLatest = async () => {
  loading.value = true
  try {
    const data = await selectorApi.getLatest(form.tradeDate)
    applyResult(data)
    if (!results.value.length) ElMessage.info('当日暂无选股结果，请先运行选股')
  } catch (e: any) {
    ElMessage.error(e?.message || '加载选股结果失败')
  } finally {
    loading.value = false
  }
}

const showDetail = (row: any) => {
  detailRow.value = row
  detailVisible.value = true
}

onMounted(loadLatest)
</script>

<style scoped>
.meta { display: flex; gap: 20px; margin: 8px 0 12px; color: #475569; font-size: 13px; }
.tip { color: #64748b; font-size: 12px; margin-top: 12px; }
.json-box {
  margin: 0;
  white-space: pre-wrap;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 8px;
  max-height: 180px;
  overflow: auto;
}
</style>
