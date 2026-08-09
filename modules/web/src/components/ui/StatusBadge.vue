<template>
  <el-tooltip :content="meaning || fallbackMeaning" :disabled="!meaning && !fallbackMeaning" placement="top">
    <el-tag :type="tagType" effect="plain" size="small" class="status-badge">
      {{ displayLabel }}
    </el-tag>
  </el-tooltip>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  /** 原始状态值（PASS/DEGRADED/BLOCKED/ABSTAIN/NO_DATA/ERROR/Beta/Legacy/…）。 */
  status?: string
  /** 展示文本（缺省由语义映射推断）。 */
  label?: string
  /** 语义说明（缺省由语义映射推断）。 */
  meaning?: string
  type?: 'success' | 'warning' | 'danger' | 'info'
}>(), {})

/**
 * V2-MP-028：统一 Capability/Run/Data 状态语义映射。
 * 五类状态必须有可判定 UI（不是只换颜色）：文本标签 + 语义说明 + 颜色三重表达。
 */
interface Semantics {
  label: string
  meaning: string
  type: 'success' | 'warning' | 'danger' | 'info'
}

const STATUS_MAP: Record<string, Semantics> = {
  // 系统/数据基座
  PASS: { label: '正常', meaning: '系统/数据基座就绪', type: 'success' },
  HEALTHY: { label: '正常', meaning: '服务健康', type: 'success' },
  ONLINE: { label: '在线', meaning: '在线可用', type: 'success' },
  DEGRADED: { label: '降级', meaning: '基座可用但降级（如行情迟到/日历未初始化）', type: 'warning' },
  LIMITED: { label: '受限', meaning: '能力受限（数据可靠度 limited / 覆盖不足）', type: 'warning' },
  STALE: { label: '迟到', meaning: '数据未到最新交易日', type: 'warning' },
  DELAYED: { label: '迟到', meaning: '数据延迟', type: 'warning' },
  BLOCKED: { label: '故障', meaning: '基座不可用（DB 断开/无行情/门禁 BLOCKED）', type: 'danger' },
  FAIL: { label: '失败', meaning: '任务/请求失败', type: 'danger' },
  FAILED: { label: '失败', meaning: '任务/请求失败', type: 'danger' },
  ERROR: { label: '错误', meaning: '发生错误（详见 errorId）', type: 'danger' },
  REJECTED: { label: '拒绝', meaning: '请求被拒绝（业务/门禁）', type: 'danger' },
  UNAVAILABLE: { label: '不可用', meaning: '能力当前不可用', type: 'danger' },
  UNAUTHORIZED: { label: '未授权', meaning: '无权限访问（401）', type: 'danger' },
  // 运行/任务
  PUBLISHED: { label: '已发布', meaning: 'run 已发布且可读', type: 'success' },
  FILLED: { label: '已填充', meaning: '数据已落库', type: 'success' },
  SUCCESS: { label: '成功', meaning: '操作成功', type: 'success' },
  PENDING: { label: '进行中', meaning: '任务等待执行', type: 'info' },
  RUNNING: { label: '运行中', meaning: '任务执行中', type: 'info' },
  CANCELLED: { label: '已取消', meaning: '任务已取消', type: 'info' },
  // 无决策/空态（区别于错误态）
  ABSTAIN: { label: '放弃', meaning: '该日无合格信号，主动放弃（MP-001）', type: 'info' },
  ABSTAINED: { label: '放弃', meaning: '该日无合格信号，主动放弃', type: 'info' },
  NO_ELIGIBLE_CANDIDATE: { label: '无合格候选', meaning: '无标的通过 eligibility 阈值', type: 'info' },
  NO_DATA: { label: '无数据', meaning: '该区间无数据（非错误）', type: 'info' },
  EMPTY: { label: '空', meaning: '结果为空（非错误）', type: 'info' },
  NOT_PUBLISHED: { label: '未发布', meaning: '该日无预测快照', type: 'info' },
  RECOVERY_REQUIRED: { label: '需恢复', meaning: '任务处于需恢复状态', type: 'warning' },
  // 能力成熟度标签
  BETA: { label: 'Beta', meaning: '新能力，接口/口径可能调整，非生产承诺', type: 'warning' },
  LEGACY: { label: 'Legacy', meaning: '旧版能力，仅维护，新功能不在此演进', type: 'info' },
  RESEARCH: { label: 'Research', meaning: '研究态，产出不可作为生产结果', type: 'info' },
  CHALLENGER: { label: '候选', meaning: '模型为 challenger，未晋级', type: 'info' }
}

const norm = computed(() => (props.status ?? '').trim().toUpperCase())

const semantics = computed<Semantics | null>(() => STATUS_MAP[norm.value] ?? null)

const displayLabel = computed(() => props.label ?? semantics.value?.label ?? props.status ?? '—')

const fallbackMeaning = computed(() =>
  semantics.value ? semantics.value.meaning : props.status ? `状态：${props.status}` : ''
)

const meaning = computed(() => props.meaning ?? semantics.value?.meaning ?? '')

const tagType = computed<'success' | 'warning' | 'danger' | 'info'>(() =>
  props.type ?? semantics.value?.type ?? 'info'
)
</script>

<style scoped>
.status-badge {
  font-variant-numeric: tabular-nums;
}
</style>
