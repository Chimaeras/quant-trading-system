<template>
  <div class="screener-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <span>条件选股器</span>
      </template>

      <el-form inline class="form">
        <el-form-item label="关键字">
          <el-input v-model="form.keyword" placeholder="代码/名称" style="width: 160px" clearable />
        </el-form-item>
        <el-form-item label="交易所">
          <el-select v-model="form.exchange" placeholder="全部" style="width: 100px" clearable>
            <el-option label="沪市" value="SSE" />
            <el-option label="深市" value="SZSE" />
            <el-option label="北交所" value="BSE" />
          </el-select>
        </el-form-item>
        <el-form-item label="行业">
          <el-input v-model="form.industry" placeholder="如 银行" style="width: 120px" clearable />
        </el-form-item>
        <el-form-item label="上市≥(天)">
          <el-input-number v-model="form.minListingDays" :min="0" :max="3650" :step="30" controls-position="right" style="width: 130px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="run">筛选</el-button>
        </el-form-item>
      </el-form>

      <p class="hint">
        MVP 条件选股器，基于股票基本信息筛选；实时行情过滤（涨跌幅/成交额）为后续扩展，可与「选股打分」结果联动。
      </p>

      <div v-loading="loading">
        <template v-if="result">
          <div class="meta">
            <span>参考日：{{ result.reference_date }}</span>
            <span>筛选前：{{ result.total_before_filter }}</span>
            <span>返回：{{ result.returned }}</span>
          </div>
          <el-table :data="result.items" size="small" stripe border>
            <el-table-column type="index" label="#" width="56" />
            <el-table-column prop="stock_code" label="代码" min-width="90" />
            <el-table-column prop="exchange_type" label="交易所" min-width="80" />
            <el-table-column prop="stock_name" label="名称" min-width="100" />
            <el-table-column prop="industry" label="行业" min-width="100" />
            <el-table-column prop="list_date" label="上市日" min-width="110" />
            <el-table-column prop="listing_days" label="上市天数" min-width="90" />
          </el-table>
          <el-empty v-if="!result.items.length" description="无符合条件的标的" />
        </template>
        <el-empty v-else-if="!loading" description="设置条件后点击筛选" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { runScreener, type ScreenerResponse } from '@/api/screener'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const loading = ref(false)
const result = ref<ScreenerResponse | null>(null)

const form = reactive({
  keyword: '',
  exchange: '',
  industry: '',
  minListingDays: 60
})

async function run() {
  loading.value = true
  try {
    result.value = await runScreener({
      keyword: form.keyword || undefined,
      exchange: form.exchange || undefined,
      industry: form.industry || undefined,
      minListingDays: form.minListingDays || undefined,
      page: 1,
      size: 50
    })
  } catch (e: any) {
    toast.error(e?.message || '筛选失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => run())
</script>

<style scoped>
.screener-page {
  max-width: 1200px;
  margin: 0 auto;
}
.form {
  margin-bottom: 8px;
}
.hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--text-secondary);
}
.meta {
  display: flex;
  gap: 18px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
