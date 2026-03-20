<template>
  <el-card shadow="hover">
    <template #header>系统设置</template>
    <el-form label-width="120px" style="max-width: 500px">
      <el-form-item label="主题">
        <el-select v-model="theme" @change="applyTheme">
          <el-option label="深色" value="dark" />
          <el-option label="浅色" value="light" />
        </el-select>
      </el-form-item>
      <el-form-item label="佣金率(%)">
        <el-input-number v-model="commission" :min="0" :max="1" :step="0.01" />
      </el-form-item>
      <el-form-item label="印花税率(%)">
        <el-input-number v-model="stampTax" :min="0" :max="1" :step="0.01" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="save">保存</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useSystemStore } from '@/stores/system'

const systemStore = useSystemStore()
const theme = ref<'light' | 'dark'>('dark')
const commission = ref(0.0003)
const stampTax = ref(0.001)

onMounted(() => {
  theme.value = (systemStore.theme || 'dark') as 'light' | 'dark'
  commission.value = systemStore.commissionRate ?? 0.0003
  stampTax.value = systemStore.stampDuty ?? 0.001
})

const applyTheme = () => {
  systemStore.setTheme(theme.value as 'light' | 'dark')
}

const save = () => {
  systemStore.theme = theme.value as 'light' | 'dark'
  systemStore.commissionRate = commission.value
  systemStore.stampDuty = stampTax.value
  ElMessage.success('保存成功')
}
</script>
