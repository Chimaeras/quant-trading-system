<template>
  <div class="account-page">
    <el-card shadow="hover">
      <template #header>账户概览</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="总资产">¥{{ accountInfo.totalAsset?.toLocaleString() }}</el-descriptions-item>
        <el-descriptions-item label="可用资金">¥{{ accountInfo.cash?.toLocaleString() }}</el-descriptions-item>
        <el-descriptions-item label="持仓市值">¥{{ accountInfo.positionValue?.toLocaleString() }}</el-descriptions-item>
        <el-descriptions-item label="今日盈亏">¥{{ accountInfo.todayPnl?.toLocaleString() }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <el-card shadow="hover" style="margin-top: 20px">
      <template #header>资金流水</template>
      <el-table :data="transactions" stripe>
        <el-table-column prop="time" label="时间" min-width="160" />
        <el-table-column prop="type" label="类型" min-width="100" />
        <el-table-column prop="amount" label="金额" min-width="110" />
        <el-table-column prop="balance" label="余额" min-width="110" />
      </el-table>
      <el-empty v-if="!transactions.length" description="暂无流水记录" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getAccountInfo } from '@/api/account'

const accountInfo = ref({})
const transactions = ref([])

onMounted(async () => {
  try {
    const res = await getAccountInfo()
    accountInfo.value = res.data || {}
  } catch (e) {
    accountInfo.value = {
      totalAsset: 500000,
      cash: 150000,
      positionValue: 350000,
      todayPnl: 12500
    }
  }
})
</script>
