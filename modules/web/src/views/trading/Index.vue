<template>
  <div class="trading-page">
    <el-row :gutter="20">
      <el-col :span="16">
        <el-card shadow="hover">
          <template #header>订单列表</template>
          <el-table :data="orders" stripe>
            <el-table-column prop="orderId" label="订单号" min-width="120" />
            <el-table-column prop="symbol" label="标的" min-width="100" />
            <el-table-column prop="action" label="方向" min-width="80">
              <template #default="{ row }">
                <el-tag :type="row.action === 'buy' ? 'success' : 'danger'">
                  {{ row.action === 'buy' ? '买入' : '卖出' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="volume" label="数量" min-width="90" />
            <el-table-column prop="price" label="价格" min-width="90" />
            <el-table-column prop="status" label="状态" min-width="95" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header>快捷下单</template>
          <el-form :model="orderForm" label-width="80px">
            <el-form-item label="标的">
              <el-input v-model="orderForm.symbol" placeholder="000001" />
            </el-form-item>
            <el-form-item label="方向">
              <el-radio-group v-model="orderForm.action">
                <el-radio value="buy">买入</el-radio>
                <el-radio value="sell">卖出</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="数量">
              <el-input-number v-model="orderForm.volume" :min="100" :step="100" />
            </el-form-item>
            <el-form-item label="价格">
              <el-input-number v-model="orderForm.price" :min="0" :precision="2" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="placeOrder">提交订单</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import request from '@/api/request'

const orders = ref([])
const orderForm = reactive({
  symbol: '000001',
  action: 'buy',
  volume: 1000,
  price: 12.5,
  type: 'limit'
})

const placeOrder = async () => {
  try {
    await request.post('/order/place', orderForm)
    orders.value.unshift({
      orderId: 'ORD' + Date.now(),
      ...orderForm,
      status: 'submitted'
    })
  } catch (e) {}
}
</script>
