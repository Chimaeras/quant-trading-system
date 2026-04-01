<template>
  <el-card shadow="hover">
    <template #header>
      <div class="header-row">
        <span>日频选股器（T+1）</span>
        <el-radio-group v-model="mode" size="small" @change="onModeChange">
          <el-radio-button value="classic">经典多因子</el-radio-button>
          <el-radio-button value="oversold">超跌反弹</el-radio-button>
        </el-radio-group>
      </div>
    </template>

    <!-- 经典 -->
    <el-form v-if="mode === 'classic'" inline>
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
      <el-form-item label="模板">
        <el-select v-model="form.templateId" clearable placeholder="可选模板" style="width: 180px">
          <el-option v-for="t in templates" :key="String(t.template_id)" :label="String(t.name)" :value="String(t.template_id)" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="runSelection">运行选股</el-button>
        <el-button @click="loadLatest">加载最新</el-button>
        <el-button @click="saveTemplate">保存模板</el-button>
        <el-button @click="exportCsv" :disabled="!results.length">导出清单</el-button>
      </el-form-item>
    </el-form>

    <!-- 超跌反弹 -->
    <el-form v-else inline>
      <el-form-item label="交易日">
        <el-date-picker v-model="oversoldForm.tradeDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="TopN">
        <el-input-number v-model="oversoldForm.topN" :min="1" :max="300" />
      </el-form-item>
      <el-form-item label="最低综合分">
        <el-input-number v-model="oversoldForm.minTotalScore" :min="0" :max="100" />
      </el-form-item>
      <el-form-item label="排除 ST">
        <el-switch v-model="oversoldForm.excludeST" />
      </el-form-item>
      <el-form-item label="落库">
        <el-switch v-model="oversoldForm.persist" />
      </el-form-item>
      <el-form-item label="模板">
        <el-select v-model="oversoldForm.templateId" clearable placeholder="可选（参数合并）" style="width: 180px">
          <el-option v-for="t in templates" :key="String(t.template_id)" :label="String(t.name)" :value="String(t.template_id)" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="runOversold">运行超跌筛选</el-button>
        <el-button @click="loadLatest">加载最新</el-button>
        <el-button @click="saveOversoldTemplate">保存模板</el-button>
        <el-button @click="exportCsv" :disabled="!results.length">导出清单</el-button>
      </el-form-item>
    </el-form>

    <div class="meta" v-if="resultMeta.result_id">
      <span>结果ID: {{ resultMeta.result_id }}</span>
      <span>交易日: {{ resultMeta.trade_date }}</span>
      <span v-if="mode === 'classic'">参数: {{ resultMeta.lookback_days }}天 / Top{{ resultMeta.top_n }}</span>
      <span v-else>Top{{ resultMeta.top_n }} / 最低综合分 {{ resultMeta.min_total_score ?? '—' }}</span>
      <span>因子版本: {{ resultMeta.factor_version || 'v1' }}</span>
    </div>

    <!-- 经典表格 -->
    <el-table v-if="mode === 'classic'" v-loading="loading" :data="results" stripe>
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

    <!-- 超跌表格 -->
    <el-table v-else v-loading="loading" :data="results" stripe>
      <el-table-column prop="rank_no" label="排名" width="70" />
      <el-table-column prop="stock_code" label="代码" min-width="95" />
      <el-table-column prop="stock_name" label="名称" min-width="120" />
      <el-table-column prop="final_score" label="综合分" min-width="88" />
      <el-table-column prop="trend_score" label="趋势(40)" min-width="88" />
      <el-table-column prop="volume_score" label="量能(30)" min-width="88" />
      <el-table-column prop="momentum_score" label="超跌(30)" min-width="88" />
      <el-table-column prop="volatility20" label="加权分" min-width="88" />
      <el-table-column prop="ret20" label="20日收益(%)" min-width="110" />
      <el-table-column prop="ret60" label="60日收益(%)" min-width="110" />
      <el-table-column label="操作" min-width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <p v-if="mode === 'classic'" class="tip">逻辑：趋势 + 动量 + 量价得分 - 风险惩罚；用于次日观察与买入决策参考。</p>
    <p v-else class="tip">逻辑：趋势确认(40) + 成交量验证(30) + 超跌程度(30)；结果落库 factor_version = oversold_rebound_v1，可与经典选股区分查询。</p>
  </el-card>

  <el-dialog v-model="detailVisible" title="选股详情" width="640px">
    <el-descriptions :column="2" border v-if="detailRow">
      <el-descriptions-item label="股票">{{ detailRow.stock_code }} {{ detailRow.stock_name }}</el-descriptions-item>
      <el-descriptions-item label="综合分">{{ detailRow.final_score }}</el-descriptions-item>
      <template v-if="mode === 'oversold'">
        <el-descriptions-item label="趋势(40)">{{ detailRow.trend_score }}</el-descriptions-item>
        <el-descriptions-item label="量能(30)">{{ detailRow.volume_score }}</el-descriptions-item>
        <el-descriptions-item label="超跌(30)">{{ detailRow.momentum_score }}</el-descriptions-item>
        <el-descriptions-item label="加权分">{{ detailRow.volatility20 }}</el-descriptions-item>
      </template>
      <template v-else>
        <el-descriptions-item label="趋势分">{{ detailRow.trend_score }}</el-descriptions-item>
        <el-descriptions-item label="动量分">{{ detailRow.momentum_score }}</el-descriptions-item>
        <el-descriptions-item label="量价分">{{ detailRow.volume_score }}</el-descriptions-item>
        <el-descriptions-item label="风险惩罚">{{ detailRow.risk_penalty }}</el-descriptions-item>
      </template>
      <el-descriptions-item label="20日收益">{{ detailRow.ret20 }}%</el-descriptions-item>
      <el-descriptions-item label="60日收益">{{ detailRow.ret60 }}%</el-descriptions-item>
      <el-descriptions-item v-if="mode === 'classic'" label="20日波动">{{ detailRow.volatility20 }}%</el-descriptions-item>
      <el-descriptions-item v-if="mode === 'classic'" label="60日回撤">{{ detailRow.max_drawdown60 }}%</el-descriptions-item>
      <el-descriptions-item label="日均成交额" :span="2">{{ detailRow.avg_amount20 }}</el-descriptions-item>
      <el-descriptions-item label="明细/原因" :span="2">
        <pre class="json-box">{{ JSON.stringify(detailRow.reasons || detailRow.detail || {}, null, 2) }}</pre>
      </el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { selectorApi, OVERSOLD_REBOUND_FACTOR_VERSION } from '@/api/selector'

