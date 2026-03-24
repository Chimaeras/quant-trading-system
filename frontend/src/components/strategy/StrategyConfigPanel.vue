<template>
  <div class="strategy-config">
    <div class="config-title">
      <span>指标参数</span>
      <el-button link type="primary" size="small" @click="resetToDefault">恢复默认</el-button>
    </div>
    <!-- MA_Cross 均线交叉 -->
    <template v-if="strategyName === 'MA_Cross'">
      <el-form label-width="120px" class="config-form">
        <el-form-item label="短均线周期">
          <el-input-number v-model="params.short_period" :min="2" :max="60" :step="1" controls-position="right" />
          <span class="param-desc">MA(short)，常用 5</span>
        </el-form-item>
        <el-form-item label="长均线周期">
          <el-input-number v-model="params.long_period" :min="5" :max="120" :step="1" controls-position="right" />
          <span class="param-desc">MA(long)，常用 20，须大于短均线</span>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon class="formula-tip">
          公式：短均线上穿长均线(金叉)买入，下穿(死叉)卖出
        </el-alert>
      </el-form>
    </template>
    <!-- BOLL_Strategy 布林带 -->
    <template v-else-if="strategyName === 'BOLL_Strategy'">
      <el-form label-width="120px" class="config-form">
        <el-form-item label="布林带周期">
          <el-input-number v-model="params.period" :min="5" :max="60" :step="1" controls-position="right" />
          <span class="param-desc">中轨计算周期，常用 20</span>
        </el-form-item>
        <el-form-item label="标准差倍数">
          <el-input-number v-model="params.std_dev" :min="1" :max="3" :step="0.1" :precision="1" controls-position="right" />
          <span class="param-desc">上/下轨 = 中轨 ± K×标准差，常用 2</span>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon class="formula-tip">
          公式：中轨=MA(close,N)，上轨=中轨+K×std，下轨=中轨-K×std；价格触及下轨买入，触及上轨卖出
        </el-alert>
      </el-form>
    </template>
    <!-- MACD_Strategy -->
    <template v-else-if="strategyName === 'MACD_Strategy'">
      <el-form label-width="120px" class="config-form">
        <el-form-item label="快线周期">
          <el-input-number v-model="params.fast" :min="5" :max="30" :step="1" controls-position="right" />
          <span class="param-desc">EMA(close, fast)，常用 12</span>
        </el-form-item>
        <el-form-item label="慢线周期">
          <el-input-number v-model="params.slow" :min="10" :max="50" :step="1" controls-position="right" />
          <span class="param-desc">EMA(close, slow)，常用 26，须大于快线</span>
        </el-form-item>
        <el-form-item label="信号线周期">
          <el-input-number v-model="params.signal" :min="5" :max="20" :step="1" controls-position="right" />
          <span class="param-desc">DEA = EMA(DIF, signal)，常用 9</span>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon class="formula-tip">
          公式：DIF=EMA(快)-EMA(慢)，DEA=EMA(DIF)，柱状图=DIF-DEA；金叉(DIF上穿DEA)买入，死叉卖出
        </el-alert>
      </el-form>
    </template>
    <el-empty v-else description="该策略无可配置指标" :image-size="60" />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

export interface StrategyParams {
  short_period?: number
  long_period?: number
  period?: number
  std_dev?: number
  fast?: number
  slow?: number
  signal?: number
}

const DEFAULT_PARAMS: Record<string, StrategyParams> = {
  MA_Cross: { short_period: 5, long_period: 20 },
  BOLL_Strategy: { period: 20, std_dev: 2 },
  MACD_Strategy: { fast: 12, slow: 26, signal: 9 }
}

const props = defineProps<{
  strategyName: string
  modelValue?: StrategyParams
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: StrategyParams): void
}>()

const params = ref<StrategyParams>({ ...getDefaultForStrategy(props.strategyName), ...props.modelValue })

function getDefaultForStrategy(name: string): StrategyParams {
  return { ...(DEFAULT_PARAMS[name] || {}) }
}

watch(() => props.strategyName, (name) => {
  const merged = { ...getDefaultForStrategy(name), ...(props.modelValue || {}) }
  params.value = merged
  emit('update:modelValue', merged)
}, { immediate: false })

watch(params, (v) => { emit('update:modelValue', { ...v }) }, { deep: true })

watch(() => props.modelValue, (v) => {
  if (!v || !Object.keys(v).length) return
  const next = { ...getDefaultForStrategy(props.strategyName), ...v }
  if (JSON.stringify(params.value) !== JSON.stringify(next)) params.value = next
}, { deep: true })

const resetToDefault = () => {
  params.value = getDefaultForStrategy(props.strategyName)
  emit('update:modelValue', params.value)
}
</script>

<style scoped>
.strategy-config { padding: 12px 0; }
.config-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; font-weight: 500; }
.config-form :deep(.el-input-number) { width: 120px; }
.param-desc { margin-left: 8px; color: #888; font-size: 12px; }
.formula-tip { margin-top: 8px; font-size: 12px; }
</style>
