<template>
  <div class="strategy-page">
    <el-card shadow="hover">
      <template #header>
        <span>策略列表</span>
        <el-button type="primary" size="small" style="float: right" @click="toEditor()">新建策略</el-button>
      </template>
      <el-table :data="strategies" stripe>
        <el-table-column prop="id" label="ID" min-width="70" />
        <el-table-column prop="name" label="策略名称" min-width="160">
          <template #default="{ row }">{{ row.name ?? row.strategy_name ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" min-width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'running' ? 'success' : 'info'">
              {{ row.status === 'running' ? '运行中' : '已停止' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="pnl" label="盈亏" min-width="100">
          <template #default="{ row }">
            <span :class="(row.pnl ?? 0) >= 0 ? 'green' : 'red'">
              ¥{{ (row.pnl ?? 0) >= 0 ? '+' : '' }}{{ row.pnl ?? 0 }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="200">
          <template #default="{ row }">
            <el-button size="small" @click="toEditor(row.id)">编辑/配置</el-button>
            <el-button size="small" type="primary" @click="toBacktest(row.id)">回测</el-button>
            <el-button v-if="row.status === 'running'" type="warning" size="small" @click="toggleStrategy(row)">
              停止
            </el-button>
            <el-button v-else type="success" size="small" @click="toggleStrategy(row)">启动</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { strategyApi } from '@/api/strategy'

const router = useRouter()
const strategies = ref([])

const toEditor = (id) => {
  router.push(id ? { path: '/strategy/editor', query: { id } } : '/strategy/editor')
}
const toBacktest = (id) => {
  router.push({ path: '/strategy/backtest', query: { id } })
}
const toggleStrategy = (row) => {
  row.status = row.status === 'running' ? 'stopped' : 'running'
}

onMounted(async () => {
  try {
    const list = await strategyApi.getStrategyList()
    strategies.value = list || []
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