const loading = ref(false)
const mode = ref<'classic' | 'oversold'>('classic')
const results = ref<any[]>([])
const detailVisible = ref(false)
const detailRow = ref<any>(null)
const resultMeta = reactive({
  result_id: '',
  trade_date: '',
  lookback_days: 60,
  top_n: 10,
  factor_version: 'v1',
  min_total_score: undefined as number | undefined
})
const templates = ref<Array<Record<string, unknown>>>([])

const form = reactive({
  tradeDate: dayjs().format('YYYY-MM-DD'),
  lookbackDays: 60,
  topN: 10,
  minAvgAmount: 30000000,
  minTrendScore: 40,
  templateId: ''
})

const oversoldForm = reactive({
  tradeDate: dayjs().format('YYYY-MM-DD'),
  topN: 50,
  minTotalScore: 60,
  excludeST: true,
  persist: true,
  templateId: ''
})

const applyResult = (data: Record<string, any>) => {
  resultMeta.result_id = String(data.result_id ?? '')
  resultMeta.trade_date = String(data.trade_date ?? '')
  resultMeta.lookback_days = Number(data.lookback_days ?? form.lookbackDays)
  resultMeta.top_n = Number(data.top_n ?? form.topN)
  resultMeta.factor_version = String(data.factor_version ?? 'v1')
  resultMeta.min_total_score =
    data.min_total_score !== undefined && data.min_total_score !== null ? Number(data.min_total_score) : undefined
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

const runOversold = async () => {
  loading.value = true
  try {
    const data = await selectorApi.runOversoldRebound({
      tradeDate: oversoldForm.tradeDate,
      topN: oversoldForm.topN,
      minTotalScore: oversoldForm.minTotalScore,
      excludeST: oversoldForm.excludeST,
      persist: oversoldForm.persist,
      templateId: oversoldForm.templateId || undefined
    })
    applyResult(data)
    ElMessage.success(`超跌筛选完成，共 ${results.value.length} 只`)
  } catch (e: any) {
    ElMessage.error(e?.message || '运行超跌筛选失败')
  } finally {
    loading.value = false
  }
}

const loadTemplates = async () => {
  templates.value = await selectorApi.listTemplates()
}

const saveTemplate = async () => {
  const name = `经典-${dayjs().format('MMDD-HHmm')}`
  await selectorApi.saveTemplate({
    templateId: form.templateId || undefined,
    name,
    params: {
      lookbackDays: form.lookbackDays,
      topN: form.topN,
      minAvgAmount: form.minAvgAmount,
      minTrendScore: form.minTrendScore
    }
  })
  ElMessage.success('模板已保存')
  await loadTemplates()
}

const saveOversoldTemplate = async () => {
  const name = `超跌反弹-${dayjs().format('MMDD-HHmm')}`
  await selectorApi.saveTemplate({
    templateId: oversoldForm.templateId || undefined,
    name,
    params: {
      topN: oversoldForm.topN,
      minTotalScore: oversoldForm.minTotalScore,
      excludeST: oversoldForm.excludeST,
      persist: oversoldForm.persist
    }
  })
  ElMessage.success('模板已保存')
  await loadTemplates()
}

const exportCsv = () => {
  if (mode.value === 'classic') {
    const headers = ['rank_no', 'stock_code', 'stock_name', 'final_score', 'trend_score', 'momentum_score', 'ret20', 'ret60', 'max_drawdown60']
    const lines = [headers.join(',')]
    for (const r of results.value) {
      lines.push(headers.map(h => String((r as any)[h] ?? '')).join(','))
    }
    downloadCsv(lines, `stock-picker-${resultMeta.trade_date || dayjs().format('YYYY-MM-DD')}.csv`)
  } else {
    const headers = ['rank_no', 'stock_code', 'stock_name', 'final_score', 'trend_score_40', 'volume_score_30', 'oversold_score_30', 'weighted_score', 'ret20', 'ret60']
    const lines = [headers.join(',')]
    for (const r of results.value) {
      const row = [
        r.rank_no,
        r.stock_code,
        r.stock_name,
        r.final_score,
        r.trend_score_40 ?? r.trend_score,
        r.volume_score_30 ?? r.volume_score,
        r.oversold_score_30 ?? r.momentum_score,
        r.weighted_score ?? r.volatility20,
        r.ret20,
        r.ret60
      ]
      lines.push(row.map(v => String(v ?? '')).join(','))
    }
    downloadCsv(lines, `oversold-rebound-${resultMeta.trade_date || dayjs().format('YYYY-MM-DD')}.csv`)
  }
}

function downloadCsv(lines: string[], filename: string) {
  const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

const loadLatest = async () => {
  loading.value = true
  try {
    const tradeDate = mode.value === 'classic' ? form.tradeDate : oversoldForm.tradeDate
    const factorVersion = mode.value === 'oversold' ? OVERSOLD_REBOUND_FACTOR_VERSION : undefined
    const data = await selectorApi.getLatest(tradeDate, factorVersion)
    applyResult(data)
    if (!results.value.length) ElMessage.info('当日暂无该类型选股结果，请先运行')
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

function onModeChange() {
  loadLatest()
}

onMounted(async () => {
  await loadTemplates()
  await loadLatest()
})
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.meta {
  display: flex;
  gap: 20px;
  margin: 8px 0 12px;
  color: #475569;
  font-size: 13px;
  flex-wrap: wrap;
}
.tip {
  color: #64748b;
  font-size: 12px;
  margin-top: 12px;
}
.json-box {
  margin: 0;
  white-space: pre-wrap;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 8px;
  max-height: 220px;
  overflow: auto;
}
</style>
