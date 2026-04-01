<template>
  <el-card shadow="hover">
    <template #header>
      <span>策略列表</span>
      <el-button type="primary" size="small" @click="$router.push('/strategy/editor')">新建策略</el-button>
    </template>
    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="name" label="策略名称" min-width="180">
        <template #default="{ row }">{{ row.name ?? row.strategy_name }}</template>
      </el-table-column>
      <el-table-column prop="language" label="语言" min-width="80" />
      <el-table-column prop="created_at" label="创建时间" min-width="150" />
      <el-table-column label="操作" min-width="140">
        <template #default="{ row }">
          <el-button link type="primary" @click="edit(row)">编辑</el-button>
          <el-button link type="primary" @click="$router.push(`/backtest?id=${row.id}`)">回测</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { strategyApi } from '@/api/strategy'

const router = useRouter()
const loading = ref(false)
const list = ref<any[]>([])

const load = async () => {
  loading.value = true
  try {
    list.value = await strategyApi.getStrategyList()
  } finally {
    loading.value = false
  }
}

const edit = (row: any) => {
  router.push({ path: '/strategy/editor', query: { id: row.id } })
}

onMounted(load)
</script>
