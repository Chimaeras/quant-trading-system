<template>
  <PageShell>
    <PageHeader title="条件选股器" subtitle="MVP 条件选股器，基于股票基本信息筛选；实时行情过滤（涨跌幅 / 成交额）为后续扩展" />

    <SectionCard>
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
        <el-form-item label="排除ST">
          <el-switch v-model="form.excludeSt" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="resetPageAndRun">筛选</el-button>
        </el-form-item>
        <el-form-item label="模板名">
          <el-input v-model="templateName" placeholder="保存当前条件" style="width: 150px" clearable />
        </el-form-item>
        <el-form-item>
          <el-button :loading="saving" @click="saveTemplate">保存模板</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="loading">
        <template v-if="result">
          <div class="meta">
            <span>参考日：{{ result.reference_date }}</span>
            <span>关键字/交易所匹配：{{ result.total_before_filter }}</span>
            <span>过滤后：{{ result.total_after_filter }}</span>
            <span>当前页：{{ result.returned }} 条</span>
          </div>
          <el-table :data="result.items" size="small" stripe border>
            <el-table-column type="index" label="#" width="56" :index="(i: number) => (page - 1) * pageSize + i + 1" />
            <el-table-column prop="stock_code" label="代码" min-width="90" />
            <el-table-column prop="exchange_type" label="交易所" min-width="80" />
            <el-table-column prop="stock_name" label="名称" min-width="100" />
            <el-table-column prop="industry" label="行业" min-width="100" />
            <el-table-column prop="list_date" label="上市日" min-width="110" />
            <el-table-column prop="listing_days" label="上市天数" min-width="90" />
          </el-table>
          <EmptyState v-if="!result.items.length" message="无符合条件的标的" />
          <el-pagination
            v-if="result.total_after_filter > pageSize"
            v-model:current-page="page"
            :page-size="pageSize"
            :total="result.total_after_filter"
            layout="total, prev, pager, next"
            style="margin-top: 12px; justify-content: flex-end"
            @current-change="run"
          />
        </template>
        <EmptyState v-else-if="!loading" message="设置条件后点击筛选" />
      </div>
    </SectionCard>

    <SectionCard title="已保存模板">
      <el-table :data="templates" size="small" stripe border v-loading="loadingTpl">
        <el-table-column type="index" label="#" width="56" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="keyword" label="关键字" min-width="100" />
        <el-table-column prop="exchange" label="交易所" min-width="80" />
        <el-table-column prop="industry" label="行业" min-width="90" />
        <el-table-column prop="min_listing_days" label="上市≥天" min-width="90" />
        <el-table-column prop="created_at" label="创建时间" min-width="150">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="applyTemplate(row)">加载</el-button>
            <el-button link type="danger" :loading="deletingId === row.id" @click="removeTemplate(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loadingTpl && !templates.length" message="暂无保存的模板" />
    </SectionCard>
  </PageShell>
</template>

<script setup lang="ts">
import PageShell from '@/components/ui/PageShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { ref, reactive, onMounted } from 'vue'
import { runScreener, saveScreenerTemplate, listScreenerTemplates, deleteScreenerTemplate, type ScreenerResponse, type ScreenerTemplate } from '@/api/screener'
import { errorText } from '@/api/request'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const loading = ref(false)
const result = ref<ScreenerResponse | null>(null)
const page = ref(1)
const pageSize = ref(50)
const templateName = ref('')
const saving = ref(false)
const loadingTpl = ref(false)
const deletingId = ref<number | null>(null)
const templates = ref<ScreenerTemplate[]>([])

const form = reactive({
  keyword: '',
  exchange: '',
  industry: '',
  minListingDays: 60,
  excludeSt: true
})

async function run() {
  loading.value = true
  try {
    result.value = await runScreener({
      keyword: form.keyword || undefined,
      exchange: form.exchange || undefined,
      industry: form.industry || undefined,
      minListingDays: form.minListingDays || undefined,
      excludeSt: form.excludeSt || undefined,
      page: page.value,
      size: pageSize.value
    })
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function resetPageAndRun() {
  page.value = 1
  run()
}

function formatTime(value: string): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

async function loadTemplates() {
  loadingTpl.value = true
  try {
    templates.value = await listScreenerTemplates()
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    loadingTpl.value = false
  }
}

async function saveTemplate() {
  if (!templateName.value.trim()) {
    toast.warning('请输入模板名称')
    return
  }
  saving.value = true
  try {
    await saveScreenerTemplate({
      name: templateName.value.trim(),
      keyword: form.keyword || undefined,
      exchange: form.exchange || undefined,
      industry: form.industry || undefined,
      minListingDays: form.minListingDays || undefined,
      excludeSt: form.excludeSt || undefined
    })
    toast.success('模板已保存')
    templateName.value = ''
    await loadTemplates()
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    saving.value = false
  }
}

function applyTemplate(t: ScreenerTemplate) {
  form.keyword = t.keyword || ''
  form.exchange = t.exchange || ''
  form.industry = t.industry || ''
  form.minListingDays = t.min_listing_days || 60
  form.excludeSt = t.exclude_st ?? true
  toast.success('已加载模板：' + t.name)
  run()
}

async function removeTemplate(t: ScreenerTemplate) {
  if (!t.id) return
  deletingId.value = t.id
  try {
    await deleteScreenerTemplate(t.id)
    toast.success('已删除')
    await loadTemplates()
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    deletingId.value = null
  }
}

onMounted(() => {
  run()
  loadTemplates()
})
</script>

<style scoped>
.form {
  margin: 0 0 8px;
}
.hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--text-secondary);
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
