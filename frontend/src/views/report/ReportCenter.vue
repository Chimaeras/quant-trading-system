<template>
  <el-card shadow="hover">
    <template #header>报表中心</template>
    <el-form inline>
      <el-form-item label="年份">
        <el-select v-model="year" @change="load">
          <el-option v-for="y in years" :key="y" :label="`${y}年`" :value="y" />
        </el-select>
      </el-form-item>
    </el-form>
    <el-table :data="monthlyData" stripe>
      <el-table-column prop="month" label="月份" min-width="85" />
      <el-table-column prop="return" label="收益率(%)" min-width="115">
        <template #default="{ row }">
          <span :class="row.return >= 0 ? 'profit' : 'loss'">{{ row.return?.toFixed(2) ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="trades" label="交易笔数" min-width="110" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { reportApi } from '@/api/report'

const year = ref(new Date().getFullYear())
const years = ref([2024, 2023, 2022])
const monthlyData = ref<any[]>([])

const load = async () => {
  monthlyData.value = await reportApi.getMonthlyReturns(year.value)
}

onMounted(load)
</script>

<style scoped>
.profit { color: #67c23a; }
.loss { color: #f56c6c; }
</style>
