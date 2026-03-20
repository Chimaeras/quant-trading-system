<template>
  <div class="strategy-page">
    <el-card shadow="hover">
      <template #header>
        <span>策略列表</span>
        <el-button type="primary" size="small" style="float: right">新建策略</el-button>
      </template>
      <el-table :data="strategies" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="策略名称" />
        <el-table-column prop="status" label="状态">
          <template #default="{ row }">
            <el-tag :type="row.status === 'running' ? 'success' : 'info'">
              {{ row.status === 'running' ? '运行中' : '已停止' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="pnl" label="盈亏">
          <template #default="{ row }">
            <span :class="row.pnl >= 0 ? 'green' : 'red'">
              ¥{{ row.pnl >= 0 ? '+' : '' }}{{ row.pnl }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button v-if="row.status === 'running'" type="warning" size="small" @click="toggleStrategy(row)">
              停止
            </el-button>
            <el-button v-else type="success" size="small" @click="toggleStrategy(row)">启动</el-button>
            <el-button size="small">配置</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getStrategyList } from '@/api/strategy'

const strategies = ref([])

const toggleStrategy = (row) => {
  row.status = row.status === 'running' ? 'stopped' : 'running'
}

onMounted(async () => {
  try {
    const res = await getStrategyList()
    strategies.value = res.data || []
  } catch (e) {
    strategies.value = [
      { id: 1, name: 'MA_Cross', status: 'running', pnl: 12500 },
      { id: 2, name: 'BOLL_Strategy', status: 'stopped', pnl: -3200 }
    ]
  }
})
</script>

<style scoped>
.green { color: #67c23a; }
.red { color: #f56c6c; }
</style>
